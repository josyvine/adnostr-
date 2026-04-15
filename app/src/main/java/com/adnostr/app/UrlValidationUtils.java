package com.adnostr.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Patterns;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DNS/Phishing Validation Engine for AdNostr.
 * FEATURE: Formats raw user input into valid URLs.
 * FEATURE: Enforces HTTPS and blocks raw IPv4 addresses to prevent phishing traps.
 * FEATURE: Performs asynchronous background network pings to ensure websites actually exist.
 */
public class UrlValidationUtils {

    private static final String TAG = "AdNostr_UrlValidator";

    // Regex to detect raw IPv4 addresses (e.g., 192.168.1.1 or 8.8.8.8)
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    /**
     * Callback interface to return background validation results to the UI thread.
     */
    public interface ValidationCallback {
        void onValidationResult(boolean isValid, String formattedUrl, String errorMessage);
    }

    /**
     * Formats the user input to ensure it has a valid protocol.
     * Upgrades standard http:// to https:// to enforce security.
     */
    public static String formatAndSecureUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }

        String url = input.trim();

        // Enforce HTTPS over HTTP
        if (url.toLowerCase().startsWith("http://")) {
            url = url.replaceFirst("(?i)http://", "https://");
        } 
        // Add HTTPS if no protocol is provided
        else if (!url.toLowerCase().startsWith("https://")) {
            url = "https://" + url;
        }

        return url;
    }

    /**
     * Validates the structural integrity of the URL before attempting a network ping.
     * Blocks raw IP addresses and malformed strings.
     */
    public static boolean isStructurallyValid(String formattedUrl) {
        if (formattedUrl == null || formattedUrl.isEmpty()) {
            return false;
        }

        // 1. Standard Android Web URL Regex check
        if (!Patterns.WEB_URL.matcher(formattedUrl).matches()) {
            return false;
        }

        // 2. Extract the host to check for raw IP addresses
        try {
            URL parsedUrl = new URL(formattedUrl);
            String host = parsedUrl.getHost();
            
            if (host != null) {
                Matcher ipMatcher = IPV4_PATTERN.matcher(host);
                if (ipMatcher.matches()) {
                    Log.w(TAG, "Structural validation failed: Raw IP address detected.");
                    return false; // Reject raw IP addresses
                }
            }
        } catch (Exception e) {
            return false; // Malformed URL
        }

        return true;
    }

    /**
     * Performs a comprehensive check: Formatting -> Structural Check -> Background Network Ping.
     * 
     * @param rawInput The raw string typed by the advertiser.
     * @param callback Returns the result on the main UI thread.
     */
    public static void validateUrlThoroughly(String rawInput, ValidationCallback callback) {
        final String secureUrl = formatAndSecureUrl(rawInput);

        // Step 1: Structural and Security validation
        if (!isStructurallyValid(secureUrl)) {
            if (callback != null) {
                callback.onValidationResult(false, secureUrl, "Invalid URL format or unsafe IP address.");
            }
            return;
        }

        // Step 2: Background Network Ping (DNS & HTTP Check)
        new Thread(() -> {
            boolean isAlive = false;
            String errorMsg = "Website is unreachable or dead.";
            HttpURLConnection connection = null;

            try {
                URL targetUrl = new URL(secureUrl);
                connection = (HttpURLConnection) targetUrl.openConnection();
                connection.setRequestMethod("HEAD"); // Only fetch headers to save bandwidth
                connection.setConnectTimeout(5000); // 5 second timeout
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                
                // Mask as a standard browser to prevent immediate 403s from basic bot blockers
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                int responseCode = connection.getResponseCode();
                
                // Accept 200s (Success) and 300s (Redirects)
                if (responseCode >= 200 && responseCode < 400) {
                    isAlive = true;
                    errorMsg = "";
                } else if (responseCode == 403 || responseCode == 401) {
                    // Website exists but restricts headless access. We consider this a valid DNS resolution.
                    isAlive = true;
                    errorMsg = "";
                    Log.d(TAG, "URL exists but returned " + responseCode + ". Accepting as valid.");
                } else {
                    errorMsg = "Website returned an error code: " + responseCode;
                }

            } catch (java.net.UnknownHostException e) {
                errorMsg = "DNS Resolution failed. Website does not exist.";
            } catch (java.net.SocketTimeoutException e) {
                errorMsg = "Connection timed out. Website is too slow or offline.";
            } catch (Exception e) {
                errorMsg = "Network error: " + e.getMessage();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            // Return results to the Main UI Thread
            final boolean finalIsAlive = isAlive;
            final String finalErrorMsg = errorMsg;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) {
                    callback.onValidationResult(finalIsAlive, secureUrl, finalErrorMsg);
                }
            });

        }).start();
    }
}