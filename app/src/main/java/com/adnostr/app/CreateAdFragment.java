package com.adnostr.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.adnostr.app.databinding.FragmentCreateAdBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Ad Creation Interface for Advertisers.
 * Handles IPFS media links, Google Maps GPS capture, and Nostr kind:30001 
 * event generation and broadcasting.
 */
public class CreateAdFragment extends Fragment {

    private static final String TAG = "AdNostr_CreateAd";
    private FragmentCreateAdBinding binding;
    private AdNostrDatabaseHelper db;
    private FusedLocationProviderClient fusedLocationClient;

    private String capturedMapsUrl = "";
    private List<String> ipfsImageCIDs = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCreateAdBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());

        // 1. Setup Media/IPFS logic
        binding.btnAddImage.setOnClickListener(v -> {
            // Trigger image picker -> then IPFSHelper.upload()
            // For now, we mock an IPFS CID result
            simulateIpfsUpload();
        });

        // 2. Setup Location Logic (GPS capture)
        binding.btnCaptureLocation.setOnClickListener(v -> {
            captureBusinessLocation();
        });

        // 3. Setup Broadcast Action
        binding.btnBroadcastNow.setOnClickListener(v -> {
            prepareAndBroadcastAd();
        });
    }

    /**
     * Uses Google Play Services to get the current coordinates for the ad.
     * Generates a standard Google Maps URL as per tech specs.
     */
    private void captureBusinessLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            return;
        }

        binding.pbLocationLoader.setVisibility(View.VISIBLE);
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            binding.pbLocationLoader.setVisibility(View.GONE);
            if (location != null) {
                capturedMapsUrl = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                binding.tvLocationStatus.setText("GPS Location Captured Successfully");
                binding.tvLocationStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
            } else {
                Toast.makeText(getContext(), "GPS Signal Unavailable. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Mocks the IPFS CID return for UI logic.
     */
    private void simulateIpfsUpload() {
        String mockCid = "bafybeihash" + System.currentTimeMillis();
        ipfsImageCIDs.add("ipfs://" + mockCid);
        Toast.makeText(getContext(), "Image uploaded to IPFS", Toast.LENGTH_SHORT).show();
        binding.tvImageCount.setText(ipfsImageCIDs.size() + " Images Attached");
    }

    /**
     * Gathers all form data and generates the kind:30001 Nostr event.
     */
    private void prepareAndBroadcastAd() {
        String title = binding.etAdTitle.getText().toString().trim();
        String desc = binding.etAdDescription.getText().toString().trim();
        String tagsInput = binding.etAdTags.getText().toString().trim();

        if (title.isEmpty() || desc.isEmpty() || tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 1. Build the content JSON
            JSONObject content = new JSONObject();
            content.put("title", title);
            content.put("desc", desc);
            
            JSONArray images = new JSONArray();
            for (String cid : ipfsImageCIDs) images.put(cid);
            content.put("images", images);

            JSONObject links = new JSONObject();
            links.put("maps", capturedMapsUrl);
            links.put("whatsapp", binding.etWhatsapp.getText().toString().trim());
            content.put("links", links);
            
            content.put("expiry", "2026-05-01"); // Example expiry

            // 2. Build the Nostr Event Object
            JSONObject event = new JSONObject();
            event.put("kind", 30001);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", content.toString());

            // 3. Build Tags (Targeting)
            JSONArray tags = new JSONArray();
            String[] tagsArray = tagsInput.split(",");
            for (String t : tagsArray) {
                JSONArray tagPair = new JSONArray();
                tagPair.put("t");
                tagPair.put(t.trim().toLowerCase());
                tags.put(tagPair);
            }
            event.put("tags", tags);

            // 4. Send to WebSockets
            broadcastToNetwork(event.toString());

        } catch (Exception e) {
            Log.e(TAG, "Ad Generation Failed: " + e.getMessage());
            throw new RuntimeException("Broadcast Preparation Failed", e);
        }
    }

    private void broadcastToNetwork(String signedEventJson) {
        // Logic will bridge to WebSocketClientManager to push to relays
        Log.i(TAG, "Broadcasting Ad Event: " + signedEventJson);
        Toast.makeText(getContext(), "Ad Broadcasted to Decentralized Network!", Toast.LENGTH_LONG).show();
        Navigation.findNavController(requireView()).navigateUp();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}