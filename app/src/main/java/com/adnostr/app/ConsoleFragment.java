package com.adnostr.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.adnostr.app.databinding.FragmentConsoleBinding;

/**
 * FEATURE: Permanent System Console Tab.
 * Logic: Periodically polls WebSocketClientManager for live protocol logs.
 * Provides a centralized, full-screen view of network traffic and forensic events.
 * Acts as the "Home" for minimized log dialogs.
 */
public class ConsoleFragment extends Fragment {

    private FragmentConsoleBinding binding;
    private WebSocketClientManager wsManager;
    private AdNostrDatabaseHelper db;
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private Runnable logUpdater;

    // Refresh interval for live terminal feel (1 second)
    private static final int REFRESH_MS = 1000;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentConsoleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        wsManager = WebSocketClientManager.getInstance();
        db = AdNostrDatabaseHelper.getInstance(requireContext());

        // 1. Display Truncated Identity in Header
        setupIdentityHeader();

        // 2. Setup Log Copy Action
        binding.btnCopyFullLogs.setOnClickListener(v -> copyLogsToClipboard());

        // 3. Initialize Live Log Polling
        startLogUpdates();
    }

    /**
     * Pulls the user's hex public key to identify the node in the console header.
     */
    private void setupIdentityHeader() {
        String pubKey = db.getPublicKey();
        if (pubKey != null && !pubKey.isEmpty()) {
            String truncated = pubKey.substring(0, 12) + "..." + pubKey.substring(pubKey.length() - 8);
            binding.tvConsoleIdentity.setText("Node ID: " + truncated);
        }
    }

    /**
     * Starts a recurring task to refresh the log view.
     * This ensures the permanent console is always up-to-date with WebSocket traffic.
     */
    private void startLogUpdates() {
        logUpdater = new Runnable() {
            @Override
            public void run() {
                if (binding != null) {
                    refreshTerminalOutput();
                    logHandler.postDelayed(this, REFRESH_MS);
                }
            }
        };
        logHandler.post(logUpdater);
    }

    /**
     * Fetches current log history from the manager and updates the UI.
     */
    private void refreshTerminalOutput() {
        String currentLogs = wsManager.getLiveLogs();
        if (currentLogs.isEmpty()) {
            binding.tvConsoleLogFull.setText("[SYSTEM STANDBY] No protocol frames detected.");
        } else {
            binding.tvConsoleLogFull.setText(currentLogs);
        }

        // Auto-scroll logic: ensures user sees latest incoming data
        binding.svConsoleScroll.post(() -> binding.svConsoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Standard utility to copy terminal contents to system clipboard.
     */
    private void copyLogsToClipboard() {
        String textToCopy = binding.tvConsoleLogFull.getText().toString();
        if (!textToCopy.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("AdNostr Terminal Logs", textToCopy);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(getContext(), "Full logs copied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop polling to prevent memory leaks when fragment is not visible
        if (logUpdater != null) {
            logHandler.removeCallbacks(logUpdater);
        }
        binding = null;
    }
}