package com.adnostr.app;

import android.os.Build;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Extracted Adapter for the Synchronized Ad Text Slider.
 * GLITCH FIX: Explicitly uses TextView.BufferType.SPANNABLE. 
 * This ensures that when the ViewPager2 recycles pages, your bold, 
 * italic, and color formatting remains "as is" and does not get stripped.
 */
public class AdTextSliderAdapter extends RecyclerView.Adapter<AdTextSliderAdapter.TextHolder> {

    private final String title;
    private final List<String> chunks;

    public AdTextSliderAdapter(String title, List<String> chunks) {
        this.title = title;
        this.chunks = chunks;
    }

    @NonNull
    @Override
    public TextHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Use the standard simple list item layout for the description slides
        View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);

        // ViewPager2 child views MUST have MATCH_PARENT layout params to render correctly
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        return new TextHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TextHolder holder, int position) {
        TextView tvTitle = holder.itemView.findViewById(android.R.id.text1);
        TextView tvDesc = holder.itemView.findViewById(android.R.id.text2);

        // Set the Ad Title (Static across all slides)
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(20);

        // GLITCH FIX: Convert chunk HTML into visual formatting and lock the BufferType
        // to SPANNABLE. This prevents the recycling mechanism from losing the edited styles.
        String htmlChunk = chunks.get(position);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvDesc.setText(Html.fromHtml(htmlChunk, Html.FROM_HTML_MODE_LEGACY), TextView.BufferType.SPANNABLE);
        } else {
            tvDesc.setText(Html.fromHtml(htmlChunk), TextView.BufferType.SPANNABLE);
        }

        // Set base text properties (Spannable HTML will override these for specific edited parts)
        tvDesc.setTextColor(0xFFBDBDBD);
        tvDesc.setTextSize(14);
    }

    @Override
    public int getItemCount() {
        return chunks != null ? chunks.size() : 0;
    }

    static class TextHolder extends RecyclerView.ViewHolder {
        TextHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}