package com.adnostr.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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

import com.adnostr.app.databinding.FragmentCreateAdBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ad Creation Interface for Advertisers.
 * UPDATED: Restored detailed Advertiser Logging to Console and routed IPFS errors to Network Console.
 * FIXED: Changed Kind to 30001 as per Ad Event specification.
 * FIXED: Included mandatory 'd' tag for Kind 30001 compliance to fix relay indexing.
 * FIXED: Displays discovered usernames in brackets during Reach Discovery.
 * FIXED: Enforced manual string construction for content JSON to resolve "invalid: bad event id".
 * NEW: Implemented real IPFSHelper upload logic and multi-image JSONArray support.
 * NEW: Saves broadcasted ads to Advertiser History DB.
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
                        // Support for single selection
                        if (result.getData().getData() != null) {
                            handleSelectedImage(result.getData().getData());
                        } 
                        // Support for multiple selection if enabled in Intent
                        else if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            for (int i = 0; i < count; i++) {
                                handleSelectedImage(result.getData().getClipData().getItemAt(i).getUri());
                            }
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
     * Support multiple image selection for the swiping feature.
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Enable multi-selection
        imagePickerLauncher.launch(intent);
    }

    /**
     * REAL LOGIC: Resolves the Uri to a File and calls IPFSHelper for decentralized storage.
     */
    private void handleSelectedImage(Uri uri) {
        try {
            File file = new File(getRealPathFromURI(uri));
            if (!file.exists()) {
                Toast.makeText(getContext(), "Error: Could not resolve file path.", Toast.LENGTH_SHORT).show();
                return;
            }

            binding.tvImageCount.setText("Uploading to IPFS...");

            IPFSHelper.uploadImage(file, new IPFSHelper.IPFSUploadCallback() {
                @Override
                public void onSuccess(String cid, String gatewayUrl) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            // Collect the real ipfs:// URL
                            ipfsImageCIDs.add("ipfs://" + cid);
                            binding.tvImageCount.setText(ipfsImageCIDs.size() + " Media Items Ready");
                            Toast.makeText(getContext(), "Decentralized Upload Success", Toast.LENGTH_SHORT).show();
                        });
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            binding.tvImageCount.setText("Upload Failed");
                            
                            // FIX: Extract raw Java exception and show it in the Big Screen Console instead of a Toast
                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            e.printStackTrace(pw);
                            String rawStackTrace = sw.toString();

                            RelayReportDialog dialog = RelayReportDialog.newInstance(
                                    "IPFS UPLOAD FAILURE",
                                    "Error communicating with decentralized storage gateway.",
                                    "RAW EXCEPTION:\n" + rawStackTrace
                            );
                            dialog.showSafe(getChildFragmentManager(), "IPFS_ERROR_CONSOLE");
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "File resolution failed: " + e.getMessage());
        }
    }

    /**
     * Utility to resolve content Uri to physical file path.
     */
    private String getRealPathFromURI(Uri contentUri) {
        String result;
        Cursor cursor = requireContext().getContentResolver().query(contentUri, null, null, null, null);
        if (cursor == null) {
            result = contentUri.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
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

        List<String> tagsToSearch = new ArrayList<>();
        for (String s : tagsInput.split(",")) {
            String clean = s.trim().toLowerCase().replace("#", "");
            if (!clean.isEmpty()) tagsToSearch.add(clean);
        }

        StringBuilder discoveryLogs = new StringBuilder();
        discoveryLogs.append("INITIATING REACH DISCOVERY...\n\n");
        discoveryLogs.append("Tags formatted for Nostr search: ").append(tagsToSearch.toString()).append("\n");

        RelayReportDialog dialog = RelayReportDialog.newInstance(
                "DISCOVERY CONSOLE",
                "Scanning decentralized network...",
                discoveryLogs.toString()
        );
        dialog.showSafe(getChildFragmentManager(), "DISCOVERY_CONSOLE");

        ReachDiscoveryHelper.discoverGlobalReach(requireContext(), tagsToSearch, new ReachDiscoveryHelper.ReachCallback() {
            @Override
            public void onReachCalculated(int totalUsers, List<String> usernames) {
                if (isAdded() && binding != null) {
                    requireActivity().runOnUiThread(() -> {
                        String resultText = totalUsers + " Active Users Found";
                        if (usernames != null && !usernames.isEmpty()) {
                            StringBuilder names = new StringBuilder(" (");
                            for (int i = 0; i < usernames.size(); i++) {
                                names.append(usernames.get(i));
                                if (i < usernames.size() - 1) names.append(", ");
                            }
                            names.append(")");
                            resultText += names.toString();
                        }
                        binding.tvActiveWatchers.setText(resultText);

                        RelayReportDialog existing = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("DISCOVERY_CONSOLE");
                        if (existing != null) {
                            discoveryLogs.append("\n[SUCCESS] Found ").append(totalUsers).append(" unique users.");
                            existing.updateTechnicalLogs("Scan Complete", discoveryLogs.toString());
                        }
                    });
                }
            }

            @Override
            public void onDiscoveryError(String error) {
                if (isAdded() && binding != null) {
                    requireActivity().runOnUiThread(() -> {
                        binding.tvActiveWatchers.setText("Reach: 0 (Offline)");
                    });
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
            }
        });
    }

    private void prepareAndBroadcastAd() {
        String title = binding.etAdTitle.getText().toString().trim();
        String desc = binding.etAdDescription.getText().toString().trim();
        String tagsInput = binding.etAdTags.getText().toString().trim();
        String whatsapp = binding.etWhatsapp.getText().toString().trim();

        if (title.isEmpty() || desc.isEmpty() || tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Please provide all ad details", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // CRITICAL FIX: Convert the IPFS List to a JSON Array for swiping support
            StringBuilder imageJsonBuilder = new StringBuilder("[");
            for (int i = 0; i < ipfsImageCIDs.size(); i++) {
                imageJsonBuilder.append("\"").append(ipfsImageCIDs.get(i)).append("\"");
                if (i < ipfsImageCIDs.size() - 1) imageJsonBuilder.append(",");
            }
            imageJsonBuilder.append("]");
            String imagePayload = imageJsonBuilder.toString();

            String cleanPhone = whatsapp.replaceAll("[^\\d]", "");
            String ctaUrl = whatsapp.isEmpty() ? "" : "https://wa.me/" + cleanPhone;

            // FIXED: Strict manual string construction to prevent event ID mismatch
            String contentStr = "{" +
                    "\"title\":\"" + title.replace("\"", "\\\"") + "\"," +
                    "\"desc\":\"" + desc.replace("\"", "\\\"") + "\"," +
                    "\"image\":" + imagePayload + "," +
                    "\"cta\":\"" + ctaUrl + "\"," +
                    "\"maps\":\"" + capturedMapsUrl + "\"," +
                    "\"expiry\":\"2026-05-01\"" +
                    "}";

            JSONObject event = new JSONObject();
            event.put("kind", 30001); 
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", contentStr);

            JSONArray tags = new JSONArray();
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_ad_" + System.currentTimeMillis());
            tags.put(dTag);

            for (String t : tagsInput.split(",")) {
                String cleanTag = t.trim().toLowerCase().replace("#", ""); 
                if (cleanTag.isEmpty()) continue;
                JSONArray tagPair = new JSONArray();
                tagPair.put("t");
                tagPair.put(cleanTag);
                tags.put(tagPair);
            }
            event.put("tags", tags);

            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);

            if (signedEvent != null) {
                // BROADCAST MESSAGE: Construct the full Nostr array message for storage
                JSONArray fullMsg = new JSONArray();
                fullMsg.put("EVENT");
                fullMsg.put(""); // subId placeholder
                fullMsg.put(signedEvent);
                
                // NEW: Save successfully signed ad to local Advertiser History DB
                db.saveToAdvertiserHistory(fullMsg.toString());

                broadcastToNetwork(signedEvent);
            } else {
                Toast.makeText(getContext(), "Signing Failed", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Ad Preparation Error: " + e.getMessage());
            throw new RuntimeException("Ad Broadcast Preparation Failed", e);
        }
    }

    private void broadcastToNetwork(JSONObject signedEvent) {
        Set<String> relayPool = db.getRelayPool();
        StringBuilder technicalLogs = new StringBuilder();
        technicalLogs.append("AD BROADCAST STATUS:\n\n");

        final int totalNodes = relayPool.size();
        final int[] finishedNodes = {0};

        NostrPublisher.publishToPool(relayPool, signedEvent, (relayUrl, success, message) -> {
            finishedNodes[0]++;
            
            // FIX: Restoring the highly detailed advertiser logging logic.
            // Appends [OK]/[FAIL] and then explicitly appends the exact Payload/ACK message below it.
            technicalLogs.append(success ? "[OK] " : "[FAIL] ").append(relayUrl).append("\n");
            if (message != null && !message.isEmpty()) {
                technicalLogs.append(" > ").append(message).append("\n\n");
            } else {
                technicalLogs.append("\n");
            }

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    RelayReportDialog existingDialog = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("AD_CONSOLE");
                    if (existingDialog != null) {
                        existingDialog.updateTechnicalLogs("Relays: " + finishedNodes[0] + "/" + totalNodes, technicalLogs.toString());
                    } else {
                        RelayReportDialog dialog = RelayReportDialog.newInstance("AD BROADCAST CONSOLE", "Broadcasting...", technicalLogs.toString());
                        dialog.showSafe(getChildFragmentManager(), "AD_CONSOLE");
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