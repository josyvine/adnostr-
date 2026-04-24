package com.adnostr.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.FragmentNearbyBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * FEATURE 3: Nearby Discovery Fragment.
 * Listens for Kind 30004 (Location Beacons) and displays users within 50km.
 * Logic: Get My GPS -> Subscribe Kind 30004 -> Decrypt -> Calculate Proximity -> Sort & Display.
 * FIXED: Role-based filtering to ensure Users see Advertisers, and Advertisers see Users.
 * FIXED: Self-filtering to ensure your own beacon doesn't show up on your radar.
 * FORENSIC UPDATE: Integrated RelayReportDialog for deep diagnostic logs on refresh.
 * CRASH FIX: Enforced runOnUiThread in logDiagnostic and used addStatusListener to prevent Popup interference.
 * ENHANCEMENT: Fixed OOM Crash by capping StringBuilder size.
 */
public class NearbyFragment extends Fragment {

    private static final String TAG = "AdNostr_Nearby";
    private static final double MAX_DISTANCE_KM = 50.0;

    private FragmentNearbyBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    private FusedLocationProviderClient fusedLocationClient;

    private Location myCurrentLocation;
    private final List<NearbyUser> nearbyList = new ArrayList<>();
    private NearbyAdapter adapter;
    
    // FIXED: Added member variable for listener tracking
    private WebSocketClientManager.RelayStatusListener mRelayListener;

    // Forensic Log Accumulator
    private final StringBuilder diagnosticLogs = new StringBuilder();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentNearbyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        wsManager = WebSocketClientManager.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());

        setupRecyclerView();
        
        binding.swipeRefreshNearby.setOnRefreshListener(this::refreshNearbyScan);

        // Start by getting our own location to calculate distances
        requestMySnapshotLocation();
    }

    private void setupRecyclerView() {
        binding.rvNearbyUsers.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NearbyAdapter(nearbyList);
        binding.rvNearbyUsers.setAdapter(adapter);
    }

    /**
     * Retrieves the device's current location once to establish a center point for discovery.
     */
    private void requestMySnapshotLocation() {
        logDiagnostic("GPS_SCAN: Requesting current device coordinates...");
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logDiagnostic("GPS_ERROR: Permission ACCESS_FINE_LOCATION denied.");
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                myCurrentLocation = location;
                logDiagnostic("GPS_SUCCESS: My Loc -> Lat: " + location.getLatitude() + ", Lon: " + location.getLongitude());
                startDiscoverySubscription();
            } else {
                logDiagnostic("GPS_FAIL: LastLocation returned null. Ensure GPS is ON and App has permission.");
                Log.w(TAG, "Location null. Retrying scan...");
                if (binding != null) binding.swipeRefreshNearby.setRefreshing(false);
            }
        });
    }

    /**
     * Sends a Nostr REQ for Kind 30004 Location Beacons.
     */
    private void startDiscoverySubscription() {
        try {
            nearbyList.clear();
            adapter.notifyDataSetChanged();
            
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30004));
            // Limit to recent beacons (last 1 hour)
            filter.put("since", (System.currentTimeMillis() / 1000) - 3600);

            String subId = "nearby-" + UUID.randomUUID().toString().substring(0, 6);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            logDiagnostic("REQ_START: Initiating Kind 30004 subscription.");
            logDiagnostic("SUB_ID: " + subId);

            // FIXED: Using addStatusListener with member variable tracking
            mRelayListener = new WebSocketClientManager.RelayStatusListener() {
                @Override 
                public void onRelayConnected(String url) { 
                    logDiagnostic("RELAY_ACTIVE: " + url + " - Sending REQ frame.");
                    wsManager.subscribe(url, req); 
                }
                
                @Override public void onRelayDisconnected(String url, String reason) {
                    logDiagnostic("RELAY_LOST: " + url + " (Reason: " + reason + ")");
                }
                
                @Override public void onError(String url, Exception ex) {
                    logDiagnostic("SOCKET_ERROR: " + url + " - " + ex.getMessage());
                }

                @Override
                public void onMessageReceived(String url, String message) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> processNearbyEvent(message));
                    }
                }
            };
            
            wsManager.addStatusListener(mRelayListener);

            // Connect to bootstrap if not already active
            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            logDiagnostic("CRITICAL_SUB_FAIL: " + e.getMessage());
            Log.e(TAG, "Subscription failure: " + e.getMessage());
        }
    }

    private void processNearbyEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            JSONArray msg = new JSONArray(rawMessage);
            if (!"EVENT".equals(msg.getString(0))) {
                if ("EOSE".equals(msg.getString(0))) {
                    logDiagnostic("EVENT_EOSE: Relay search completed.");
                    if (binding != null) binding.swipeRefreshNearby.setRefreshing(false);
                }
                return;
            }

            JSONObject event = msg.getJSONObject(2);
            int kind = event.optInt("kind", -1);
            if (kind != 30004) return;

            String encryptedContent = event.getString("content");
            String senderPubkey = event.getString("pubkey");

            logDiagnostic("EVENT_RECV: Received beacon from " + senderPubkey.substring(0, 8));

            // 1. Decrypt using Master App Key
            String decryptedJson;
            try {
                decryptedJson = EncryptionUtils.decryptPayload(encryptedContent);
                logDiagnostic("DECRYPT_OK: Forensic payload unwrapped.");
            } catch (Exception e) {
                logDiagnostic("DECRYPT_FAIL: Beacon discarded (Not AdNostr Protocol).");
                return;
            }

            JSONObject locData = new JSONObject(decryptedJson);

            double lat = locData.getDouble("lat");
            double lon = locData.getDouble("lon");
            String role = locData.optString("role", "USER");
            String name = locData.optString("name", "Anonymous");

            // RADAR FILTERING LOGIC
            String myPubkey = db.getPublicKey();
            String myRole = db.getUserRole();

            if (senderPubkey.equals(myPubkey)) {
                logDiagnostic("FILTER_SELF: Dropping my own beacon.");
                return;
            }

            if (RoleSelectionActivity.ROLE_USER.equals(myRole) && !RoleSelectionActivity.ROLE_ADVERTISER.equals(role)) {
                logDiagnostic("FILTER_ROLE: Dropped USER (I am in Consumer Mode).");
                return;
            }
            
            if (RoleSelectionActivity.ROLE_ADVERTISER.equals(myRole) && !RoleSelectionActivity.ROLE_USER.equals(role)) {
                logDiagnostic("FILTER_ROLE: Dropped ADVERTISER (I am in Business Mode).");
                return;
            }

            // 2. Proximity Calculation (Haversine)
            double distance = calculateDistance(myCurrentLocation.getLatitude(), myCurrentLocation.getLongitude(), lat, lon);
            logDiagnostic("PROXIMITY_CALC: Distance is " + String.format("%.2f", distance) + " km");

            if (distance <= MAX_DISTANCE_KM) {
                logDiagnostic("RADAR_ACCEPT: Found " + name + " within range.");
                updateNearbyList(new NearbyUser(name, role, distance, senderPubkey));
            } else {
                logDiagnostic("RADAR_REJECT: Target is outside 50km radius.");
            }

        } catch (Exception ignored) {
            logDiagnostic("PARSE_ERROR: Received malformed JSON from relay.");
        }
    }

    private void updateNearbyList(NearbyUser user) {
        for (int i = 0; i < nearbyList.size(); i++) {
            if (nearbyList.get(i).pubkey.equals(user.pubkey)) {
                nearbyList.set(i, user);
                sortAndNotify();
                return;
            }
        }
        nearbyList.add(user);
        sortAndNotify();
    }

    private void sortAndNotify() {
        Collections.sort(nearbyList, (o1, o2) -> Double.compare(o1.distance, o2.distance));
        adapter.notifyDataSetChanged();
        
        if (binding != null) {
            if (nearbyList.isEmpty()) {
                binding.llNoNearby.setVisibility(View.VISIBLE);
            } else {
                binding.llNoNearby.setVisibility(View.GONE);
            }
        }
    }

    private void refreshNearbyScan() {
        diagnosticLogs.setLength(0);
        logDiagnostic("=== INITIATING DIAGNOSTIC RADAR SCAN ===");
        logDiagnostic("ACTIVE_ROLE: " + db.getUserRole());
        
        RelayReportDialog report = RelayReportDialog.newInstance(
                "NEARBY RADAR CONSOLE", 
                "Scanning GPS Beacons...", 
                diagnosticLogs.toString()
        );
        // Link minimize listener to allow dismissal to the main navigation bar console
        report.setConsoleMinimizeListener(() -> {
            // Dismissal handled by RelayReportDialog internally
        });
        report.showSafe(getChildFragmentManager(), "NEARBY_LOG");

        binding.swipeRefreshNearby.setRefreshing(true);
        requestMySnapshotLocation();
    }

    /**
     * FIXED: Wrapped UI updates in runOnUiThread to prevent crash during background relay reports.
     * FIX: OOM Crash Fix - Limit StringBuilder Memory Footprint.
     */
    private void logDiagnostic(final String msg) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            diagnosticLogs.append("[").append(System.currentTimeMillis()).append("] ").append(msg).append("\n");

            // FIX: Prevent OutOfMemoryError by pruning old logs
            if (diagnosticLogs.length() > 20000) {
                diagnosticLogs.delete(0, 5000);
            }

            if (isAdded()) {
                RelayReportDialog report = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("NEARBY_LOG");
                if (report != null) {
                    report.updateTechnicalLogs("Forensic Scan in Progress", diagnosticLogs.toString());
                }
            }
        });
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double theta = lon1 - lon2;
        double dist = Math.sin(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.cos(Math.toRadians(theta));
        dist = Math.acos(dist);
        dist = Math.toDegrees(dist);
        return dist * 60 * 1.1515 * 1.609344;
    }

    /**
     * FIXED FOR POPUP: Unregister listener to ensure MainActivity remains the master trigger.
     */
    @Override
    public void onDestroyView() {
        if (wsManager != null && mRelayListener != null) {
            wsManager.removeStatusListener(mRelayListener);
        }
        super.onDestroyView();
        binding = null;
    }

    public static class NearbyUser {
        String name, role, pubkey;
        double distance;
        NearbyUser(String n, String r, double d, String p) {
            this.name = n; this.role = r; this.distance = d; this.pubkey = p;
        }
    }
}