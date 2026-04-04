package com.adnostr.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.adnostr.app.databinding.DialogRelayReportBinding;

/**
 * Technical Network Console Popup.
 * Provides detailed visibility into Nostr Relay events, broadcast successes, 
 * and background monitoring logs to identify why ads or searches may be failing.
 * FIXED: Resolved IllegalStateException crash and added live log update support.
 */
public class RelayReportDialog extends DialogFragment {

    private DialogRelayReportBinding binding;
    private String title;
    private String summary;
    private String logs;

    /**
     * Factory method to create a new instance of the technical console.
     */
    public static RelayReportDialog newInstance(String title, String summary, String detailedLogs) {
        RelayReportDialog frag = new RelayReportDialog();
        Bundle args = new Bundle();
        args.putString("TITLE", title);
        args.putString("SUMMARY", summary);
        args.putString("LOGS", detailedLogs);
        frag.setArguments(args);
        return frag;
    }

    /**
     * NEW: Safe execution of the popup to prevent the "onSaveInstanceState" crash.
     */
    public void showSafe(FragmentManager manager, String tag) {
        if (manager == null || manager.isDestroyed() || manager.isStateSaved()) return;
        
        FragmentTransaction ft = manager.beginTransaction();
        ft.add(this, tag);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            title = getArguments().getString("TITLE");
            summary = getArguments().getString("SUMMARY");
            logs = getArguments().getString("LOGS");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogRelayReportBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set technical headers
        binding.tvReportHeader.setText(title != null ? title : "NETWORK CONSOLE");
        binding.tvNetworkSummary.setText(summary != null ? summary : "Initializing report...");

        // Populate the log area with raw relay data or JSON
        if (logs != null && !logs.isEmpty()) {
            binding.tvConsoleLog.setText(logs);
        } else {
            binding.tvConsoleLog.setText("No relay events recorded yet.");
        }

        // Setup Close Action
        binding.btnCloseReport.setOnClickListener(v -> dismiss());
    }

    /**
     * NEW: Public method to update logs while the dialog is visible.
     * Use this to show incoming JSON data in real-time.
     */
    public void updateTechnicalLogs(String newSummary, String newLogs) {
        if (binding != null) {
            binding.tvNetworkSummary.setText(newSummary);
            binding.tvConsoleLog.setText(newLogs);
        }
    }

    /**
     * Styles the dialog to be full-screen with a dark transparent background.
     */
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}