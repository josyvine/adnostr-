package com.adnostr.app;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

/**
 * Bridge Utility for AdNostr Rich Text.
 * GLITCH FIX: Translates Android Spans into Standard HTML tags.
 * This ensures that edited descriptions (colors, sizes, bullets) 
 * are preserved "as is" during Nostr transmission and display 
 * correctly in the Ad Popup Slider and "Read More" screen.
 */
public class HtmlStandardizer {

    /**
     * Converts a Spannable string from the Editor into standardized HTML.
     */
    public static String toStandardHtml(Spannable text) {
        if (text == null || text.length() == 0) return "";

        StringBuilder sb = new StringBuilder();
        int next;
        for (int i = 0; i < text.length(); i = next) {
            next = text.nextSpanTransition(i, text.length(), Object.class);
            
            Object[] spans = text.getSpans(i, next, Object.class);
            StringBuilder openTags = new StringBuilder();
            StringBuilder closeTags = new StringBuilder();

            // MANUAL BULLET BRIDGE FLAG
            boolean hasBullet = false;

            for (Object span : spans) {
                if (span instanceof StyleSpan) {
                    int style = ((StyleSpan) span).getStyle();
                    if (style == Typeface.BOLD) {
                        openTags.append("<b>");
                        closeTags.insert(0, "</b>");
                    } else if (style == Typeface.ITALIC) {
                        openTags.append("<i>");
                        closeTags.insert(0, "</i>");
                    }
                } else if (span instanceof UnderlineSpan) {
                    openTags.append("<u>");
                    closeTags.insert(0, "</u>");
                } else if (span instanceof ForegroundColorSpan) {
                    int color = ((ForegroundColorSpan) span).getForegroundColor();
                    String hexColor = String.format("#%06X", (0xFFFFFF & color));
                    openTags.append("<font color=\"").append(hexColor).append("\">");
                    closeTags.insert(0, "</font>");
                } else if (span instanceof AbsoluteSizeSpan) {
                    // Translate exact dip sizes into relative HTML sizes
                    int size = ((AbsoluteSizeSpan) span).getSize();
                    if (size > 24) openTags.append("<big><big>");
                    else if (size > 18) openTags.append("<big>");
                    
                    if (size > 24) closeTags.insert(0, "</big></big>");
                    else if (size > 18) closeTags.insert(0, "</big>");
                } else if (span instanceof RelativeSizeSpan) {
                    float proportion = ((RelativeSizeSpan) span).getSizeChange();
                    if (proportion > 1.1f) {
                        openTags.append("<h1>");
                        closeTags.insert(0, "</h1>");
                    }
                } else if (span instanceof BulletSpan) {
                    // FLAGGED: We found a BulletSpan
                    hasBullet = true;
                }
            }

            // INJECT: Manually burn the bullet character into the string builder
            // before the text segment, ensuring it survives JSON serialization.
            if (hasBullet) {
                sb.append("• ");
            }

            sb.append(openTags);
            // Escape HTML characters in the raw text segment
            String segment = text.subSequence(i, next).toString()
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>");
            sb.append(segment);
            sb.append(closeTags);
        }

        return sb.toString();
    }
}