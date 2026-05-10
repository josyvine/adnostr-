package com.adnostr.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityAdminDbUploaderBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * NEW: CLOUDFLARE DATABASE ARCHITECT (ADMIN ONLY)
 * Role: Handles Batch JSON Uploads and 5-Layer Hierarchy Seeding.
 * Logic:
 * 1. Context Setup: Fetch/Type Main Cat, Sub Cat, and Brand.
 * 2. Tick Seeding: Clicking Green Tick icon broadcasts a Single-Point seed to Worker.
 * 3. Batch Engine: Selects 100+ files, validates ASIN_RAW structure, signs via BIP-340.
 * 4. Forensic Console: Displays real-time protocol frames and Worker responses.
 * 
 * GLITCH FIXES:
 * - Implemented Auto-Tier fetching: Selecting a parent dropdown now populates the child dropdown.
 * - Relaxed Hierarchy Validation: Trimmed strings and unified slug logic to prevent false "Mismatch" aborts.
 */
public class AdminDbUploaderActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Architect";
    private ActivityAdminDbUploaderBinding binding;
    private AdNostrDatabaseHelper db;
    private CloudflareHelper cloudHelper;

    private List<String> mainCategories = new ArrayList<>();
    private List<String> subCategories = new ArrayList<>();
    private List<String> brands = new ArrayList<>();

    private List<Uri> selectedFileUris = new ArrayList<>();
    private FileUploadAdapter fileAdapter;

    private final StringBuilder forensicLogs = new StringBuilder();

    // Launcher for Multi-File Picker (JSON Batch)
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleFileSelection(result.getData());
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDbUploaderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        cloudHelper = new CloudflareHelper();

        // SECURITY GATE: Double check Admin Authority
        if (!db.isAdmin()) {
            Toast.makeText(this, "UNAUTHORIZED ACCESS DENIED", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupUI();
        loadInitialCategories();
        logForensic("ARCHITECT: Engine Initialized. Ready for seeding...");
    }

    private void setupUI() {
        setSupportActionBar(binding.toolbarArchitect);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Cloud Database Architect");
        }
        binding.toolbarArchitect.setNavigationOnClickListener(v -> finish());

        // Setup File List
        binding.rvBatchFiles.setLayoutManager(new LinearLayoutManager(this));
        fileAdapter = new FileUploadAdapter(selectedFileUris);
        binding.rvBatchFiles.setAdapter(fileAdapter);

        // --- GLITCH FIX: AUTO-FETCH TIERED DROP-DOWNS ---
        binding.etMainCat.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            loadSubCategories(selected);
        });

        binding.etSubCat.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            loadBrands(binding.etMainCat.getText().toString(), selected);
        });

        // --- EDITABLE SEEDING LOGIC (THE TICK ENGINE) ---
        setupSeedingWatcher(binding.etMainCat, "tier1");
        setupSeedingWatcher(binding.etSubCat, "tier2");
        setupSeedingWatcher(binding.etBrand, "tier3");

        binding.btnSelectFiles.setOnClickListener(v -> openFilePicker());
        binding.btnStartBatchUpload.setOnClickListener(v -> startBatchProcess());
    }

    /**
     * Monitors dropdown typing. If the value is NEW, show the Green Tick.
     */
    private void setupSeedingWatcher(android.widget.AutoCompleteTextView view, String tier) {
        view.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String input = s.toString().trim();
                boolean exists = false;

                if (tier.equals("tier1")) exists = mainCategories.contains(input);
                else if (tier.equals("tier2")) exists = subCategories.contains(input);
                else if (tier.equals("tier3")) exists = brands.contains(input);

                // If typing a brand-new name, show the Seed Tick
                if (!input.isEmpty() && !exists) {
                    view.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_seed_tick, 0);
                    view.setOnTouchListener((v, event) -> {
                        final int DRAWABLE_RIGHT = 2;
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            if (event.getRawX() >= (view.getRight() - view.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width())) {
                                performSeedBroadcast(tier, input);
                                return true;
                            }
                        }
                        return false;
                    });
                } else {
                    view.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    view.setOnTouchListener(null);
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * SEEDING ACTION: Instructions Step 4.
     * Instructs Worker to create the Layer index anchor.
     */
    private void performSeedBroadcast(String tier, String name) {
        logForensic("CRYPTO: Generating BIP-340 Seed Signature for " + name);

        String path = generatePathForTier(tier, name);
        String signature = generateAdminSignature(name + "|" + path);

        cloudHelper.broadcastHierarchyAnchor(this, tier, name, path, signature, new CloudflareHelper.CloudflareCallback() {
            @Override public void onStatusUpdate(String log) { logForensic(log); }
            @Override public void onSuccess(String response, String extra) {
                runOnUiThread(() -> {
                    logForensic("WORKER: Anchor created successfully in R2.");
                    if (tier.equals("tier1")) mainCategories.add(name);
                    else if (tier.equals("tier2")) subCategories.add(name);
                    else if (tier.equals("tier3")) brands.add(name);
                    Toast.makeText(AdminDbUploaderActivity.this, "Seed Success", Toast.LENGTH_SHORT).show();
                    
                    // Force refresh icon state
                    binding.etMainCat.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    binding.etSubCat.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    binding.etBrand.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                });
            }
            @Override public void onFailure(Exception e) {
                runOnUiThread(() -> logForensic("ERROR: Seeding failed - " + e.getMessage()));
            }
        });
    }

    /**
     * FIXED: Relaxed MIME filtering to prevent greyed-out JSON files.
     * Uses Multi-MIME support for application/json and text/plain.
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*"); 
        String[] mimeTypes = {"application/json", "text/plain", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    private void handleFileSelection(Intent data) {
        selectedFileUris.clear();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                selectedFileUris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            selectedFileUris.add(data.getData());
        }
        fileAdapter.setItems(selectedFileUris); // Using specialized setter
        logForensic("BATCH: Selected " + selectedFileUris.size() + " JSON files.");
    }

    /**
     * BATCH UPLOAD LOGIC: Instructions Step 5.
     * Validates ASIN_RAW, strips _raw, and streams to R2.
     */
    private void startBatchProcess() {
        if (selectedFileUris.isEmpty()) {
            Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show();
            return;
        }

        logForensic("VALIDATING: Checking ASIN_RAW hierarchy consistency...");

        // Internal Validation: First file check
        if (!validateHierarchy(selectedFileUris.get(0))) {
            logForensic("CRITICAL: Selected files do not match chosen Category/Brand. Aborting.");
            return;
        }

        String targetPath = "v1/" + slugify(binding.etMainCat.getText().toString()) + "/" 
                           + slugify(binding.etSubCat.getText().toString()) + "/" 
                           + slugify(binding.etBrand.getText().toString()) + "/";

        String sigPayload = "batch|" + selectedFileUris.size() + "|" + targetPath;
        String signature = generateAdminSignature(sigPayload);

        binding.pbUpload.setVisibility(View.VISIBLE);
        binding.pbUpload.setMax(selectedFileUris.size());

        cloudHelper.uploadBatchToR2(this, selectedFileUris, targetPath, signature, new CloudflareHelper.CloudflareCallback() {
            @Override public void onStatusUpdate(String log) { logForensic(log); }
            @Override public void onSuccess(String response, String extra) {
                runOnUiThread(() -> {
                    binding.pbUpload.setVisibility(View.GONE);
                    logForensic("FINAL: Batch complete. Layer 4 Query Index hydrated.");
                    Toast.makeText(AdminDbUploaderActivity.this, "Database Architected Successfully", Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    binding.pbUpload.setVisibility(View.GONE);
                    logForensic("FAILED: Worker rejected batch - " + e.getMessage());
                });
            }
        });
    }

    private boolean validateHierarchy(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject json = new JSONObject(sb.toString());
            JSONObject hier = json.optJSONObject("hierarchy");
            if (hier == null) return false;

            // GLITCH FIX: Use trimmed and normalized slug logic for validation
            String m = slugify(hier.optString("main_category"));
            String s = slugify(hier.optString("sub_category"));
            
            String selectedM = slugify(binding.etMainCat.getText().toString());
            String selectedS = slugify(binding.etSubCat.getText().toString());

            return m.equals(selectedM) && s.equals(selectedS);

        } catch (Exception e) { return false; }
    }

    private void loadInitialCategories() {
        cloudHelper.fetchSchemaLayer(this, "v1/categories.json", new CloudflareHelper.CloudflareCallback() {
            @Override public void onStatusUpdate(String log) {}
            @Override public void onSuccess(String response, String extra) {
                try {
                    mainCategories.clear();
                    JSONArray arr = new JSONArray(response);
                    for (int i = 0; i < arr.length(); i++) mainCategories.add(arr.getJSONObject(i).optString("main"));
                    runOnUiThread(() -> {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(AdminDbUploaderActivity.this, 
                                android.R.layout.simple_dropdown_item_1line, mainCategories);
                        binding.etMainCat.setAdapter(adapter);
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onFailure(Exception e) { logForensic("FETCH: Root index empty. Ready for Step 1 Seeding."); }
        });
    }

    /**
     * GLITCH FIX: Populate Sub-Category based on Main selection
     */
    private void loadSubCategories(String mainCat) {
        String path = "v1/" + slugify(mainCat) + ".json";
        cloudHelper.fetchSchemaLayer(this, path, new CloudflareHelper.CloudflareCallback() {
            @Override public void onStatusUpdate(String log) {}
            @Override public void onSuccess(String response, String extra) {
                try {
                    subCategories.clear();
                    JSONArray arr = new JSONArray(response);
                    for (int i = 0; i < arr.length(); i++) subCategories.add(arr.getJSONObject(i).optString("sub"));
                    runOnUiThread(() -> {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(AdminDbUploaderActivity.this, 
                                android.R.layout.simple_dropdown_item_1line, subCategories);
                        binding.etSubCat.setAdapter(adapter);
                        binding.etSubCat.showDropDown();
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onFailure(Exception e) { runOnUiThread(() -> subCategories.clear()); }
        });
    }

    /**
     * GLITCH FIX: Populate Brands based on Sub selection
     */
    private void loadBrands(String mainCat, String subCat) {
        String path = "v1/" + slugify(mainCat) + "/" + slugify(subCat) + ".json";
        cloudHelper.fetchSchemaLayer(this, path, new CloudflareHelper.CloudflareCallback() {
            @Override public void onStatusUpdate(String log) {}
            @Override public void onSuccess(String response, String extra) {
                try {
                    brands.clear();
                    JSONArray arr = new JSONArray(response);
                    for (int i = 0; i < arr.length(); i++) brands.add(arr.getString(i));
                    runOnUiThread(() -> {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(AdminDbUploaderActivity.this, 
                                android.R.layout.simple_dropdown_item_1line, brands);
                        binding.etBrand.setAdapter(adapter);
                        binding.etBrand.showDropDown();
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onFailure(Exception e) { runOnUiThread(() -> brands.clear()); }
        });
    }

    private String generateAdminSignature(String payload) {
        try {
            JSONObject event = new JSONObject();
            event.put("content", payload);
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("pubkey", db.getPublicKey());
            event.put("kind", 10000); 
            event.put("tags", new JSONArray());

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            return signed.getString("sig");
        } catch (Exception e) { return ""; }
    }

    private void logForensic(String msg) {
        runOnUiThread(() -> {
            forensicLogs.append("[").append(System.currentTimeMillis()).append("] ").append(msg).append("\n");
            binding.tvForensicTerminal.setText(forensicLogs.toString());
            binding.svTerminal.post(() -> binding.svTerminal.fullScroll(View.FOCUS_DOWN));
        });
    }

    private String generatePathForTier(String tier, String name) {
        if (tier.equals("tier1")) return "v1/categories.json";
        if (tier.equals("tier2")) return "v1/" + slugify(binding.etMainCat.getText().toString()) + ".json";
        return "v1/" + slugify(binding.etMainCat.getText().toString()) + "/" + slugify(binding.etSubCat.getText().toString()) + ".json";
    }

    private String slugify(String text) {
        return text.toString().toLowerCase()
                .trim()
                .replace(" & ", "-")
                .replace("&", "-")
                .replace(" ", "-")
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-+", "-");
    }
}