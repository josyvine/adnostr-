package com.cloudnest.app;

import android.webkit.MimeTypeMap;

/**
 * Helper class to map file extensions and MIME types to UI icons.
 * This ensures that non-media files (PDF, Code, Zip) have proper thumbnails.
 * FIXES: Glitch 1.
 */
public class MimeTypeHelper {

    /**
     * Returns the appropriate drawable resource ID for a given file name.
     * @param fileName The name of the file including extension.
     * @return Drawable resource ID.
     */
    public static int getIconForFile(String fileName) {
        if (fileName == null) return R.drawable.ic_file_generic;

        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName).toLowerCase();
        
        // If extension is empty, try to get it from the string manually
        if (extension.isEmpty() && fileName.contains(".")) {
            extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        }

        switch (extension) {
            // Document Types
            case "pdf":
                return R.drawable.ic_file_pdf;
            case "doc":
            case "docx":
                return R.drawable.ic_file_word;
            case "xls":
            case "xlsx":
                return R.drawable.ic_file_excel;
            case "ppt":
            case "pptx":
                return R.drawable.ic_file_ppt;
            case "txt":
            case "log":
            case "rtf":
                return R.drawable.ic_file_text;

            // Archive Types
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
                return R.drawable.ic_file_zip;

            // Code / Web Types
            case "html":
            case "htm":
            case "xml":
            case "json":
                return R.drawable.ic_file_code;
            case "js":
            case "css":
            case "php":
            case "py":
            case "java":
            case "cpp":
            case "c":
                return R.drawable.ic_file_code;

            // Audio Types
            case "mp3":
            case "wav":
            case "ogg":
            case "m4a":
                return R.drawable.ic_file_audio;

            // Default
            default:
                return R.drawable.ic_file_generic;
        }
    }

    /**
     * Checks if a MIME type is an image or video (Media).
     */
    public static boolean isMediaFile(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/") || mimeType.startsWith("video/");
    }
}