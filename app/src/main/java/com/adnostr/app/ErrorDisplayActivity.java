package com.adnostr.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityErrorDisplayBinding;

/**
 * Diagnostic Activity for AdNostr.
 * Displays a detailed full-screen popup when an uncaught Java exception occurs.
 */
public class ErrorDisplayActivity extends AppCompatActivity {

    private ActivityErrorDisplayBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewBinding for the error layout
        binding = ActivityErrorDisplayBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 1. Retrieve the detailed stack trace from the Intent
        String errorLog = getIntent().getStringExtra("ERROR_DETAILS");

        if (errorLog == null || errorLog.isEmpty()) {
            errorLog = "No detailed error log available. The process crashed silently.";
        }

        // 2. Display the error details in the large scrollable text area
        binding.tvDetailedErrorLog.setText(errorLog);

        // 3. Setup Button: Copy Error to Clipboard
        // Useful for pasting into GitHub Issues or AI for diagnosis
        binding.btnCopyError.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("AdNostr Crash Log", binding.tvDetailedErrorLog.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        // 4. Setup Button: Close & Restart
        // Closes the error screen and ensures the background process is terminated
        binding.btnCloseError.setOnClickListener(v -> {
            terminateAppGracefully();
        });
    }

    /**
     * Completely shuts down the app process after the user closes the error screen.
     */
    private void terminateAppGracefully() {
        finish();
        // Since this runs on a separate :error_handler process, we kill it to exit
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    /**
     * Prevents the user from accidentally closing the error screen via the back button
     * without seeing the diagnostic log first.
     */
    @Override
    public void onBackPressed() {
        // Option: allow exit or block to force reading log.
        // We will allow exit but trigger the same cleanup logic.
        terminateAppGracefully();
    }
}