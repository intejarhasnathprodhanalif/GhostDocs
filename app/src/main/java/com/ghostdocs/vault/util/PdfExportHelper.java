package com.ghostdocs.vault.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PdfExportHelper — Secure FileProvider Intent Dispatcher & Native PDF Processing Engine
 *
 * <p>Encapsulates Android {@link FileProvider} URI translation, native {@link PdfRenderer}
 * page-1 bitmap generation (for ML Kit OCR & thumbnails), and Scoped Storage downloads export.
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

    /**
     * Renders the first page of a PDF into a high-quality Bitmap.
     * Suitable for OCR, preview, and thumbnail caching.
     *
     * @param context Application context.
     * @param pdfUri  Content URI of the PDF.
     * @param maxDimension Maximum width or height of the rendered bitmap (e.g. 300 for thumb, 1200 for OCR).
     * @return Decoded Bitmap of Page 1, or null if rendering fails.
     */
    @Nullable
    public static Bitmap renderFirstPage(@NonNull Context context, @NonNull Uri pdfUri, int maxDimension) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
            if (pfd == null) return null;

            renderer = new PdfRenderer(pfd);
            if (renderer.getPageCount() == 0) return null;

            page = renderer.openPage(0);
            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();

            int targetWidth = pageWidth;
            int targetHeight = pageHeight;

            if (maxDimension > 0 && (pageWidth > maxDimension || pageHeight > maxDimension)) {
                if (pageWidth >= pageHeight) {
                    targetWidth = maxDimension;
                    targetHeight = Math.max(1, (int) ((float) targetWidth * pageHeight / pageWidth));
                } else {
                    targetHeight = maxDimension;
                    targetWidth = Math.max(1, (int) ((float) targetHeight * pageWidth / pageHeight));
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Failed to render PDF page 1: " + pdfUri, e);
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Saves an unencrypted copy of a PDF to the device's public Downloads directory.
     *
     * @param context Host context.
     * @param sourcePdfFile Local PDF file on disk.
     * @param docTitle Title of the document for file naming.
     * @return true if successfully saved, false otherwise.
     */
    public static boolean savePdfToDownloads(@NonNull Context context, @NonNull File sourcePdfFile, @NonNull String docTitle) {
        String safeName = sanitizeFilename(docTitle) + "_" + System.currentTimeMillis() + ".pdf";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GhostDocs");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(sourcePdfFile);
                         OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                    return true;
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File ghostDir = new File(downloadsDir, "GhostDocs");
                ghostDir.mkdirs();
                File outFile = new File(ghostDir, safeName);
                try (InputStream in = new FileInputStream(sourcePdfFile);
                     OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save PDF to Downloads", e);
        }
        return false;
    }

    public static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) return "GhostDocs_Document";
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
