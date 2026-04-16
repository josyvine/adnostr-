package com.adnostr.app;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Adapter for the Professional Rich Text Color Palette.
 * Renders a horizontal list of 10 distinct colors for the text editor.
 */
public class ColorPaletteAdapter extends RecyclerView.Adapter<ColorPaletteAdapter.ColorViewHolder> {

    // 10 Professional Color Swatches for the Editor
    private final String[] hexColors = {
            "#FFFFFF", // White
            "#BDBDBD", // Light Gray
            "#F44336", // Red
            "#E91E63", // Pink
            "#9C27B0", // Purple
            "#2196F3", // Blue
            "#00BCD4", // Cyan
            "#4CAF50", // Green
            "#FFEB3B", // Yellow
            "#FF9800"  // Orange
    };

    private final OnColorClickListener listener;

    public interface OnColorClickListener {
        void onColorClicked(int color);
    }

    public ColorPaletteAdapter(OnColorClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ColorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // We inflate the circular swatch layout we created earlier
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_color_swatch, parent, false);
        return new ColorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ColorViewHolder holder, int position) {
        String hexCode = hexColors[position];
        holder.bind(hexCode, listener);
    }

    @Override
    public int getItemCount() {
        return hexColors.length;
    }

    static class ColorViewHolder extends RecyclerView.ViewHolder {
        private final CardView cvColorCircle;

        public ColorViewHolder(@NonNull View itemView) {
            super(itemView);
            cvColorCircle = itemView.findViewById(R.id.cvColorCircle);
        }

        public void bind(String hexCode, OnColorClickListener listener) {
            // Parse the hex string into an Android Color integer
            int parsedColor = Color.parseColor(hexCode);
            
            // Set the circular card's background to this color
            cvColorCircle.setCardBackgroundColor(parsedColor);

            // Handle the click event and pass the color back to the Activity
            cvColorCircle.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onColorClicked(parsedColor);
                }
            });
        }
    }
}