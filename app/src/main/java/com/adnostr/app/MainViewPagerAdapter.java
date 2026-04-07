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
 * FIXED: USER ROLE (3 TABS) -> Interests, History, Settings.
 * FIXED: ADVERTISER ROLE (5 TABS) -> Stats, History, Broadcast, Network, Settings.
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
            // USER PATH (Exactly 3 Fragments)
            switch (position) {
                case 0:
                    return new UserDashboardFragment(); // Interests
                case 1:
                    return new AdsHistoryFragment();    // History
                case 2:
                    return new SettingsFragment();      // Settings
                default:
                    return new UserDashboardFragment();
            }
        } else {
            // ADVERTISER PATH (Exactly 5 Fragments)
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
                    return new SettingsFragment();      // Settings
                default:
                    return new AdvDashboardFragment();
            }
        }
    }

    /**
     * Returns the total count of swipeable items based on the Role.
     * FIXED: 3 for User, 5 for Advertiser.
     */
    @Override
    public int getItemCount() {
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            return 3; 
        } else {
            return 5;
        }
    }
}