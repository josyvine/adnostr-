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
     * Core I/O Logic: Queues the string to be written to the daily .txt file.
     */
    private static void writeToReport(final String level, final String category, final String message) {
        final long currentTime = System.currentTimeMillis();
        
        logExecutor.execute(() -> {
            if (reportDirectory == null) return;

            String dateStr = fileDateFormat.format(new Date(currentTime));
            String timeStr = timestampFormat.format(new Date(currentTime));
            
            // File naming: report_2023-10-27.txt
            File reportFile = new File(reportDirectory, "report_" + dateStr + ".txt");
            
            // Format: [2023-10-27 14:05:01.123] [ACTION] [UI_NAV] User clicked tab: Settings
            String logEntry = String.format("[%s] [%s] [%s] %s\n", timeStr, level, category, message);

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