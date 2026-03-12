package com.cloudnest.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter for the Visual Ledger.
 * Displays Preset Folders as expandable list items.
 * Clicking a parent row expands it to show detailed sequence and size metrics.
 * UPDATED: Corrected field access to match FileTrackDao.LedgerReportModel public members.
 */
public class LedgerExpandableAdapter extends RecyclerView.Adapter<LedgerExpandableAdapter.ViewHolder> {

    private final Context context;
    private List<FileTrackDao.LedgerReportModel> ledgerList;
    private int expandedPosition = -1; // -1 means nothing is expanded

    public LedgerExpandableAdapter(Context context, List<FileTrackDao.LedgerReportModel> ledgerList) {
        this.context = context;
        this.ledgerList = ledgerList;
    }

    public void updateList(List<FileTrackDao.LedgerReportModel> newList) {
        this.ledgerList = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Inflate the parent view
        View view = LayoutInflater.from(context).inflate(R.layout.item_ledger_parent, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileTrackDao.LedgerReportModel item = ledgerList.get(position);

        // Bind data using direct public field access as defined in FileTrackDao
        holder.tvFolderName.setText(item.folder_name);
        holder.tvSeqRange.setText("Files " + item.start_sequence + " to " + item.end_sequence);
        holder.tvFileCount.setText(item.total_files + " files");
        holder.tvTotalSize.setText(LocalFileHelper.getReadableSize(item.total_size));

        // Toggle visibility based on expansion
        boolean isExpanded = position == expandedPosition;
        holder.childLayout.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

        // Click listener to handle expansion
        holder.itemView.setOnClickListener(v -> {
            int previousExpandedPosition = expandedPosition;
            expandedPosition = isExpanded ? -1 : holder.getAdapterPosition();
            notifyItemChanged(previousExpandedPosition);
            notifyItemChanged(expandedPosition);
        });
    }

    @Override
    public int getItemCount() {
        return ledgerList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFolderName;
        View childLayout;
        TextView tvSeqRange;
        TextView tvFileCount;
        TextView tvTotalSize;

        ViewHolder(View v) {
            super(v);
            tvFolderName = v.findViewById(R.id.tv_ledger_folder_name);
            childLayout = v.findViewById(R.id.layout_ledger_child);
            tvSeqRange = v.findViewById(R.id.tv_ledger_seq_range);
            tvFileCount = v.findViewById(R.id.tv_ledger_file_count);
            tvTotalSize = v.findViewById(R.id.tv_ledger_total_size);
        }
    }
}