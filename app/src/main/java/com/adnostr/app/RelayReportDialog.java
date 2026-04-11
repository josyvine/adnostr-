package com.adnostr.app;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

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
 * UPDATED: Integrated support for detailed HTTP/Encryption error logging 
 * for the Blossom/NIP-96 media enhancement.
 * FIXED: Added Auto-Scroll logic to ensure long forensic dumps are visible at the bottom.
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

        // Populate the log area with raw relay data, encryption steps, or HTTP responses
        if (logs != null && !logs.isEmpty()) {
            binding.tvConsoleLog.setText(logs);
        } else {
            binding.tvConsoleLog.setText("No network or encryption events recorded yet.");
        }

        // Setup Copy to Clipboard Action
        binding.btnCopyLogs.setOnClickListener(v -> {
            String textToCopy = binding.tvConsoleLog.getText().toString();
            if (!textToCopy.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("AdNostr Logs", textToCopy);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Setup Close Action
        binding.btnCloseReport.setOnClickListener(v -> dismiss());
    }

    /**
     * FIX: This method forces the dialog to fill the entire screen.
     * It must be in onStart() because onCreateDialog() is too early for window sizing.
     */
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
        }
    }

    /**
     * Public method to update logs while the dialog is visible.
     * Used to push real-time Blossom upload status and AES encryption diagnostics.
     * FIXED: Now automatically scrolls to the bottom so newest forensic data is visible.
     */
    public void updateTechnicalLogs(String newSummary, String newLogs) {
        if (binding != null) {
            binding.tvNetworkSummary.setText(newSummary);
            binding.tvConsoleLog.setText(newLogs);

            // AUTO-SCROLL FIX: Ensure we always see the latest Forensic Rejection Reason
            binding.getRoot().post(() -> {
                if (binding != null && binding.tvConsoleLog.getParent() instanceof android.view.View) {
                    View parent = (View) binding.tvConsoleLog.getParent();
                    if (parent instanceof android.widget.ScrollView || parent instanceof androidx.core.widget.NestedScrollView) {
                        parent.scrollTo(0, binding.tvConsoleLog.getBottom());
                    }
                }
            });
        }
    }

    /**
     * Styles the dialog with a dark transparent background.
     */
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}