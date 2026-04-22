package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemNearbyUserBinding;

import java.util.List;
import java.util.Locale;

/**
 * Adapter for the Nearby Radar List.
 * FEATURE 3: Renders discovered users/advertisers with distance info.
 * Logic: Binds NearbyUser data -> Formats Distance String -> Styles Role Badge.
 */
public class NearbyAdapter extends RecyclerView.Adapter<NearbyAdapter.NearbyViewHolder> {

    private final List<NearbyFragment.NearbyUser> nearbyList;

    public NearbyAdapter(List<NearbyFragment.NearbyUser> nearbyList) {
        this.nearbyList = nearbyList;
    }

    @NonNull
    @Override
    public NearbyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemNearbyUserBinding binding = ItemNearbyUserBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new NearbyViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull NearbyViewHolder holder, int position) {
        NearbyFragment.NearbyUser user = nearbyList.get(position);
        holder.bind(user);
    }

    @Override
    public int getItemCount() {
        return nearbyList != null ? nearbyList.size() : 0;
    }

    /**
     * ViewHolder class for individual nearby items.
     */
    static class NearbyViewHolder extends RecyclerView.ViewHolder {
        private final ItemNearbyUserBinding binding;

        public NearbyViewHolder(ItemNearbyUserBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds data and applies conditional styling based on the user's role.
         */
        public void bind(NearbyFragment.NearbyUser user) {
            // 1. Set Name
            binding.tvNearbyUserName.setText(user.name);

            // 2. Set and Style Role Badge
            binding.tvNearbyUserRole.setText(user.role);
            if (RoleSelectionActivity.ROLE_ADVERTISER.equals(user.role)) {
                // Businesses get a green tint
                binding.tvNearbyUserRole.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_green));
                binding.ivNearbyAvatar.setImageResource(R.drawable.ic_nav_publisher);
                binding.ivNearbyAvatar.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_green));
            } else {
                // Standard users get the default blue tint
                binding.tvNearbyUserRole.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_blue));
                binding.ivNearbyAvatar.setImageResource(R.drawable.ic_cmd_profile);
                binding.ivNearbyAvatar.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_blue));
            }

            // 3. Format Distance (e.g., "1.2 km")
            // If less than 1km, show in meters for better precision
            if (user.distance < 1.0) {
                int meters = (int) (user.distance * 1000);
                binding.tvNearbyProximity.setText(String.format(Locale.getDefault(), "%d m", meters));
            } else {
                binding.tvNearbyProximity.setText(String.format(Locale.getDefault(), "%.1f km", user.distance));
            }
        }
    }
}