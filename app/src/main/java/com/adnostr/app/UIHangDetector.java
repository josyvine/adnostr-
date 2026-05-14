package com.adnostr.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * TOTAL SURVEILLANCE WATCHDOG: UIHangDetector
 * Role: Monitors the Main UI Thread for freezes, lags, and bloating.
 * 
 * Logic:
 * 1. Sends a probe to the Main Looper at regular intervals.
 * 2. Measures the "Response Latency" of the interface.
 * 3. Records detailed reports if rendering exceeds performance thresholds.
 */
public class UIHangDetector {

    private static final String TAG = "UIHangDetector";
    
    // Performance Thresholds (in milliseconds)
    private static final long CHECK_INTERVAL = 500;  // How often to check UI health
    private static final long BLOAT_THRESHOLD = 200; // Time beyond which UI is considered "Laggy"
    private static final long HANG_THRESHOLD = 2000; // Time beyond which UI is considered "Frozen"

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Thread watchdogThread;
    private static volatile boolean isRunning = false;
    
    // Tracking variables for the handshake logic
    private static long lastTickTime = 0;
    private static final Object syncObject = new Object();
    private static boolean isCallbackRunning = false;

    /**
     * Starts the surveillance watchdog.
     * Called on Application startup (AdNostrApplication).
     */
    public static void startWatchdog() {
        if (isRunning) return;
        isRunning = true;

        watchdogThread = new Thread(() -> {
            ActionReportLogger.logAction("WATCHDOG", "UI Surveillance Thread Started.");
            
            while (isRunning) {
                final long startTime = System.currentTimeMillis();
                isCallbackRunning = true;

                // Send a "Tick" to the Main UI Thread
                mainHandler.post(() -> {
                    synchronized (syncObject) {
                        isCallbackRunning = false;
                        lastTickTime = System.currentTimeMillis();
                        syncObject.notifyAll();
                    }
                });

                try {
                    // Watchdog waits for the Main Thread to finish the task
                    synchronized (syncObject) {
                        long waitTime = HANG_THRESHOLD;
                        while (isCallbackRunning && waitTime > 0) {
                            long startWait = System.currentTimeMillis();
                            syncObject.wait(waitTime);
                            waitTime -= (System.currentTimeMillis() - startWait);
                        }
                    }

                    long duration = System.currentTimeMillis() - startTime;

                    // EVALUATION LOGIC
                    if (isCallbackRunning) {
                        // CASE 1: HARD HANG
                        // The Main Thread is completely frozen and didn't respond within 2 seconds.
                        captureAndLogHang(System.currentTimeMillis() - startTime);
                    } else if (duration > BLOAT_THRESHOLD) {
                        // CASE 2: UI BLOAT / LAG
                        // The UI responded, but it took too long (rendering was slow).
                        ActionReportLogger.logPerformance("UI_BLOAT", 
                            "Significant lag detected. Main Thread response time: " + duration + "ms");
                    }

                } catch (InterruptedException e) {
                    ActionReportLogger.logError("WATCHDOG_INTERRUPT", e.getMessage());
                    break;
                }

                // Sleep before the next health check
                try {
                    Thread.sleep(CHECK_INTERVAL);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AdNostr-UI-Watchdog");

        watchdogThread.setPriority(Thread.MAX_PRIORITY);
        watchdogThread.start();
    }

    /**
     * Identifies exactly where the app is stuck by analyzing the Main Thread's Stack Trace.
     */
    private static void captureAndLogHang(long duration) {
        StringBuilder report = new StringBuilder();
        report.append("CRITICAL HANG DETECTED.\n");
        report.append("Total Freeze Duration: ").append(duration).append("ms\n");
        report.append("Analysis: Main UI Thread is blocked.\n");
        report.append("Suspected Location (Stack Trace):\n");

        // Extract the current stack trace of the Main Thread
        StackTraceElement[] stackTrace = Looper.getMainLooper().getThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            report.append("    at ").append(element.toString()).append("\n");
        }

        // Save the detailed forensic report to the .txt file
        ActionReportLogger.logHang("CRITICAL_FREEZE", report.toString());
        Log.e(TAG, "!!! UI HANG DETECTED !!! Check Surveillance Report folder.");
    }

    public static void stopWatchdog() {
        isRunning = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }
}