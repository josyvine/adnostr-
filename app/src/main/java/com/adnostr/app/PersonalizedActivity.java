package com.adnostr.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityPersonalizedBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FEATURE 4: Personalized Topics Selector.
 * Allows users to choose business/interest categories.
 * Broadcasts selection as Kind 30003 for directory discovery.
 */
public class PersonalizedActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Personalized";
    private ActivityPersonalizedBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    private TopicAdapter adapter;

    // Predefined professional marketplace categories
    private final List<String> availableTopics = Arrays.asList(
            "Mobiles & Tech",
            "Real Estate",
            "Vehicles",
            "Electronics",
            "Fashion & Beauty",
            "Home & Living",
            "Jobs & Services",
            "Health & Fitness",
            "Education",
            "Food & Dining"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Standard AdNostr Dark Theme setup
        getWindow().setStatusBarColor(android.graphics.Color.parseColor("#121212"));

        binding = ActivityPersonalizedBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        setupTopicList();

        binding.btnCancelPersonalized.setOnClickListener(v -> finish());
        binding.btnSaveTopics.setOnClickListener(v -> saveAndBroadcastTopics());
    }

    /**
     * Initializes the topic list with checkboxes.
     * Loads existing selection from database if available.
     */
    private void setupTopicList() {
        binding.rvTopicList.setLayoutManager(new LinearLayoutManager(this));
        
        // Retrieve current saved topics (Feature 4 database enhancement)
        Set<String> savedTopics = db.getInterests(); // Re-using interests set for topic parity
        
        adapter = new TopicAdapter(availableTopics, savedTopics);
        binding.rvTopicList.setAdapter(adapter);
    }

    /**
     * Saves topics locally and broadcasts Kind 30003 to the Nostr Network.
     */
    private void saveAndBroadcastTopics() {
        List<String> selected = adapter.getSelectedTopics();

        if (selected.isEmpty()) {
            Toast.makeText(this, "Please select at least one topic.", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnSaveTopics.setEnabled(false);
        binding.btnSaveTopics.setText("BROADCASTING...");

        try {
            // 1. Save locally for directory filtering
            db.saveInterests(new HashSet<>(selected));

            // 2. Prepare Kind 30003 (Sets/Interests)
            JSONArray topicsArray = new JSONArray();
            for (String topic : selected) {
                topicsArray.put(topic);
            }

            JSONObject event = new JSONObject();
            event.put("kind", 30003);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            
            // Content is the JSON string of the topics array
            event.put("content", topicsArray.toString());

            // NIP-51 requirement: d-tag for sets
            JSONArray tags = new JSONArray();
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_personalized_topics");
            tags.put(dTag);
            
            // Add 't' tags for relay indexing
            for(String topic : selected) {
                JSONArray tTag = new JSONArray();
                tTag.put("t");
                tTag.put(topic.toLowerCase().replace(" ", "_"));
                tags.put(tTag);
            }
            event.put("tags", tags);

            // 3. Sign and Broadcast
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signedEvent != null) {
                wsManager.broadcastEvent(signedEvent.toString());
                Log.i(TAG, "Personalized Topics Broadcasted: " + topicsArray.toString());
                
                Toast.makeText(this, "Profile Updated Locally & Globally", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                throw new Exception("Signing failed");
            }

        } catch (Exception e) {
            Log.e(TAG, "Save failed: " + e.getMessage());
            Toast.makeText(this, "Broadcast Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            binding.btnSaveTopics.setEnabled(true);
            binding.btnSaveTopics.setText("SAVE & BROADCAST");
        }
    }
}