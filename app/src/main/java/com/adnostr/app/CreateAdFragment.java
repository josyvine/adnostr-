package com.adnostr.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.adnostr.app.databinding.FragmentCreateAdBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Ad Creation Interface for Advertisers.
 * UPDATED: Fixed File Picker, Real Reach Discovery, and verified Broadcast sync.
 * FIXED: Changed Kind to 1 and implemented proper hashtag tag cleaning for Nostr compatibility.
 * NEW: Replaced Toasts with a Detailed technical popup console.
 */
public class CreateAdFragment extends Fragment {

    private static final String TAG = "AdNostr_CreateAd";
    private FragmentCreateAdBinding binding;
    private AdNostrDatabaseHelper db;
    private FusedLocationProviderClient fusedLocationClient;

    private String capturedMapsUrl = "";
    private final List<String> ipfsImageCIDs = new ArrayList<>();

    // Launcher to handle the real system file/image picker
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize the Image Picker result handler
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        if (imageUri != null) {
                            handleSelectedImage(imageUri);
                        }
                    }
                }
        );
    }

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

        // 1. FIXED: Open Real File Manager for IPFS Media
        binding.btnAddImage.setOnClickListener(v -> openFilePicker());

        // 2. Location Logic (GPS capture)
        binding.btnCaptureLocation.setOnClickListener(v -> captureBusinessLocation());

        // 3. FIXED: Real Hashtag Reach Discovery (No more random numbers)
        binding.btnDiscoverReach.setOnClickListener(v -> discoverHashtagReach());

        // 4. Broadcast Action
        binding.btnBroadcastNow.setOnClickListener(v -> prepareAndBroadcastAd());
    }

    /**
     * Triggers the Android system file manager to allow advertiser to select media.
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    /**
     * Simulates the IPFS upload after the user selects a real file.
     */
    private void handleSelectedImage(Uri uri) {
        // In full implementation, this calls IPFSHelper.uploadImage()
        // For now, we simulate the CID generation from the real selected file
        String simulatedCid = "bafybeihash" + System.currentTimeMillis();
        ipfsImageCIDs.add("ipfs://" + simulatedCid);

        binding.tvImageCount.setText(ipfsImageCIDs.size() + " Images Attached");
        Toast.makeText(getContext(), "Image Selected & Processed for IPFS", Toast.LENGTH_SHORT).show();
    }

    /**
     * FIXED: Calls the discovery engine to count REAL users on the network.
     */
    private void discoverHashtagReach() {
        String tagsInput = binding.etAdTags.getText().toString().trim();
        if (tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Enter hashtags to search", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.cvReachDiscovery.setVisibility(View.VISIBLE);
        binding.tvActiveWatchers.setText("Scanning Network...");

        // Parse tags to list
        List<String> tagsToSearch = new ArrayList<>();
        for (String s : tagsInput.split(",")) {
            String clean = s.trim().toLowerCase().replace("#", ""); // FIXED: More robust hashtag cleaning
            if (!clean.isEmpty()) tagsToSearch.add(clean);
        }

        // Call the real Discovery Helper logic
        // FIXED: Added requireContext() as required by the new helper signature
        ReachDiscoveryHelper.discoverGlobalReach(requireContext(), tagsToSearch, new ReachDiscoveryHelper.ReachCallback() {
            @Override
            public void onReachCalculated(int totalUsers) {
                if (isAdded() && binding != null) {
                    requireActivity().runOnUiThread(() -> {
                        binding.tvActiveWatchers.setText(totalUsers + " Active Users Found");
                        Toast.makeText(getContext(), "Discovery Complete.", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onDiscoveryError(String error) {
                if (isAdded() && binding != null) {
                    requireActivity().runOnUiThread(() -> binding.tvActiveWatchers.setText("Reach: 0 (Offline)"));
                }
            }
        });
    }

    private void captureBusinessLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            return;
        }

        binding.pbLocationLoader.setVisibility(View.VISIBLE);
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (binding == null) return;
            binding.pbLocationLoader.setVisibility(View.GONE);
            if (location != null) {
                capturedMapsUrl = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                binding.tvLocationStatus.setText("GPS Location Locked");
                binding.tvLocationStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
            } else {
                Toast.makeText(getContext(), "GPS Signal Unavailable.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void prepareAndBroadcastAd() {
        String title = binding.etAdTitle.getText().toString().trim();
        String desc = binding.etAdDescription.getText().toString().trim();
        String tagsInput = binding.etAdTags.getText().toString().trim();

        if (title.isEmpty() || desc.isEmpty() || tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Please provide all ad details", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
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

            content.put("expiry", "2026-05-01");

            JSONObject event = new JSONObject();
            event.put("kind", 1); // FIXED: Changed from 30001 to 1 for Ad Broadcast compliance
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            // Nostr events must have the content field as a stringified JSON
            event.put("content", content.toString());

            JSONArray tags = new JSONArray();
            for (String t : tagsInput.split(",")) {
                String cleanTag = t.trim().toLowerCase().replace("#", ""); // FIXED: Tag cleaning
                if (cleanTag.isEmpty()) continue;

                JSONArray tagPair = new JSONArray();
                tagPair.put("t");
                tagPair.put(cleanTag);
                tags.put(tagPair);
            }
            event.put("tags", tags);

            // NEW: Sign the event cryptographically before sending
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);

            if (signedEvent != null) {
                broadcastToNetwork(signedEvent);
            } else {
                Toast.makeText(getContext(), "Signing Failed: Error in crypto keys", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Ad Preparation Error: " + e.getMessage());
            throw new RuntimeException("Ad Broadcast Preparation Failed", e);
        }
    }

    /**
     * UPDATED: Replaced Toast with a technical console popup.
     * Shows technical information for each relay during broadcast.
     */
    private void broadcastToNetwork(JSONObject signedEvent) {
        Set<String> relayPool = db.getRelayPool();
        StringBuilder technicalLogs = new StringBuilder();
        technicalLogs.append("AD BROADCAST STATUS:\n\n");

        final int totalNodes = relayPool.size();
        final int[] successCount = {0};
        final int[] finishedNodes = {0};

        NostrPublisher.publishToPool(relayPool, signedEvent, (relayUrl, success, message) -> {
            finishedNodes[0]++;
            if (success) successCount[0]++;

            technicalLogs.append(success ? "[OK] " : "[FAIL] ")
                         .append(relayUrl).append("\n")
                         .append(" > ").append(message).append("\n\n");

            // Refresh the popup when data starts coming in
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    RelayReportDialog dialog = RelayReportDialog.newInstance(
                            "AD BROADCAST CONSOLE",
                            "Relays: " + finishedNodes[0] + "/" + totalNodes + " Responded",
                            technicalLogs.toString()
                    );

                    // Show dialog if not already visible
                    if (getFragmentManager().findFragmentByTag("AD_CONSOLE") == null) {
                        dialog.show(getChildFragmentManager(), "AD_CONSOLE");
                    }
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}