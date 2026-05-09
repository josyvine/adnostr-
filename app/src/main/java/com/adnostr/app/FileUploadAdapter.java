package com.adnostr.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.adnostr.app.databinding.ItemUploadFileRowBinding;
import java.util.ArrayList;
import java.util.List;

/**
 * NEW: BATCH FILE STATUS ADAPTER
 * Role: Renders the list of JSON files selected for Cloudflare R2 Archiving.
 * Logic: Tracks individual file states (Pending, Success, Failed) during the 
 * Multipart streaming process in AdminDbUploaderActivity.
 */
public class FileUploadAdapter extends RecyclerView.Adapter<FileUploadAdapter.FileViewHolder> {

    // Status Constants for the Architect UI
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_UPLOADING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    private final List<UploadItem> uploadItems = new ArrayList<>();

    /**
     * Constructor accepts raw Uris from the File Picker and wraps them 
     * in the UploadItem state-manager.
     */
    public FileUploadAdapter(List<Uri> uris) {
        setItems(uris);
    }

    /**
     * Resets the list and initializes all files to 'Pending' status.
     */
    public void setItems(List<Uri> uris) {
        uploadItems.clear();
        for (Uri uri : uris) {
            uploadItems.add(new UploadItem(uri, STATUS_PENDING));
        }
        notifyDataSetChanged();
    }

    /**
     * Updates the status of a specific file in the list.
     * Called by AdminDbUploaderActivity during the Multipart loop.
     */
    public void updateStatus(Uri uri, int status) {
        for (int i = 0; i < uploadItems.size(); i++) {
            if (uploadItems.get(i).uri.equals(uri)) {
                uploadItems.get(i).status = status;
                notifyItemChanged(i);
                break;
            }
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemUploadFileRowBinding binding = ItemUploadFileRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new FileViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        holder.bind(uploadItems.get(position));
    }

    @Override
    public int getItemCount() {
        return uploadItems.size();
    }

    /**
     * ViewHolder logic for the individual JSON file row.
     */
    static class FileViewHolder extends RecyclerView.ViewHolder {
        private final ItemUploadFileRowBinding binding;

        public FileViewHolder(ItemUploadFileRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(UploadItem item) {
            Context context = itemView.getContext();
            
            // 1. Resolve and Display the clean filename (e.g., B0GTRM9NQ7_RAW.json)
            String fileName = getFileName(context, item.uri);
            binding.tvFileName.setText(fileName);

            // 2. Visual State Machine: Map status to Icons and Colors
            switch (item.status) {
                case STATUS_PENDING:
                    binding.ivStatusIcon.setImageResource(android.R.drawable.presence_invisible);
                    binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.hfs_text_grey));
                    binding.tvFileStatus.setText(R.string.status_pending);
                    binding.tvFileStatus.setTextColor(ContextCompat.getColor(context, R.color.hfs_text_grey));
                    binding.pbFileProgress.setVisibility(View.GONE);
                    break;

                case STATUS_UPLOADING:
                    binding.ivStatusIcon.setImageResource(android.R.drawable.stat_sys_upload);
                    binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.hfs_active_blue));
                    binding.tvFileStatus.setText(R.string.status_uploading);
                    binding.tvFileStatus.setTextColor(ContextCompat.getColor(context, R.color.hfs_active_blue));
                    binding.pbFileProgress.setVisibility(View.VISIBLE);
                    break;

                case STATUS_SUCCESS:
                    binding.ivStatusIcon.setImageResource(R.drawable.ic_seed_tick); // Reusing the seed tick
                    binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.hfs_active_green));
                    binding.tvFileStatus.setText(R.string.status_success);
                    binding.tvFileStatus.setTextColor(ContextCompat.getColor(context, R.color.hfs_active_green));
                    binding.pbFileProgress.setVisibility(View.GONE);
                    break;

                case STATUS_FAILED:
                    binding.ivStatusIcon.setImageResource(android.R.drawable.stat_notify_error);
                    binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.hfs_error_red));
                    binding.tvFileStatus.setText(R.string.status_failed);
                    binding.tvFileStatus.setTextColor(ContextCompat.getColor(context, R.color.hfs_error_red));
                    binding.pbFileProgress.setVisibility(View.GONE);
                    break;
            }
        }

        /**
         * Helper to extract the Display Name from a Content Uri.
         */
        private String getFileName(Context context, Uri uri) {
            String result = null;
            if (uri.getScheme().equals("content")) {
                try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (index != -1) {
                            result = cursor.getString(index);
                        }
                    }
                }
            }
            if (result == null) {
                result = uri.getPath();
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
            return result;
        }
    }

    /**
     * Internal data wrapper to maintain state during the upload lifecycle.
     */
    private static class UploadItem {
        Uri uri;
        int status;

        UploadItem(Uri uri, int status) {
            this.uri = uri;
            this.status = status;
        }
    }
}