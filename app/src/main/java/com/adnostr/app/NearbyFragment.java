package com.adnostr.app;

import android.Manifest;
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
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                myCurrentLocation = location;
                startDiscoverySubscription();
            } else {
                Log.w(TAG, "Location null. Retrying scan...");
                binding.swipeRefreshNearby.setRefreshing(false);
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

            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override public void onRelayConnected(String url) { wsManager.subscribe(url, req); }
                @Override public void onRelayDisconnected(String url, String reason) {}
                @Override public void onError(String url, Exception ex) {}

                @Override
                public void onMessageReceived(String url, String message) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> processNearbyEvent(message));
                    }
                }
            });

            // Connect to bootstrap if not already active
            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            Log.e(TAG, "Subscription failure: " + e.getMessage());
        }
    }

    private void processNearbyEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            JSONArray msg = new JSONArray(rawMessage);
            if (!"EVENT".equals(msg.getString(0))) {
                if ("EOSE".equals(msg.getString(0))) binding.swipeRefreshNearby.setRefreshing(false);
                return;
            }

            JSONObject event = msg.getJSONObject(2);
            String encryptedContent = event.getString("content");
            String senderPubkey = event.getString("pubkey");

            // 1. Decrypt using Master App Key
            String decryptedJson = EncryptionUtils.decryptPayload(encryptedContent);
            JSONObject locData = new JSONObject(decryptedJson);

            double lat = locData.getDouble("lat");
            double lon = locData.getDouble("lon");
            String role = locData.optString("role", "USER");
            String name = locData.optString("name", "Anonymous");

            // =========================================================================
            // FIXED (GLITCHES 1, 2, & 9): RADAR FILTERING LOGIC
            // =========================================================================
            String myPubkey = db.getPublicKey();
            String myRole = db.getUserRole();

            // Self Filter: Prevent seeing yourself on the radar
            if (senderPubkey.equals(myPubkey)) {
                return;
            }

            // Role Filter for USERS: Only show ADVERTISERS
            if (RoleSelectionActivity.ROLE_USER.equals(myRole) && !RoleSelectionActivity.ROLE_ADVERTISER.equals(role)) {
                return;
            }
            
            // Role Filter for ADVERTISERS: Only show USERS
            if (RoleSelectionActivity.ROLE_ADVERTISER.equals(myRole) && !RoleSelectionActivity.ROLE_USER.equals(role)) {
                return;
            }

            // 2. Proximity Calculation (Haversine)
            double distance = calculateDistance(myCurrentLocation.getLatitude(), myCurrentLocation.getLongitude(), lat, lon);

            if (distance <= MAX_DISTANCE_KM) {
                updateNearbyList(new NearbyUser(name, role, distance, senderPubkey));
            }

        } catch (Exception ignored) {}
    }

    /**
     * Logic: Add new discovery -> Filter duplicates by Pubkey -> Sort by distance.
     */
    private void updateNearbyList(NearbyUser user) {
        // Prevent duplicate entries for the same pubkey
        for (int i = 0; i < nearbyList.size(); i++) {
            if (nearbyList.get(i).pubkey.equals(user.pubkey)) {
                nearbyList.set(i, user); // Update with latest distance
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
        
        if (nearbyList.isEmpty()) {
            binding.llNoNearby.setVisibility(View.VISIBLE);
        } else {
            binding.llNoNearby.setVisibility(View.GONE);
        }
    }

    private void refreshNearbyScan() {
        binding.swipeRefreshNearby.setRefreshing(true);
        requestMySnapshotLocation();
    }

    /**
     * Standard Haversine math to calculate distance between two GPS points in KM.
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double theta = lon1 - lon2;
        double dist = Math.sin(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.cos(Math.toRadians(theta));
        dist = Math.acos(dist);
        dist = Math.toDegrees(dist);
        return dist * 60 * 1.1515 * 1.609344;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Simple internal model for discovered users.
     */
    public static class NearbyUser {
        String name, role, pubkey;
        double distance;

        NearbyUser(String n, String r, double d, String p) {
            this.name = n; this.role = r; this.distance = d; this.pubkey = p;
        }
    }
}