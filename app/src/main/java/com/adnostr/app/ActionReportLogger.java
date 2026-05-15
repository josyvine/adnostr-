package com.adnostr.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TOTAL SURVEILLANCE ENGINE: ActionReportLogger
 * Role: Silently records every interaction, UI delay, and silent error to .txt files.
 * Location: Saves to a public, non-hidden folder named "adnostr report".
 * 
 * Features:
 * - Asynchronous Background Writing: Logging never blocks the Main UI thread.
 * - Millisecond Precision: Tracks exact timing of user actions and system responses.
 * - Daily Rotation: Generates a new .txt file every day for easy monitoring.
 * 
 * UPDATED FOR FORENSIC REPORTING:
 * - Captures HTML_GLITCH for disappearing dropdowns.
 * - Captures LOGIC_VIOLATION for dual-source desyncs.
 * - Captures BRIDGE_LATENCY for Javascript-to-Native calls.
 * - Captures UX_BLOCKAGE for failed button intents.
 */
public class ActionReportLogger {

    private static final String TAG = "ActionReportLogger";
    private static final String FOLDER_NAME = "adnostr report";
    
    // Background executor to handle file I/O without hanging the UI
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    
    private static File reportDirectory;
    private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    /**
     * Initializes the reporting directory.
     * Called on Application startup (AdNostrApplication).
     */
    public static void init(Context context) {
        // We target the public "Documents" directory to ensure the folder is NOT hidden
        File publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        reportDirectory = new File(publicDocs, FOLDER_NAME);

        if (!reportDirectory.exists()) {
            boolean created = reportDirectory.mkdirs();
            if (created) {
                Log.i(TAG, "Surveillance Directory Created: " + reportDirectory.getAbsolutePath());
            } else {
                // Fallback to internal files dir if public storage fails, but still labeled clearly
                reportDirectory = new File(context.getExternalFilesDir(null), FOLDER_NAME);
                reportDirectory.mkdirs();
            }
        }
    }

    /**
     * Logs a standard user action or interface transition.
     */
    public static void logAction(String category, String message) {
        writeToReport("ACTION", category, message);
    }

    /**
     * Logs a performance issue, UI bloat, or rendering delay.
     */
    public static void logPerformance(String category, String message) {
        writeToReport("PERFORMANCE", category, message);
    }

    /**
     * Logs a silent error caught in a try-catch block.
     */
    public static void logError(String category, String errorDetails) {
        writeToReport("SILENT_ERROR", category, errorDetails);
    }

    /**
     * Logs a detected UI Hang or Thread Freeze.
     */
    public static void logHang(String category, String hangDetails) {
        writeToReport("UI_HANG", category, hangDetails);
    }

    /**
     * NEW: Logs a glitch detected within the WebView HTML/JS environment.
     * Use this for: Disappearing dropdowns, empty containers, or CSS failures.
     */
    public static void logHtmlGlitch(String component, String glitchDetails) {
        writeToReport("HTML_GLITCH", component, glitchDetails);
    }

    /**
     * NEW: Logs a violation of application logic.
     * Use this for: Gold Standard vs Crowdsourced desyncs or invalid data states.
     */
    public static void logLogicViolation(String rule, String violationDetails) {
        writeToReport("LOGIC_VIOLATION", rule, violationDetails);
    }

    /**
     * NEW: Logs the time taken for a Javascript-to-Native bridge execution.
     */
    public static void logBridgeLatency(String bridgeMethod, long durationMs) {
        writeToReport("BRIDGE_LATENCY", bridgeMethod, "Execution took " + durationMs + "ms");
    }

    /**
     * NEW: Logs when a user interaction (Button click) results in a failed intent or dead zone.
     */
    public static void logUxBlockage(String buttonId, String reason) {
        writeToReport("UX_BLOCKAGE", buttonId, reason);
    }

    /**
     * Core I/O Logic: Queues the string to be written to the daily .txt file.
     * UPDATED: Captures current Thread info to identify the source of the report.
     */
    private static void writeToReport(final String level, final String category, final String message) {
        final long currentTime = System.currentTimeMillis();
        final String threadName = Thread.currentThread().getName();
        final long threadId = Thread.currentThread().getId();
        
        logExecutor.execute(() -> {
            if (reportDirectory == null) return;

            String dateStr = fileDateFormat.format(new Date(currentTime));
            String timeStr = timestampFormat.format(new Date(currentTime));
            
            // File naming: report_2023-10-27.txt
            File reportFile = new File(reportDirectory, "report_" + dateStr + ".txt");
            
            // Format: [2023-10-27 14:05:01.123] [T:JavaBridge:15] [ACTION] [UI_NAV] User clicked tab: Settings
            String logEntry = String.format("[%s] [T:%s:%d] [%s] [%s] %s\n", 
                    timeStr, threadName, threadId, level, category, message);

            FileWriter writer = null;
            try {
                // 'true' ensures we append to the file rather than overwriting it
                writer = new FileWriter(reportFile, true);
                writer.write(logEntry);
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write surveillance report: " + e.getMessage());
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {}
                }
            }
        });
    }
}