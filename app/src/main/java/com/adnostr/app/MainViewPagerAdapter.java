package com.adnostr.app;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Adapter for the Main ViewPager2.
 * FEATURE: Manages the primary screens of AdNostr dynamically based on Role.
 * FEATURE: Enables the "Swipe Left to Right / Right to Left" logic.
 * 
 * UPDATED: USER ROLE (5 TABS) -> Interests, History, Nearby, Console, Settings.
 * UPDATED: ADVERTISER ROLE (8 TABS) -> Stats, History, Broadcast, Network, Publisher, Nearby, Console, Settings.
 * 
 * TOTAL SURVEILLANCE UPDATE:
 * - Performance Tracking: Logs the exact time taken to instantiate each fragment.
 * - Interaction Logging: Records every tab generation request to the .txt report.
 */
public class MainViewPagerAdapter extends FragmentStateAdapter {

    private final String userRole;

    public MainViewPagerAdapter(@NonNull FragmentActivity fragmentActivity, String role) {
        super(fragmentActivity);
        this.userRole = role;
    }

    /**
     * Instantiates the fragment for the given position based on the User's Role.
     * This separates the User and Advertiser views completely.
     * SURVEILLANCE: Measures initialization time to detect UI Bloating during swipes.
     */
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        long startTime = System.currentTimeMillis();
        Fragment fragment;

        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            // USER PATH (Exactly 5 Fragments)
            switch (position) {
                case 0:
                    fragment = new UserDashboardFragment(); // Interests
                    break;
                case 1:
                    fragment = new AdsHistoryFragment();    // History
                    break;
                case 2:
                    fragment = new NearbyFragment();        // Nearby (Feature 3)
                    break;
                case 3:
                    fragment = new ConsoleFragment();       // Console (New Tab)
                    break;
                case 4:
                    fragment = new SettingsFragment();      // Settings
                    break;
                default:
                    fragment = new UserDashboardFragment();
                    break;
            }
        } else {
            // ADVERTISER PATH (Exactly 8 Fragments)
            switch (position) {
                case 0:
                    fragment = new AdvDashboardFragment();  // Stats
                    break;
                case 1:
                    fragment = new AdsHistoryFragment();    // History
                    break;
                case 2:
                    fragment = new CreateAdFragment();      // Broadcast
                    break;
                case 3:
                    fragment = new MarketplaceFragment();   // Network
                    break;
                case 4:
                    fragment = new AdsPublisherFragment();  // Publisher (Feature 5)
                    break;
                case 5:
                    fragment = new NearbyFragment();        // Nearby (Feature 3)
                    break;
                case 6:
                    fragment = new ConsoleFragment();       // Console (New Tab)
                    break;
                case 7:
                    fragment = new SettingsFragment();      // Settings
                    break;
                default:
                    fragment = new AdvDashboardFragment();
                    break;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        String fragName = fragment.getClass().getSimpleName();
        ActionReportLogger.logAction("PAGER_RENDER", "Generated " + fragName + " for position " + position + ". Time: " + duration + "ms");

        return fragment;
    }

    /**
     * Returns the total count of swipeable items based on the Role.
     * UPDATED: 5 for User, 8 for Advertiser to include permanent Console.
     */
    @Override
    public int getItemCount() {
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            return 5; 
        } else {
            return 8;
        }
    }
}