package com.adnostr.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.style.AlignmentSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityRichTextEditorBinding;

/**
 * Professional Rich Text Editor Engine.
 * FEATURE: Implements Span-based formatting for Bold, Italics, and Alignment.
 * FEATURE: Supports specialized Sub-heading tool (H2) for ad sections.
 * FEATURE: Implements Bullet Point logic and Foreground Color mapping.
 * FEATURE: Serializes content to HTML for AdNostr JSON payload compatibility.
 * FIXED: Removed erroneous LayoutInflator import to resolve build failure.
 * FIXED: Focus-Loss bug resolved. Actions now automatically target current word or paragraph if no highlight is made.
 */
public class RichTextEditorActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_RichEditor";
    private ActivityRichTextEditorBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityRichTextEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 1. Load existing text if provided (passed back from CreateAdFragment)
        String existingText = getIntent().getStringExtra("EXISTING_TEXT");
        if (existingText != null && !existingText.isEmpty()) {
            if (existingText.contains("<")) {
                // If it looks like HTML, parse it
                binding.etRichContent.setText(Html.fromHtml(existingText, Html.FROM_HTML_MODE_LEGACY));
            } else {
                binding.etRichContent.setText(existingText);
            }
        }

        // 2. Setup Action Bar Logic
        binding.btnCancelEditor.setOnClickListener(v -> finish());

        binding.btnSaveRichText.setOnClickListener(v -> {
            String htmlOutput = Html.toHtml(binding.etRichContent.getText(), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
            Intent resultIntent = new Intent();
            resultIntent.putExtra("FORMATTED_HTML", htmlOutput);
            setResult(RESULT_OK, resultIntent);
            finish();
        });

        // 3. Setup Formatting Controls
        setupFormattingButtons();
    }

    private void setupFormattingButtons() {
        // --- BOLD & ITALIC ---
        binding.btnBold.setOnClickListener(v -> toggleStyleSpan(Typeface.BOLD));
        binding.btnItalic.setOnClickListener(v -> toggleStyleSpan(Typeface.ITALIC));

        // --- ALIGNMENT ---
        binding.btnAlignLeft.setOnClickListener(v -> applyAlignment(android.text.Layout.Alignment.ALIGN_NORMAL));
        binding.btnAlignCenter.setOnClickListener(v -> applyAlignment(android.text.Layout.Alignment.ALIGN_CENTER));
        binding.btnAlignRight.setOnClickListener(v -> applyAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE));

        // --- LISTS & BULLETS ---
        binding.btnBulletList.setOnClickListener(v -> applyBulletSpan());

        // --- SUB-HEADING (H2 STYLE) ---
        binding.btnSubHeading.setOnClickListener(v -> applySubHeadingStyle());

        // --- FONT SIZE & COLOR ---
        binding.btnFontSize.setOnClickListener(v -> toggleFontSize());
        binding.btnTextColor.setOnClickListener(v -> applyColor(Color.parseColor("#4CAF50"))); // Default Brand Green
    }

    /**
     * Helper to find the boundaries of the current paragraph/line.
     * Used for H2, Alignment, and Bullets when the user just taps the line without highlighting.
     */
    private int[] getParagraphBounds(int cursor) {
        String text = binding.etRichContent.getText().toString();
        if (text.isEmpty()) return new int[]{0, 0};

        int start = text.lastIndexOf('\n', cursor - 1);
        start = (start == -1) ? 0 : start + 1;

        int end = text.indexOf('\n', cursor);
        end = (end == -1) ? text.length() : end;

        return new int[]{start, end};
    }

    /**
     * Helper to find the boundaries of the current word.
     * Used for Bold, Italics, and Color when the user taps a word without highlighting.
     */
    private int[] getWordBounds(int cursor) {
        String text = binding.etRichContent.getText().toString();
        if (text.isEmpty() || cursor < 0 || cursor > text.length()) return new int[]{0, 0};

        int start = cursor;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }

        int end = cursor;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }

        return new int[]{start, end};
    }

    /**
     * Toggles Bold or Italic on the selected text.
     */
    private void toggleStyleSpan(int style) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        // If no text is selected, apply to the current word
        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }
        
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        StyleSpan[] existingSpans = spannable.getSpans(start, end, StyleSpan.class);

        boolean found = false;
        for (StyleSpan span : existingSpans) {
            if (span.getStyle() == style) {
                spannable.removeSpan(span);
                found = true;
            }
        }

        if (!found) {
            spannable.setSpan(new StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        
        // Prevent focus loss so the keyboard stays open
        binding.etRichContent.requestFocus();
    }

    /**
     * Applies paragraph alignment to the selected text.
     */
    private void applyAlignment(android.text.Layout.Alignment alignment) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        // Alignment applies to the whole paragraph, even if only a word is selected or clicked
        int[] bounds = getParagraphBounds(start);
        start = bounds[0];
        end = bounds[1];

        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new AlignmentSpan.Standard(alignment), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }

    /**
     * Applies a standard Bullet Point to the current line.
     */
    private void applyBulletSpan() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        // Bullets apply to the whole paragraph
        int[] bounds = getParagraphBounds(start);
        start = bounds[0];
        end = bounds[1];

        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new BulletSpan(20, Color.parseColor("#4CAF50")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }

    /**
     * Professional Sub-heading: Large + Bold + Accent Color.
     */
    private void applySubHeadingStyle() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        // H2 applies to the whole paragraph/line
        int[] bounds = getParagraphBounds(start);
        start = bounds[0];
        end = bounds[1];
        
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new RelativeSizeSpan(1.4f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#2196F3")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        binding.etRichContent.requestFocus();
    }

    private void toggleFontSize() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }
        
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new RelativeSizeSpan(1.2f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }

    private void applyColor(int color) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }
        
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }
}