package com.ghostdocs.vault.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * PdfExportHelper — Secure FileProvider Intent Dispatcher for PDF Export & Sharing
 *
 * <p>Encapsulates Android {@link FileProvider} URI translation and intent construction
 * for viewing and sharing encrypted export files.
 */
public final class PdfExportHelper {

    private static final String TAG = "GhostDocs.PdfHelper";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    private PdfExportHelper() {
        throw new UnsupportedOperationException("PdfExportHelper is a static utility class.");
    }

    /**
     * Converts a local File to a secure content URI using GhostDocs FileProvider.
     */
    @NonNull
    public static Uri getSecureUriForFile(@NonNull Context context, @NonNull File file) {
        String authority = context.getPackageName() + AUTHORITY_SUFFIX;
        return FileProvider.getUriForFile(context, authority, file);
    }

    /**
     * Launches the system PDF viewer for the given PDF file.
     *
     * @param context Host context or activity.
     * @param pdfFile Target PDF file on disk.
     */
    public static void openPdf(@NonNull Context context, @NonNull File pdfFile) {
        try {
            Uri contentUri = getSecureUriForFile(context, pdfFile);

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(contentUri, "application/pdf");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(viewIntent, "Open Searchable PDF");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

        } catch (Exception e) {
            Log.e(TAG, "Failed to launch PDF viewer", e);
            Toast.makeText(context, "No PDF viewer app installed to open file.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens the Android share sheet for sharing the generated PDF.
     *
     * @param context Host context or activity.
     * @param pdfFile Target PDF file on disk.
     * @param title   Document title for share dialog.
     */
    public static void sharePdf(@NonNull Context context, @NonNull File pdfFile, @NonNull String title) {
        try {
            Uri contentUri = getSecureUriForFile(context, pdfFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Secured with GhostDocs Vault: " + title);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, "Share Document PDF");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

        } catch (Exception e) {
            Log.e(TAG, "Failed to launch PDF share sheet", e);
            Toast.makeText(context, "Could not share PDF.", Toast.LENGTH_SHORT).show();
        }
    }
}

