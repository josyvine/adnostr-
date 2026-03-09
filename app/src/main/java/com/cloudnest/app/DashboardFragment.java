package com.cloudnest.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.cloudnest.app.databinding.FragmentDashboardBinding;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dashboard Screen for CloudNest.
 * Displays three storage cards (Drive, Phone, SD) showing:
 * Total Storage, Used Storage, and Free Storage.
 * Colors change based on usage (Green/Yellow/Red).
 * Supports Pull-to-Refresh.
 * UPDATED: Fixed Life-cycle crashes in background threads.
 */
public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;
    private ExecutorService networkExecutor;

    private static final long GIGABYTE = 1073741824L;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        networkExecutor = Executors.newSingleThreadExecutor();

        setupSwipeRefresh();
        setupCardClicks();

        // Initial Data Load
        refreshDashboardData();
    }

    /**
     * Sets up the Pull-to-Refresh logic.
     */
    private void setupSwipeRefresh() {
        if (binding != null) {
            binding.swipeRefreshLayout.setOnRefreshListener(() -> {
                refreshDashboardData();
            });
        }
    }

    /**
     * Sets up click listeners for the three storage cards.
     * Tapping a card opens its respective File Browser.
     */
    private void setupCardClicks() {
        if (binding != null) {
            binding.cardPhoneStorage.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putString("STORAGE_TYPE", "PHONE");
                Navigation.findNavController(v).navigate(R.id.action_dashboard_to_localBrowser, bundle);
            });

            binding.cardSdStorage.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putString("STORAGE_TYPE", "SD_CARD");
                Navigation.findNavController(v).navigate(R.id.action_dashboard_to_localBrowser, bundle);
            });

            binding.cardDriveStorage.setOnClickListener(v -> {
                Navigation.findNavController(v).navigate(R.id.action_dashboard_to_driveBrowser);
            });
        }
    }

    /**
     * Orchestrates the fetching of storage data for all three cards.
     */
    private void refreshDashboardData() {
        if (binding != null) {
            binding.swipeRefreshLayout.setRefreshing(true);
            updatePhoneStorageCard();
            updateSdCardStorageCard();
            fetchGoogleDriveQuota();
        }
    }

    /**
     * Calculates internal phone storage and updates UI.
     */
    private void updatePhoneStorageCard() {
        if (binding == null) return;
        
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());

        long blockSize = stat.getBlockSizeLong();
        long totalBlocks = stat.getBlockCountLong();
        long availableBlocks = stat.getAvailableBlocksLong();

        long totalStorage = totalBlocks * blockSize;
        long freeStorage = availableBlocks * blockSize;
        long usedStorage = totalStorage - freeStorage;

        double usagePercentage = ((double) usedStorage / totalStorage) * 100;

        String formattedTotal = String.format("%.1f GB", (double) totalStorage / GIGABYTE);
        String formattedFree = String.format("%.1f GB free / %s", (double) freeStorage / GIGABYTE, formattedTotal);

        binding.tvPhoneStorageText.setText(formattedFree);
        applyColorRule(binding.viewPhoneStorageIndicator, usagePercentage);
    }

    /**
     * Attempts to calculate SD Card storage (if inserted) and updates UI.
     */
    private void updateSdCardStorageCard() {
        if (binding == null || !isAdded()) return;
        
        File[] externalStorageFiles = ContextCompat.getExternalFilesDirs(requireContext(), null);
        File sdCardFile = null;

        // The second element in the array is usually the removable SD Card on modern Android
        if (externalStorageFiles.length > 1 && externalStorageFiles[1] != null) {
            sdCardFile = externalStorageFiles[1];
        }

        if (sdCardFile != null && Environment.isExternalStorageRemovable(sdCardFile)) {
            StatFs stat = new StatFs(sdCardFile.getPath());

            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalStorage = totalBlocks * blockSize;
            long freeStorage = availableBlocks * blockSize;
            long usedStorage = totalStorage - freeStorage;

            double usagePercentage = ((double) usedStorage / totalStorage) * 100;

            String formattedTotal = String.format("%.1f GB", (double) totalStorage / GIGABYTE);
            String formattedFree = String.format("%.1f GB free / %s", (double) freeStorage / GIGABYTE, formattedTotal);

            binding.tvSdStorageText.setText(formattedFree);
            applyColorRule(binding.viewSdStorageIndicator, usagePercentage);
            binding.cardSdStorage.setEnabled(true);
            binding.cardSdStorage.setAlpha(1.0f);
        } else {
            binding.tvSdStorageText.setText("No SD Card Inserted");
            binding.viewSdStorageIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.cloudnest_gray));
            binding.cardSdStorage.setEnabled(false);
            binding.cardSdStorage.setAlpha(0.6f);
        }
    }

    /**
     * Fetches Google Drive Quota asynchronously via API v3.
     */
    private void fetchGoogleDriveQuota() {
        networkExecutor.execute(() -> {
            try {
                if (!isAdded()) return;
                
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
                if (account == null) throw new Exception("Google Account Disconnected");

                GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                        requireContext(), Collections.singleton(DriveScopes.DRIVE_READONLY));
                credential.setSelectedAccount(account.getAccount());

                Drive driveService = new Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        new GsonFactory(),
                        credential)
                        .setApplicationName(getString(R.string.app_name))
                        .build();

                About about = driveService.about().get().setFields("storageQuota").execute();
                About.StorageQuota quota = about.getStorageQuota();

                long totalStorage = quota.getLimit();
                long usedStorage = quota.getUsage();
                long freeStorage = totalStorage - usedStorage;

                double usagePercentage = ((double) usedStorage / totalStorage) * 100;

                String formattedTotal = String.format("%.1f GB", (double) totalStorage / GIGABYTE);
                String formattedFree = String.format("%.1f GB free / %s", (double) freeStorage / GIGABYTE, formattedTotal);

                // FIXED: Wrapped in binding null-check to prevent Life-cycle Crash
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.tvDriveStorageText.setText(formattedFree);
                            applyColorRule(binding.viewDriveStorageIndicator, usagePercentage);
                            binding.swipeRefreshLayout.setRefreshing(false);
                        }
                    });
                }

            } catch (Exception e) {
                Log.e("DashboardFragment", "Cloud Quota Sync Error: " + e.getMessage());
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.tvDriveStorageText.setText("Failed to sync Drive storage");
                            binding.viewDriveStorageIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.cloudnest_gray));
                            binding.swipeRefreshLayout.setRefreshing(false);
                        }
                    });
                }
            }
        });
    }

    /**
     * Applies the strict color rule defined in the instructions.
     * Green = enough/safe (< 70%)
     * Yellow = medium (70% - 90%)
     * Red = low/almost full (> 90%)
     */
    private void applyColorRule(View indicatorView, double usagePercentage) {
        if (!isAdded() || indicatorView == null) return;
        
        Context context = requireContext();
        if (usagePercentage >= 90.0) {
            indicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.cloudnest_status_red));
        } else if (usagePercentage >= 70.0) {
            indicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.cloudnest_status_yellow));
        } else {
            indicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.cloudnest_status_green));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            networkExecutor.shutdown();
        }
        binding = null;
    }
}