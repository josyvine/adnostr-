package com.adnostr.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AlignmentSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityRichTextEditorBinding;

/**
 * Professional Rich Text Editor Engine.
 * FEATURE: Implements Span-based formatting for Bold, Italics, and Alignment.
 * FEATURE: Supports specialized Sub-heading tool (H2) for ad sections.
 * FEATURE: Implements Bullet Point logic and Foreground Color mapping.
 * FEATURE: Serializes content to HTML for AdNostr JSON payload compatibility.
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
     * Toggles Bold or Italic on the selected text.
     */
    private void toggleStyleSpan(int style) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
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
    }

    /**
     * Applies paragraph alignment to the selected text.
     */
    private void applyAlignment(android.text.Layout.Alignment alignment) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new AlignmentSpan.Standard(alignment), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Applies a standard Bullet Point to the current line.
     */
    private void applyBulletSpan() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        
        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new BulletSpan(20, Color.parseColor("#4CAF50")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Professional Sub-heading: Large + Bold + Accent Color.
     */
    private void applySubHeadingStyle() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new RelativeSizeSpan(1.4f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#2196F3")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void toggleFontSize() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new RelativeSizeSpan(1.2f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void applyColor(int color) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();
        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}