package com.cloudnest.app;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.cloudnest.app.databinding.ItemUploadQueueBinding;

import java.util.List;
import java.util.UUID;

/**
 * Adapter for the Upload Manager Queue.
 * Displays file names, progress bars, and status icons for background transfers.
 * Handles "Cancel" and "Retry" button clicks.
 */
public class UploadQueueAdapter extends RecyclerView.Adapter<UploadQueueAdapter.ViewHolder> {

    private final Context context;
    private List<UploadItemModel> uploadList;
    private final OnUploadActionClickListener listener;

    /**
     * Interface to communicate button clicks back to the Fragment.
     */
    public interface OnUploadActionClickListener {
        void onCancelClicked(UUID workId);
        void onRetryClicked(UUID workId);
    }

    public UploadQueueAdapter(Context context, List<UploadItemModel> uploadList, OnUploadActionClickListener listener) {
        this.context = context;
        this.uploadList = uploadList;
        this.listener = listener;
    }

    public void updateList(List<UploadItemModel> newList) {
        this.uploadList = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemUploadQueueBinding binding = ItemUploadQueueBinding.inflate(LayoutInflater.from(context), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UploadItemModel item = uploadList.get(position);
        holder.bind(item, listener);
    }

    @Override
    public int getItemCount() {
        return uploadList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemUploadQueueBinding binding;

        ViewHolder(ItemUploadQueueBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(UploadItemModel item, OnUploadActionClickListener listener) {
            binding.tvUploadFileName.setText(item.getFileName());
            binding.progressBarUpload.setProgress(item.getProgress());

            // Reset visual state
            binding.btnCancelUpload.setVisibility(View.GONE);
            binding.btnRetryUpload.setVisibility(View.GONE);
            binding.progressBarUpload.setVisibility(View.VISIBLE);

            switch (item.getStatus()) {
                case IN_PROGRESS:
                    binding.tvUploadStatus.setText("Uploading... " + item.getProgress() + "%");
                    binding.tvUploadStatus.setTextColor(Color.DKGRAY);
                    binding.btnCancelUpload.setVisibility(View.VISIBLE);
                    binding.progressBarUpload.setIndeterminate(false);
                    break;

                case PENDING:
                    binding.tvUploadStatus.setText("Pending...");
                    binding.tvUploadStatus.setTextColor(Color.GRAY);
                    binding.btnCancelUpload.setVisibility(View.VISIBLE);
                    binding.progressBarUpload.setIndeterminate(true);
                    break;

                case COMPLETED:
                    binding.tvUploadStatus.setText("Upload Complete");
                    binding.tvUploadStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.cloudnest_status_green));
                    binding.progressBarUpload.setProgress(100);
                    break;

                case FAILED:
                    binding.tvUploadStatus.setText("Upload Failed");
                    binding.tvUploadStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.cloudnest_status_red));
                    binding.btnRetryUpload.setVisibility(View.VISIBLE);
                    binding.progressBarUpload.setProgress(0);
                    break;

                case CANCELLED:
                    binding.tvUploadStatus.setText("Cancelled");
                    binding.tvUploadStatus.setTextColor(Color.GRAY);
                    binding.progressBarUpload.setVisibility(View.INVISIBLE);
                    break;
            }

            // Click Listeners
            binding.btnCancelUpload.setOnClickListener(v -> listener.onCancelClicked(item.getWorkId()));
            binding.btnRetryUpload.setOnClickListener(v -> listener.onRetryClicked(item.getWorkId()));
        }
    }
}