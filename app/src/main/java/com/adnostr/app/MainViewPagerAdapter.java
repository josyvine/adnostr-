package com.adnostr.app;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Adapter for the Main ViewPager2.
 * FEATURE: Manages the 6 primary screens of AdNostr.
 * FEATURE: Enables the "Swipe Left to Right / Right to Left" logic.
 * SCREENS: 
 * 0: User Interests (UserDashboard)
 * 1: Ads History (The new History System)
 * 2: Advertiser Stats (AdvDashboard)
 * 3: Broadcast New Ad (CreateAd)
 * 4: Relay Marketplace (Marketplace)
 * 5: App Settings (Settings)
 */
public class MainViewPagerAdapter extends FragmentStateAdapter {

    private final String userRole;

    public MainViewPagerAdapter(@NonNull FragmentActivity fragmentActivity, String role) {
        super(fragmentActivity);
        this.userRole = role;
    }

    /**
     * Instantiates the fragment for the given position.
     * This defines the order of the 6 swipeable screens.
     */
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                // User Interests (Consumer Path)
                return new UserDashboardFragment();
            case 1:
                // Central Ads History System (Shared)
                return new AdsHistoryFragment();
            case 2:
                // Advertiser Dashboard / Stats (Business Path)
                return new AdvDashboardFragment();
            case 3:
                // Ad Creation Interface
                return new CreateAdFragment();
            case 4:
                // Decentralized Relay Marketplace
                return new MarketplaceFragment();
            case 5:
                // Global Settings & Profile
                return new SettingsFragment();
            default:
                return new UserDashboardFragment();
        }
    }

    /**
     * Returns the total count of swipeable items.
     * FIXED: Set to 6 as per user requirement.
     */
    @Override
    public int getItemCount() {
        return 6;
    }
}