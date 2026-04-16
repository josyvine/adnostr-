package com.adnostr.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Html;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ad Creation Interface for Advertisers.
 * UPDATED: Replaced unstable Blossom/NIP-96 with Private Advertiser-Owned Cloudflare R2 Storage.
 * FIXED: Included mandatory 'd' tag for Kind 30001 compliance to fix relay indexing.
 * FIXED: Enforced manual string construction for content JSON to resolve "invalid: bad event id".
 * NEW: Every image is AES-GCM encrypted locally before upload; Key is shared in the Nostr Event.
 * FIXED: Detailed Forensic Logs are now piped to the UI console in real-time.
 * ENHANCEMENT: Master App-Level Encryption completely shields ad payloads from external clients.
 * ENHANCEMENT: Hybrid Hashtag Registry integrated to lock/protect private discovery tags.
 * 
 * OVERHAUL UPDATES:
 * - Implements 3-Step UI State Machine (ViewFlipper).
 * - Integrated DNS/URL Validation Engine for Website/Instagram.
 * - Implements Native Multi-Image Picker (ACTION_GET_CONTENT).
 * - Intelligent Description Chunking for synchronized sliders.
 * - Ad Preview trigger for testing without broadcast.
 * - NEW: Professional Rich Text Editor integration via Pencil Icon.
 */
public class CreateAdFragment extends Fragment {

    private static final String TAG = "AdNostr_CreateAd";
    private FragmentCreateAdBinding binding;
    private AdNostrDatabaseHelper db;
    private FusedLocationProviderClient fusedLocationClient;

    private String capturedMapsUrl = "";
    private int currentStep = 1;

    // Core state for new Media system
    private final List<String> uploadedMediaUrls = new ArrayList<>();
    private final List<String> deletionUrlsForSession = new ArrayList<>();
    private String currentAdAesKeyHex = "";

    // Launcher to handle the real system file/image picker
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    // ENHANCEMENT: Launcher for the Professional Rich Text Editor
    private ActivityResultLauncher<Intent> richTextLauncher;

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

        // ENHANCEMENT: Handle return from Professional Rich Text Editor
        richTextLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        String formattedHtml = result.getData().getStringExtra("FORMATTED_HTML");
                        if (formattedHtml != null) {
                            // FIXED: Translate HTML tags (like <p>, <b>, etc.) into invisible formatted spans.
                            // This stops raw HTML code from showing inside the preview box.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                binding.etAdDescription.setText(Html.fromHtml(formattedHtml, Html.FROM_HTML_MODE_LEGACY));
                            } else {
                                binding.etAdDescription.setText(Html.fromHtml(formattedHtml));
                            }
                            Log.d(TAG, "Rich Text Editor returned formatted HTML payload and rendered successfully.");
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

        // 1. Navigation Flow
        binding.btnNext.setOnClickListener(v -> handleNextStep());
        binding.btnBack.setOnClickListener(v -> handleBackStep());

        // 2. Open Real File Manager for Media
        binding.btnAddImage.setOnClickListener(v -> openFilePicker());

        // 3. Location Logic (GPS capture)
        binding.btnCaptureLocation.setOnClickListener(v -> captureBusinessLocation());

        // 4. Real Hashtag Reach Discovery (NOW ROUTED THROUGH HYBRID REGISTRY)
        binding.btnDiscoverReach.setOnClickListener(v -> discoverHashtagReach());

        // 5. Ad Preview Action
        binding.btnPreviewAd.setOnClickListener(v -> prepareAndBroadcastAd(true));

        // 6. Broadcast Action
        binding.btnBroadcastNow.setOnClickListener(v -> prepareAndBroadcastAd(false));

        // ENHANCEMENT: Launch Rich Text Editor on Pencil click
        binding.ivEditRichText.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), RichTextEditorActivity.class);
            // Pass the current text (if any) to the editor
            intent.putExtra("EXISTING_TEXT", binding.etAdDescription.getText().toString());
            richTextLauncher.launch(intent);
        });

        // Initialize Step 1 UI
        updateStepUI();
    }

    /**
     * Toggles the ViewFlipper and validation logic for step transitions.
     */
    private void handleNextStep() {
        if (currentStep == 1) {
            // Validate Step 1: Must have images and a title
            if (binding.etAdTitle.getText().toString().trim().isEmpty() || uploadedMediaUrls.isEmpty()) {
                Toast.makeText(getContext(), "Please add images and a title to proceed.", Toast.LENGTH_SHORT).show();
                return;
            }
            currentStep = 2;
        } else if (currentStep == 2) {
            // Validate Step 2: Use DNS/URL Validation logic
            validateLinksAndProceed();
            return; // Exit as validation is async
        } else {
            return;
        }
        updateStepUI();
    }

    private void handleBackStep() {
        if (currentStep > 1) {
            currentStep--;
            updateStepUI();
        }
    }

    private void updateStepUI() {
        binding.vfCreateSteps.setDisplayedChild(currentStep - 1);
        binding.tvStepIndicator.setText("Step " + currentStep + " of 3");
        binding.btnBack.setVisibility(currentStep == 1 ? View.INVISIBLE : View.VISIBLE);
        binding.btnNext.setVisibility(currentStep == 3 ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Logic to validate Website and Instagram links before moving to final step.
     */
    private void validateLinksAndProceed() {
        String webUrl = binding.etWebsite.getText().toString().trim();
        String instaUrl = binding.etInstagram.getText().toString().trim();

        if (webUrl.isEmpty() && instaUrl.isEmpty()) {
            currentStep = 3;
            updateStepUI();
            return;
        }

        binding.btnNext.setEnabled(false);
        binding.btnNext.setText("Validating...");

        // Perform Thorough Validation on Website first
        if (!webUrl.isEmpty()) {
            UrlValidationUtils.validateUrlThoroughly(webUrl, (isValid, formattedUrl, errorMessage) -> {
                if (!isValid) {
                    Toast.makeText(getContext(), "Website Error: " + errorMessage, Toast.LENGTH_SHORT).show();
                    resetNextButton();
                } else {
                    binding.etWebsite.setText(formattedUrl);
                    currentStep = 3;
                    updateStepUI();
                    resetNextButton();
                }
            });
        } else if (!instaUrl.isEmpty()) {
            // Simplified check for Instagram Profile URL
            UrlValidationUtils.validateUrlThoroughly(instaUrl, (isValid, formattedUrl, errorMessage) -> {
                if (!isValid) {
                    Toast.makeText(getContext(), "Instagram Error: " + errorMessage, Toast.LENGTH_SHORT).show();
                    resetNextButton();
                } else {
                    binding.etInstagram.setText(formattedUrl);
                    currentStep = 3;
                    updateStepUI();
                    resetNextButton();
                }
            });
        }
    }

    private void resetNextButton() {
        binding.btnNext.setEnabled(true);
        binding.btnNext.setText("Next");
    }

    /**
     * Triggers the Android native system file manager for multi-select.
     * UPDATED: Using ACTION_GET_CONTENT to force native file picker.
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        imagePickerLauncher.launch(intent);
    }

    /**
     * ENHANCED MEDIA LOGIC: 
     * Reads image -> Encrypts via AES-GCM -> Uploads via CloudflareHelper (OkHttp).
     * FIXED: Forensic logs are now accumulated and displayed in real-time.
     */
    private void handleSelectedImage(Uri uri) {
        try {
            // 1. Generate one AES key for this ad session if not already set
            if (currentAdAesKeyHex.isEmpty()) {
                byte[] key = EncryptionUtils.generateAESKey();
                currentAdAesKeyHex = EncryptionUtils.bytesToHex(key);
            }

            binding.tvImageCount.setText("Preparing Secure Tunnel...");

            // 2. Read Uri bytes
            byte[] rawBytes = getBytesFromUri(uri);
            if (rawBytes == null) return;

            // 3. Encrypt the data locally using AES-GCM
            byte[] aesKey = EncryptionUtils.hexToBytes(currentAdAesKeyHex);
            byte[] encryptedBytes = EncryptionUtils.encrypt(rawBytes, aesKey);

            // 4. LAUNCH FORENSIC CONSOLE IMMEDIATELY
            final StringBuilder sessionLogs = new StringBuilder();
            sessionLogs.append("SECURE AES-GCM ENCRYPTION COMPLETE.\n");
            sessionLogs.append("INITIATING PRIVATE CLOUDFLARE TUNNEL...\n\n");

            RelayReportDialog forensicDialog = RelayReportDialog.newInstance(
                    "PRIVATE MEDIA CONSOLE",
                    "Connecting to Advertiser Cloud...",
                    sessionLogs.toString()
            );
            forensicDialog.showSafe(getChildFragmentManager(), "MEDIA_CONSOLE");

            // 5. NEW: Upload via CloudflareHelper (Private Advertiser Infrastructure)
            CloudflareHelper uploadHelper = new CloudflareHelper();
            uploadHelper.uploadMedia(requireContext(), encryptedBytes, "ad_image.enc", new CloudflareHelper.CloudflareCallback() {

                @Override
                public void onStatusUpdate(String log) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            binding.tvImageCount.setText("Direct Uploading...");

                            // PIPE LOG TO CONSOLE
                            sessionLogs.append(log);
                            RelayReportDialog dialog = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("MEDIA_CONSOLE");
                            if (dialog != null) {
                                dialog.updateTechnicalLogs("Upload in progress...", sessionLogs.toString());
                            }
                            Log.d(TAG, "Forensic Status: " + log);
                        });
                    }
                }

                @Override
                public void onSuccess(String uploadedUrl, String fileId) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            uploadedMediaUrls.add(uploadedUrl);

                            // Store the Cloudflare File ID for single-click deletion later
                            if (fileId != null && !fileId.isEmpty()) {
                                deletionUrlsForSession.add(fileId);
                            }

                            binding.tvImageCount.setText(uploadedMediaUrls.size() + " Encrypted Items Ready");

                            sessionLogs.append("\n[FINAL SUCCESS] Private Cloud Hosted at: ").append(uploadedUrl);
                            RelayReportDialog dialog = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("MEDIA_CONSOLE");
                            if (dialog != null) {
                                dialog.updateTechnicalLogs("Upload Success", sessionLogs.toString());
                            }

                            Toast.makeText(getContext(), "Private Storage Upload Complete", Toast.LENGTH_SHORT).show();
                        });
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            binding.tvImageCount.setText("Cloudflare Upload Failed");

                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            e.printStackTrace(pw);
                            String rawStackTrace = sw.toString();

                            sessionLogs.append("\n\n!!! CRITICAL FAILURE !!!\n").append(rawStackTrace);

                            RelayReportDialog dialog = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("MEDIA_CONSOLE");
                            if (dialog != null) {
                                dialog.updateTechnicalLogs("UPLOAD FAILED", sessionLogs.toString());
                            }
                        });
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Secure media handling failed: " + e.getMessage());
        }
    }

    /**
     * Reads all bytes from a Uri InputStream.
     */
    private byte[] getBytesFromUri(Uri uri) {
        try {
            InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            return byteBuffer.toByteArray();
        } catch (Exception e) {
            return null;
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
     * ENHANCEMENT: Hybrid Hashtag Registry + Discovery Engine
     * Before counting users, we now check if the tag is locked by a competitor.
     */
    private void discoverHashtagReach() {
        String tagsInput = binding.etAdTags.getText().toString().trim();
        if (tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Enter hashtags to search", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.cvReachDiscovery.setVisibility(View.VISIBLE);
        binding.tvActiveWatchers.setText("Verifying Ownership...");

        // Ensure Lock button is initially hidden
        if (binding.btnLockTag != null) binding.btnLockTag.setVisibility(View.GONE);

        List<String> tagsToSearch = new ArrayList<>();
        for (String s : tagsInput.split(",")) {
            String clean = s.trim().toLowerCase().replace("#", "");
            if (!clean.isEmpty()) tagsToSearch.add(clean);
        }

        StringBuilder discoveryLogs = new StringBuilder();
        discoveryLogs.append("INITIATING REGISTRY & REACH DISCOVERY...\n\n");

        RelayReportDialog dialog = RelayReportDialog.newInstance(
                "DISCOVERY CONSOLE",
                "Scanning decentralized registry...",
                discoveryLogs.toString()
            );
        dialog.showSafe(getChildFragmentManager(), "DISCOVERY_CONSOLE");

        // Primary Tag for Ownership Check
        String primaryTag = tagsToSearch.get(0);
        discoveryLogs.append("CHECKING DEED OWNERSHIP FOR: #").append(primaryTag).append("\n");

        // =========================================================================
        // FEATURE 1: HYBRID HASHTAG DISCOVERY "PERMISSION" LOGIC
        // =========================================================================
        HashtagRegistryManager.checkOwnership(requireContext(), primaryTag, db.getPublicKey(), new HashtagRegistryManager.OwnershipCallback() {
            @Override
            public void onResult(int status, String ownerPubkey) {
                if (!isAdded() || getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    RelayReportDialog existing = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("DISCOVERY_CONSOLE");

                    if (status == HashtagRegistryManager.STATUS_TAKEN) {
                        // CASE 3 (OWNED BY ANOTHER) - Block Reach Calculation
                        discoveryLogs.append("\n!!! DECLINED !!!\nTag is securely locked by: ").append(ownerPubkey).append("\nDiscovery Blocked to prevent spam.\n");
                        binding.tvActiveWatchers.setText("DECLINED: Tag Owned by Another Advertiser");
                        binding.tvActiveWatchers.setTextColor(getResources().getColor(android.R.color.holo_red_light));

                        if (existing != null) existing.updateTechnicalLogs("ACCESS DENIED", discoveryLogs.toString());

                    } else if (status == HashtagRegistryManager.STATUS_MINE) {
                        // CASE 2 (OWNED BY YOU) - Allow Reach Calculation
                        discoveryLogs.append("\n[VERIFIED] You own this private tag. Proceeding with scan...\n");
                        binding.tvActiveWatchers.setText("Scanning Network...");
                        binding.tvActiveWatchers.setTextColor(getResources().getColor(R.color.hfs_active_blue));

                        if (existing != null) existing.updateTechnicalLogs("OWNER VERIFIED", discoveryLogs.toString());
                        executeReachScan(tagsToSearch, discoveryLogs);

                    } else {
                        // CASE 1 (NO OWNER / PUBLIC) - Allow Reach + Show Option B
                        discoveryLogs.append("\n[PUBLIC] Tag is unclaimed. Proceeding with scan...\n");
                        binding.tvActiveWatchers.setText("Scanning Network...");
                        binding.tvActiveWatchers.setTextColor(getResources().getColor(R.color.hfs_active_blue));

                        // Option B: Show Lock Button
                        if (binding.btnLockTag != null) {
                            binding.btnLockTag.setVisibility(View.VISIBLE);
                            binding.btnLockTag.setOnClickListener(v -> lockHashtagDeed(primaryTag));
                        }

                        if (existing != null) existing.updateTechnicalLogs("PUBLIC TAG", discoveryLogs.toString());
                        executeReachScan(tagsToSearch, discoveryLogs);
                    }
                });
            }
        });
    }

    /**
     * Executes the actual network scan for users (Moved out to separate function for cleanliness).
     */
    private void executeReachScan(List<String> tagsToSearch, StringBuilder discoveryLogs) {
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
                            if (usernames != null && !usernames.isEmpty()) {
                                discoveryLogs.append("\nIdentified Usernames: ").append(usernames.toString());
                            }
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

    /**
     * Helper to lock a tag directly from the discovery view.
     */
    private void lockHashtagDeed(String tag) {
        if (binding.btnLockTag != null) {
            binding.btnLockTag.setEnabled(false);
            binding.btnLockTag.setText("LOCKING ON BLOCKCHAIN...");
        }

        HashtagRegistryManager.broadcastDeed(requireContext(), tag, db.getPrivateKey(), db.getPublicKey(), success -> {
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (success) {
                    Toast.makeText(requireContext(), "Hashtag Locked Exclusively!", Toast.LENGTH_LONG).show();
                    if (binding.btnLockTag != null) binding.btnLockTag.setVisibility(View.GONE);
                } else {
                    Toast.makeText(requireContext(), "Failed to lock hashtag.", Toast.LENGTH_SHORT).show();
                    if (binding.btnLockTag != null) {
                        binding.btnLockTag.setEnabled(true);
                        binding.btnLockTag.setText("LOCK THIS TAG EXCLUSIVELY");
                    }
                }
            });
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

    /**
     * Intelligent Description Chunking Logic.
     * FIXED: 1 Image = 1 Full Sentence rule. Splits by proper sentence endings (. ! ?) 
     * and preserves commas and punctuation. Excess sentences are grouped into the last chunk.
     */
    private List<String> chunkDescription(String desc) {
        // Strip HTML for the slider preview chunks
        String cleanDesc = desc.replaceAll("<[^>]*>", "").trim();
        List<String> chunks = new ArrayList<>();
        
        if (cleanDesc.isEmpty()) {
            chunks.add("");
            return chunks;
        }

        // Split by Full Stop, Exclamation, or Question Mark followed by whitespace, 
        // but KEEP the punctuation mark attached to the sentence.
        String[] sentences = cleanDesc.split("(?<=[.!?])\\s+");
        
        int targetImageCount = uploadedMediaUrls.size();
        if (targetImageCount <= 0) targetImageCount = 1; // Fallback safety

        for (int i = 0; i < sentences.length; i++) {
            String currentSentence = sentences[i].trim();
            if (currentSentence.isEmpty()) continue;

            if (chunks.size() < targetImageCount - 1) {
                // Add single sentence to its own slide
                chunks.add(currentSentence);
            } else {
                // We've reached the last available image slide. Group all remaining text here.
                if (chunks.size() < targetImageCount) {
                    chunks.add(currentSentence); 
                } else {
                    int lastIndex = chunks.size() - 1;
                    String merged = chunks.get(lastIndex) + " " + currentSentence;
                    chunks.set(lastIndex, merged);
                }
            }
        }

        if (chunks.isEmpty()) chunks.add(cleanDesc); // Fallback
        return chunks;
    }

    private void prepareAndBroadcastAd(boolean isPreviewOnly) {
        String title = binding.etAdTitle.getText().toString().trim();
        // This 'desc' can now contain HTML formatting from the Rich Text Editor
        String desc = binding.etAdDescription.getText().toString().trim();
        String tagsInput = binding.etAdTags.getText().toString().trim();
        String whatsapp = binding.etWhatsapp.getText().toString().trim();
        String instagram = binding.etInstagram.getText().toString().trim();
        String website = binding.etWebsite.getText().toString().trim();

        if (title.isEmpty() || desc.isEmpty() || tagsInput.isEmpty()) {
            Toast.makeText(getContext(), "Please provide all ad details", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Build Image JSON Array
            JSONArray imageJsonArr = new JSONArray();
            for (String url : uploadedMediaUrls) imageJsonArr.put(url);

            // Build Chunked Description JSON Array (Stripped of HTML for UI Slider)
            JSONArray descJsonArr = new JSONArray();
            for (String chunk : chunkDescription(desc)) descJsonArr.put(chunk);

            // Construct Ad Payload JSON (Including logo and new social fields)
            JSONObject contentObj = new JSONObject();
            contentObj.put("title", title);

            // ENHANCEMENT: Store both the chunked preview and the FULL formatted text
            contentObj.put("desc", descJsonArr);       // Synchronized preview chunks (No HTML)
            contentObj.put("full_desc", desc);          // THE FULL RICH TEXT PAYLOAD (With HTML)

            contentObj.put("image", imageJsonArr);
            contentObj.put("logo", db.getAdvertiserLogoUrl()); // Integrated Profile Logo
            contentObj.put("key", currentAdAesKeyHex);
            contentObj.put("phone", whatsapp);
            contentObj.put("cta", whatsapp.isEmpty() ? "" : "https://wa.me/" + whatsapp.replaceAll("[^\\d]", ""));
            contentObj.put("instagram", instagram);
            contentObj.put("website", website);
            contentObj.put("maps", capturedMapsUrl);
            contentObj.put("expiry", "2026-05-01");

            String contentStr = contentObj.toString();

            // =========================================================================
            // AD PREVIEW FEATURE: Launch Popup with IS_PREVIEW flag
            // =========================================================================
            if (isPreviewOnly) {
                Intent intent = new Intent(requireContext(), AdPopupActivity.class);
                intent.putExtra("AD_PAYLOAD_JSON", contentStr);
                intent.putExtra("IS_PREVIEW", true);
                startActivity(intent);
                return;
            }

            // =========================================================================
            // MASTER APP-LEVEL JSON ENCRYPTION (FOR REAL BROADCAST)
            // =========================================================================
            String finalSecureContent;
            try {
                finalSecureContent = EncryptionUtils.encryptPayload(contentStr);
                Log.i(TAG, "Master App-Level Encryption Applied.");
            } catch (Exception e) {
                Log.e(TAG, "Master Encryption Failed! Aborting broadcast: " + e.getMessage());
                Toast.makeText(getContext(), "Encryption failed. Ad aborted.", Toast.LENGTH_SHORT).show();
                return;
            }

            JSONObject event = new JSONObject();
            event.put("kind", 30001); 
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", finalSecureContent);

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
                String eventId = signedEvent.getString("id");

                if (!deletionUrlsForSession.isEmpty()) {
                    JSONArray delArray = new JSONArray(deletionUrlsForSession);
                    db.saveDeletionData(eventId, delArray.toString());
                }

                // Local History Storage
                JSONObject localEvent = new JSONObject(signedEvent.toString());
                localEvent.put("content", contentStr); 
                JSONArray fullMsg = new JSONArray();
                fullMsg.put("EVENT");
                fullMsg.put(""); 
                fullMsg.put(localEvent);
                db.saveToAdvertiserHistory(fullMsg.toString());

                // Broadcast
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
        technicalLogs.append("[SECURE] Payload wrapped in AES-256 Master Key.\n\n");

        final int totalNodes = relayPool.size();
        final int[] finishedNodes = {0};

        NostrPublisher.publishToPool(relayPool, signedEvent, (relayUrl, success, message) -> {
            finishedNodes[0]++;

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