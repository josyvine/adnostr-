package com.adnostr.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityBrowseAdvertisersBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FEATURE 4: Browse Advertisers Directory.
 * Logic: Fetches Kind 30003 (Sets) from relays -> Filters by matching Topics -> Displays Directory.
 * Allows users to find businesses relevant to their "Personalized" profile.
 */
public class BrowseAdvertisersActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Browse";
    private ActivityBrowseAdvertisersBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;

    private final List<AdvertiserProfile> fullList = new ArrayList<>();
    private final List<AdvertiserProfile> filteredList = new ArrayList<>();
    private AdvertiserProfileAdapter adapter;
    private Set<String> myTopics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityBrowseAdvertisersBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        // 1. Setup Toolbar
        setSupportActionBar(binding.toolbarBrowse);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Discover Businesses");
        }

        // 2. Load User Interests for filtering
        myTopics = db.getInterests();

        setupRecyclerView();
        setupSearchView();

        // 3. Initiate Network Scan
        startAdvertiserDiscovery();
    }

    private void setupRecyclerView() {
        binding.rvAdvertiserDirectory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AdvertiserProfileAdapter(filteredList, profile -> {
            // Logic for Feature 5: Open Advertiser Store
            Intent intent = new Intent(this, AdvertiserProfileActivity.class);
            intent.putExtra("ADVERTISER_PUBKEY", profile.pubkey);
            intent.putExtra("BUSINESS_NAME", profile.name);
            startActivity(intent);
        });
        binding.rvAdvertiserDirectory.setAdapter(adapter);
    }

    private void setupSearchView() {
        binding.searchViewAdvertisers.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterResults(newText);
                return true;
            }
        });
    }

    /**
     * Subscribes to Kind 30003 events to find advertiser topic sets.
     */
    private void startAdvertiserDiscovery() {
        binding.pbBrowseLoading.setVisibility(View.VISIBLE);
        fullList.clear();
        filteredList.clear();

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30003));
            // Specifically look for AdNostr topic profiles
            filter.put("#d", new JSONArray().put("adnostr_personalized_topics"));

            String subId = "browse-" + UUID.randomUUID().toString().substring(0, 6);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override public void onRelayConnected(String url) { wsManager.subscribe(url, req); }
                @Override public void onRelayDisconnected(String url, String reason) {}
                @Override public void onError(String url, Exception ex) {}

                @Override
                public void onMessageReceived(String url, String message) {
                    runOnUiThread(() -> processDirectoryEvent(message));
                }
            });

            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            Log.e(TAG, "Discovery Error: " + e.getMessage());
            binding.pbBrowseLoading.setVisibility(View.GONE);
        }
    }

    private void processDirectoryEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            JSONArray msg = new JSONArray(rawMessage);
            String type = msg.getString(0);

            if ("EVENT".equals(type)) {
                JSONObject event = msg.getJSONObject(2);
                String pubkey = event.getString("pubkey");
                String content = event.getString("content");

                // Check for duplicate profiles from different relays
                for (AdvertiserProfile p : fullList) {
                    if (p.pubkey.equals(pubkey)) return;
                }

                // 1. Parse Advertiser Topics
                JSONArray topicsArr = new JSONArray(content);
                List<String> advTopics = new ArrayList<>();
                for (int i = 0; i < topicsArr.length(); i++) {
                    advTopics.add(topicsArr.getString(i));
                }

                // 2. Local Trust/Interest Matching
                // Only show advertisers that share at least one topic with the user
                boolean isMatch = false;
                for (String t : advTopics) {
                    if (myTopics.contains(t)) {
                        isMatch = true;
                        break;
                    }
                }

                if (isMatch) {
                    // Logic: Normally we'd fetch Kind 0 for the real name, 
                    // for now we use a truncated pubkey as a placeholder.
                    String name = "Business " + pubkey.substring(0, 6);
                    fullList.add(new AdvertiserProfile(pubkey, name, advTopics));
                    filterResults(binding.searchViewAdvertisers.getQuery().toString());
                }

            } else if ("EOSE".equals(type)) {
                binding.pbBrowseLoading.setVisibility(View.GONE);
                if (fullList.isEmpty()) binding.tvNoAdvertisers.setVisibility(View.VISIBLE);
            }

        } catch (Exception ignored) {}
    }

    private void filterResults(String query) {
        filteredList.clear();
        if (query.isEmpty()) {
            filteredList.addAll(fullList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AdvertiserProfile p : fullList) {
                if (p.name.toLowerCase().contains(lowerQuery)) {
                    filteredList.add(p);
                }
            }
        }
        adapter.notifyDataSetChanged();
        binding.tvNoAdvertisers.setVisibility(filteredList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Placeholder for future filters (Top Rated, Nearest, etc.)
        getMenuInflater().inflate(R.menu.menu_browse_advertisers, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Internal data model for the directory profiles.
     */
    public static class AdvertiserProfile {
        String pubkey, name;
        List<String> topics;

        AdvertiserProfile(String pk, String n, List<String> t) {
            this.pubkey = pk; this.name = n; this.topics = t;
        }
    }
}