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
     */
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            // USER PATH (Exactly 5 Fragments)
            switch (position) {
                case 0:
                    return new UserDashboardFragment(); // Interests
                case 1:
                    return new AdsHistoryFragment();    // History
                case 2:
                    return new NearbyFragment();        // Nearby (Feature 3)
                case 3:
                    return new ConsoleFragment();       // Console (New Tab)
                case 4:
                    return new SettingsFragment();      // Settings
                default:
                    return new UserDashboardFragment();
            }
        } else {
            // ADVERTISER PATH (Exactly 8 Fragments)
            switch (position) {
                case 0:
                    return new AdvDashboardFragment();  // Stats
                case 1:
                    return new AdsHistoryFragment();    // History
                case 2:
                    return new CreateAdFragment();      // Broadcast
                case 3:
                    return new MarketplaceFragment();   // Network
                case 4:
                    return new AdsPublisherFragment();  // Publisher (Feature 5)
                case 5:
                    return new NearbyFragment();        // Nearby (Feature 3)
                case 6:
                    return new ConsoleFragment();       // Console (New Tab)
                case 7:
                    return new SettingsFragment();      // Settings
                default:
                    return new AdvDashboardFragment();
            }
        }
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