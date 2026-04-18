package com.adnostr.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ActivityRichTextEditorBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.LinkedList;

/**
 * Professional Rich Text Editor Engine.
 * FEATURE: Implements Span-based formatting for Bold, Italics, and Alignment.
 * FEATURE: Supports specialized Sub-heading tool (H2) for ad sections.
 * FEATURE: Implements Bullet Point logic and Foreground Color mapping.
 * FEATURE: Serializes content to HTML for AdNostr JSON payload compatibility.
 * FIXED: Removed erroneous LayoutInflator import to resolve build failure.
 * FIXED: Focus-Loss bug resolved. Actions now automatically target current word or paragraph if no highlight is made.
 * FIXED: Bullet lists can now be toggled on and off (deleted).
 * FIXED: Empty Editor formatting resolved. Styles clicked on an empty line apply to text as you type.
 * ENHANCEMENT: Integrated Modern Popup Sliders for Text Styles, Font Sizes, and Color Palette.
 * GLITCH FIX: Applied standard HTML serialization to ensure edited text displays "as is" in popups.
 * 
 * UPGRADE: Replaced vertical number list with BottomSheetDialog Font & Size Picker.
 * UPGRADE: Added support for 5 Professional Font Families (Sans, Serif, Monospace, Light, Condensed).
 * UPGRADE: Implemented Stack-based Undo and Redo functionality with Span preservation.
 */
public class RichTextEditorActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_RichEditor";
    private ActivityRichTextEditorBinding binding;

    // Pending style states for when the user clicks a format before typing
    private boolean pendingBold = false;
    private boolean pendingItalic = false;
    private boolean pendingUnderline = false;
    private int pendingColor = -1;
    private int pendingFontSize = -1;

    // --- UNDO/REDO STATE ENGINE ---
    private final LinkedList<CharSequence> undoStack = new LinkedList<>();
    private final LinkedList<CharSequence> redoStack = new LinkedList<>();
    private boolean isUndoRedoOperation = false;
    private static final int MAX_STACK_HISTORY = 50;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityRichTextEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 1. Load existing text if provided (passed back from CreateAdFragment)
        String existingText = getIntent().getStringExtra("EXISTING_TEXT");
        if (existingText != null && !existingText.isEmpty()) {
            if (existingText.contains("<")) {
                // GLITCH FIX: Use version-safe Html.fromHtml to load existing formatting
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    binding.etRichContent.setText(Html.fromHtml(existingText, Html.FROM_HTML_MODE_LEGACY));
                } else {
                    binding.etRichContent.setText(Html.fromHtml(existingText));
                }
            } else {
                binding.etRichContent.setText(existingText);
            }
        }

        // Initialize history with the starting state
        saveStateToUndoStack();

        // 2. Setup Action Bar Logic (Professional Modern 'X' and 'Done')
        binding.btnCancelEditor.setOnClickListener(v -> finish());

        // GLITCH FIX: Optimized saving logic to preserve all edits "as is"
        binding.btnSaveRichText.setOnClickListener(v -> {
            String htmlOutput;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                htmlOutput = Html.toHtml(binding.etRichContent.getText(), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
            } else {
                htmlOutput = Html.toHtml(binding.etRichContent.getText());
            }

            Intent resultIntent = new Intent();
            resultIntent.putExtra("FORMATTED_HTML", htmlOutput);
            setResult(RESULT_OK, resultIntent);
            finish();
        });

        // 3. Setup Formatting Controls & Popups
        setupFormattingButtons();

        // 4. Setup Empty Editor Typing Logic & Undo Tracking
        setupTextWatcher();
    }

    private void setupFormattingButtons() {
        // --- UNDO / REDO ---
        binding.btnUndo.setOnClickListener(v -> performUndo());
        binding.btnRedo.setOnClickListener(v -> performRedo());

        // --- TEXT STYLING POPUP (Bold, Italic, Underline Slider) ---
        binding.btnBold.setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleStyleSpan(Typeface.BOLD);
        });
        binding.btnItalic.setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleStyleSpan(Typeface.ITALIC);
        });
        binding.btnUnderline.setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleUnderlineSpan();
        });

        // --- ALIGNMENT ---
        binding.btnAlignLeft.setOnClickListener(v -> {
            saveStateToUndoStack();
            applyAlignment(android.text.Layout.Alignment.ALIGN_NORMAL);
        });
        binding.btnAlignCenter.setOnClickListener(v -> {
            saveStateToUndoStack();
            applyAlignment(android.text.Layout.Alignment.ALIGN_CENTER);
        });
        binding.btnAlignRight.setOnClickListener(v -> {
            saveStateToUndoStack();
            applyAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE);
        });

        // --- LISTS & BULLETS ---
        binding.btnBulletList.setOnClickListener(v -> {
            saveStateToUndoStack();
            applyBulletSpan();
        });

        // --- SUB-HEADING (H2 STYLE) ---
        binding.btnSubHeading.setOnClickListener(v -> {
            saveStateToUndoStack();
            applySubHeadingStyle();
        });

        // --- FONT SETTINGS (Fixes Vertical Displacement Glitch) ---
        binding.btnFontSize.setOnClickListener(v -> showFontSettingsBottomSheet());
        binding.btnFontFamily.setOnClickListener(v -> showFontSettingsBottomSheet());

        // --- COLOR POPUP ---
        binding.btnTextColor.setOnClickListener(v -> showColorPopup(v));
    }

    // =========================================================================
    // UNDO / REDO LOGIC ENGINE
    // =========================================================================

    /**
     * Captures a snapshot of the current Spannable state.
     */
    private void saveStateToUndoStack() {
        if (isUndoRedoOperation) return;
        
        // Deep copy the current editable text including all Spans
        undoStack.push(new SpannableStringBuilder(binding.etRichContent.getText()));
        
        // Clear redo stack because a new manual change breaks the redo chain
        redoStack.clear();

        // Enforce history limit
        if (undoStack.size() > MAX_STACK_HISTORY) {
            undoStack.removeLast();
        }
    }

    private void performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }

        isUndoRedoOperation = true;
        // Move current state to Redo stack
        redoStack.push(new SpannableStringBuilder(binding.etRichContent.getText()));
        
        // Restore previous state
        CharSequence previousState = undoStack.pop();
        binding.etRichContent.setText(previousState);
        binding.etRichContent.setSelection(binding.etRichContent.getText().length());
        isUndoRedoOperation = false;
    }

    private void performRedo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show();
            return;
        }

        isUndoRedoOperation = true;
        // Move current state back to Undo stack
        undoStack.push(new SpannableStringBuilder(binding.etRichContent.getText()));
        
        // Restore the "undone" state
        CharSequence nextState = redoStack.pop();
        binding.etRichContent.setText(nextState);
        binding.etRichContent.setSelection(binding.etRichContent.getText().length());
        isUndoRedoOperation = false;
    }

    /**
     * Microsoft Word Style Bottom Sheet.
     * FIXES: Vertical number displacement by using a horizontal layout.
     * ADDS: Professional Font Family selection.
     */
    private void showFontSettingsBottomSheet() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_editor_tools, null);

        // --- Size Selectors ---
        view.findViewById(R.id.btnSize14).setOnClickListener(v -> { saveStateToUndoStack(); applyFontSize(14); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnSize18).setOnClickListener(v -> { saveStateToUndoStack(); applyFontSize(18); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnSize24).setOnClickListener(v -> { saveStateToUndoStack(); applyFontSize(24); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnSize30).setOnClickListener(v -> { saveStateToUndoStack(); applyFontSize(30); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnSize36).setOnClickListener(v -> { saveStateToUndoStack(); applyFontSize(36); bottomSheet.dismiss(); });

        // --- Font Family Selectors ---
        view.findViewById(R.id.btnFontSans).setOnClickListener(v -> { saveStateToUndoStack(); applyFontFamily("sans-serif"); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnFontSerif).setOnClickListener(v -> { saveStateToUndoStack(); applyFontFamily("serif"); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnFontMono).setOnClickListener(v -> { saveStateToUndoStack(); applyFontFamily("monospace"); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnFontLight).setOnClickListener(v -> { saveStateToUndoStack(); applyFontFamily("sans-serif-light"); bottomSheet.dismiss(); });
        view.findViewById(R.id.btnFontCondensed).setOnClickListener(v -> { saveStateToUndoStack(); applyFontFamily("sans-serif-condensed"); bottomSheet.dismiss(); });

        bottomSheet.setContentView(view);
        bottomSheet.show();
    }

    /**
     * Monitors typing to apply styles dynamically if the user clicked a format
     * while the editor or line was completely empty.
     */
    private void setupTextWatcher() {
        binding.etRichContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // If it's a manual keypress and not an Undo operation, save snapshot
                if (!isUndoRedoOperation && count != after) {
                    saveStateToUndoStack();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count > 0) { // Text was added
                    Spannable spannable = binding.etRichContent.getText();
                    if (pendingBold) {
                        spannable.setSpan(new StyleSpan(Typeface.BOLD), start, start + count, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (pendingItalic) {
                        spannable.setSpan(new StyleSpan(Typeface.ITALIC), start, start + count, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (pendingUnderline) {
                        spannable.setSpan(new UnderlineSpan(), start, start + count, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (pendingColor != -1) {
                        spannable.setSpan(new ForegroundColorSpan(pendingColor), start, start + count, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (pendingFontSize != -1) {
                        spannable.setSpan(new AbsoluteSizeSpan(pendingFontSize, true), start, start + count, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * Horizontal color palette popup showing up to 10 colors.
     */
    private void showColorPopup(View anchor) {
        View popupView = getLayoutInflater().inflate(R.layout.popup_color_palette, null);

        PopupWindow popupWindow = new PopupWindow(popupView, 
                ViewGroup.LayoutParams.WRAP_CONTENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT, true);

        RecyclerView rvColors = popupView.findViewById(R.id.rvColorPalette);
        rvColors.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        ColorPaletteAdapter colorAdapter = new ColorPaletteAdapter(color -> {
            saveStateToUndoStack();
            applyColor(color);
            popupWindow.dismiss();
        });
        rvColors.setAdapter(colorAdapter);

        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - 150);
    }

    /**
     * Horizontal slider for modern text styling (Bold, Italic, Underline, etc.)
     */
    private void showTextStylePopup(View anchor) {
        View popupView = getLayoutInflater().inflate(R.layout.popup_text_styles, null);

        PopupWindow popupWindow = new PopupWindow(popupView, 
                ViewGroup.LayoutParams.WRAP_CONTENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT, true);

        popupView.findViewById(R.id.btnPopupBold).setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleStyleSpan(Typeface.BOLD);
            popupWindow.dismiss();
        });

        popupView.findViewById(R.id.btnPopupItalic).setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleStyleSpan(Typeface.ITALIC);
            popupWindow.dismiss();
        });

        popupView.findViewById(R.id.btnPopupUnderline).setOnClickListener(v -> {
            saveStateToUndoStack();
            toggleUnderlineSpan();
            popupWindow.dismiss();
        });

        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - 150);
    }

    // =========================================================================
    // CORE FORMATTING LOGIC
    // =========================================================================

    private int[] getParagraphBounds(int cursor) {
        String text = binding.etRichContent.getText().toString();
        if (text.isEmpty()) return new int[]{0, 0};

        int start = text.lastIndexOf('\n', cursor - 1);
        start = (start == -1) ? 0 : start + 1;

        int end = text.indexOf('\n', cursor);
        end = (end == -1) ? text.length() : end;

        return new int[]{start, end};
    }

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

    private void toggleStyleSpan(int style) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }

        if (start == end) {
            if (style == Typeface.BOLD) pendingBold = !pendingBold;
            if (style == Typeface.ITALIC) pendingItalic = !pendingItalic;
            Toast.makeText(this, "Style active for typing", Toast.LENGTH_SHORT).show();
            binding.etRichContent.requestFocus();
            return;
        }

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

        binding.etRichContent.requestFocus();
    }

    private void toggleUnderlineSpan() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }

        if (start == end) {
            pendingUnderline = !pendingUnderline;
            Toast.makeText(this, "Underline active for typing", Toast.LENGTH_SHORT).show();
            binding.etRichContent.requestFocus();
            return;
        }

        Spannable spannable = binding.etRichContent.getText();
        UnderlineSpan[] existingSpans = spannable.getSpans(start, end, UnderlineSpan.class);

        if (existingSpans.length > 0) {
            for (UnderlineSpan span : existingSpans) {
                spannable.removeSpan(span);
            }
        } else {
            spannable.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        binding.etRichContent.requestFocus();
    }

    private void applyAlignment(android.text.Layout.Alignment alignment) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        int[] bounds = getParagraphBounds(start);
        start = bounds[0];
        end = bounds[1];

        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new AlignmentSpan.Standard(alignment), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }

    private void applyBulletSpan() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        int[] bounds = getParagraphBounds(start);
        start = bounds[0];
        end = bounds[1];

        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();

        BulletSpan[] existingSpans = spannable.getSpans(start, end, BulletSpan.class);
        if (existingSpans.length > 0) {
            for (BulletSpan span : existingSpans) {
                spannable.removeSpan(span);
            }
        } else {
            spannable.setSpan(new BulletSpan(20, Color.parseColor("#4CAF50")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        binding.etRichContent.requestFocus();
    }

    private void applySubHeadingStyle() {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

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

    private void applyFontSize(int sizeInDip) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }

        if (start == end) {
            pendingFontSize = sizeInDip;
            Toast.makeText(this, "Size " + sizeInDip + " set", Toast.LENGTH_SHORT).show();
            binding.etRichContent.requestFocus();
            return;
        }

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new AbsoluteSizeSpan(sizeInDip, true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }

    /**
     * Applies the selected font family to the selected text.
     */
    private void applyFontFamily(String family) {
        int start = binding.etRichContent.getSelectionStart();
        int end = binding.etRichContent.getSelectionEnd();

        if (start == end) {
            int[] bounds = getWordBounds(start);
            start = bounds[0];
            end = bounds[1];
        }

        if (start == end) return;

        Spannable spannable = binding.etRichContent.getText();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spannable.setSpan(new TypefaceSpan(family), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            spannable.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
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

        if (start == end) {
            pendingColor = color;
            Toast.makeText(this, "Color selected for typing", Toast.LENGTH_SHORT).show();
            binding.etRichContent.requestFocus();
            return;
        }

        Spannable spannable = binding.etRichContent.getText();
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.etRichContent.requestFocus();
    }
}