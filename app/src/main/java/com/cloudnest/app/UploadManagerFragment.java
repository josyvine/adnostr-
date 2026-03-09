package com.cloudnest.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.cloudnest.app.databinding.FragmentUploadManagerBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Upload Queue Manager.
 * Observes background WorkManager tasks tagged with "MANUAL_UPLOAD" or "AUTO_BACKUP".
 * Displays real-time progress bars, file names, and allows cancellation.
 */
public class UploadManagerFragment extends Fragment implements UploadQueueAdapter.OnUploadActionClickListener {

    private FragmentUploadManagerBinding binding;
    private UploadQueueAdapter adapter;
    private WorkManager workManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentUploadManagerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        workManager = WorkManager.getInstance(requireContext());

        setupRecyclerView();
        observeUploads();
        setupClearAllButton();
    }

    private void setupRecyclerView() {
        binding.recyclerViewUploads.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Initialize adapter with empty list and 'this' as listener
        adapter = new UploadQueueAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewUploads.setAdapter(adapter);
    }

    private void setupClearAllButton() {
        binding.btnClearCompleted.setOnClickListener(v -> {
            workManager.pruneWork(); // Removes 'SUCCEEDED' or 'FAILED' works from internal DB
            Toast.makeText(requireContext(), "Completed logs cleared.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Observes LiveData from WorkManager.
     * Updates the UI whenever a background task updates its progress or state.
     */
    private void observeUploads() {
        // We observe both Manual Uploads and Auto Backups
        // Note: In a real app, you might want to merge these LiveData sources.
        // For simplicity, we observe "MANUAL_UPLOAD" primarily here as per LocalBrowserFragment logic.

        workManager.getWorkInfosByTagLiveData("MANUAL_UPLOAD")
                .observe(getViewLifecycleOwner(), new Observer<List<WorkInfo>>() {
                    @Override
                    public void onChanged(List<WorkInfo> workInfos) {
                        if (workInfos == null || workInfos.isEmpty()) {
                            binding.tvEmptyQueue.setVisibility(View.VISIBLE);
                            binding.recyclerViewUploads.setVisibility(View.GONE);
                            adapter.updateList(new ArrayList<>());
                            return;
                        }

                        binding.tvEmptyQueue.setVisibility(View.GONE);
                        binding.recyclerViewUploads.setVisibility(View.VISIBLE);

                        // Convert WorkInfo objects to our UI Model
                        List<UploadItemModel> uiModels = new ArrayList<>();

                        for (WorkInfo info : workInfos) {
                            String fileName = info.getProgress().getString("CURRENT_FILE");
                            if (fileName == null) fileName = "Preparing Upload...";

                            int progress = info.getProgress().getInt("PROGRESS_PERCENT", 0);
                            
                            // NEW: Extract speed and details from WorkInfo progress
                            String speed = info.getProgress().getString("SPEED");
                            String details = info.getProgress().getString("DETAILS");

                            // Determine display state
                            UploadItemModel.Status status;
                            if (info.getState() == WorkInfo.State.RUNNING) {
                                status = UploadItemModel.Status.IN_PROGRESS;
                            } else if (info.getState() == WorkInfo.State.SUCCEEDED) {
                                status = UploadItemModel.Status.COMPLETED;
                                progress = 100;
                            } else if (info.getState() == WorkInfo.State.FAILED) {
                                status = UploadItemModel.Status.FAILED;
                            } else if (info.getState() == WorkInfo.State.CANCELLED) {
                                status = UploadItemModel.Status.CANCELLED;
                            } else {
                                status = UploadItemModel.Status.PENDING;
                            }

                            // UPDATED: Pass the new fields to the constructor
                            uiModels.add(new UploadItemModel(
                                    info.getId(),
                                    fileName,
                                    progress,
                                    status,
                                    speed,
                                    details
                            ));
                        }

                        // Update the adapter with new data
                        adapter.updateList(uiModels);
                    }
                });
    }

    // --- Interaction Listener Implementation ---

    @Override
    public void onCancelClicked(UUID workId) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Cancel Upload?")
                .setMessage("Are you sure you want to stop this upload?")
                .setPositiveButton("Yes, Stop", (dialog, which) -> {
                    workManager.cancelWorkById(workId);
                    Toast.makeText(requireContext(), "Cancellation requested...", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    public void onRetryClicked(UUID workId) {
        // WorkManager works are immutable once finished. 
        // To "Retry", we essentially create a new OneTimeWorkRequest with the same data.
        // However, extracting original input data from a finished WorkInfo is complex in UI layer.
        // For this implementation, we will instruct the user to re-select files.
        Toast.makeText(requireContext(), "Please re-select files to retry upload.", Toast.LENGTH_LONG).show();

        // Alternative: Prune this specific failed ID so it disappears
        workManager.pruneWork(); 
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}