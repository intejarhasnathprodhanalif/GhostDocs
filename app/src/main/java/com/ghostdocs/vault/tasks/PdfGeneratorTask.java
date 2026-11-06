package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.ocr.OcrBoundingBox;
import com.ghostdocs.vault.security.AesEncryptionHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * PdfGeneratorTask — Searchable PDF Generator
 *
 * <p>Uses Android's native {@link PdfDocument} API to generate a searchable PDF
 * from a document bitmap and ML Kit OCR bounding boxes.
 *
 * <p><b>Searchable PDF Mechanism:</b>
 * <ol>
 *   <li>The document bitmap is drawn 1:1 onto the PDF canvas page.</li>
 *   <li>Transparent text (using {@link Paint} with {@link Color#TRANSPARENT}) is drawn
 *       over the exact bounding boxes extracted by Google ML Kit.</li>
 *   <li>PDF viewers (Drive, Acrobat, Chrome) index the transparent text layer,
 *       enabling full-text search, selection, and copy-paste while showing the image.</li>
 * </ol>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link BaseVaultTask}.</li>
 *   <li><b>Polymorphism:</b> Implements {@link #doInBackground()} on a background thread.</li>
 *   <li><b>Encapsulation:</b> PDF streams, page allocations, and paint setups are private.</li>
 * </ul>
 */
public class PdfGeneratorTask extends BaseVaultTask<File> {

    private static final String TAG = "GhostDocs.PdfTask";

    private final Bitmap sourceBitmap;
    private final File encryptedSourceFile;
    private final List<OcrBoundingBox> boundingBoxes;
    private final String docId;
    private final String docTitle;

    /**
     * Constructor for generating directly from an in-memory bitmap and bounding boxes.
     */
    public PdfGeneratorTask(@NonNull Context context,
                            @NonNull Bitmap sourceBitmap,
                            @NonNull List<OcrBoundingBox> boundingBoxes,
                            @NonNull String docId,
                            @NonNull String docTitle,
                            @Nullable TaskCallback<File> callback) {
        super(context, callback);
        this.sourceBitmap = sourceBitmap;
        this.encryptedSourceFile = null;
        this.boundingBoxes = boundingBoxes;
        this.docId = docId;
        this.docTitle = docTitle;
    }

    /**
     * Constructor for generating on-demand from an encrypted vault file.
     */
    public PdfGeneratorTask(@NonNull Context context,
                            @NonNull File encryptedSourceFile,
                            @NonNull List<OcrBoundingBox> boundingBoxes,
                            @NonNull String docId,
                            @NonNull String docTitle,
                            @Nullable TaskCallback<File> callback) {
        super(context, callback);
        this.sourceBitmap = null;
        this.encryptedSourceFile = encryptedSourceFile;
        this.boundingBoxes = boundingBoxes;
        this.docId = docId;
        this.docTitle = docTitle;
    }

    @Override
    protected File doInBackground() throws Exception {
        Log.d(TAG, "Generating searchable PDF for: " + docTitle + " (ID: " + docId + ")");

        // 1. Resolve source bitmap (either in-memory or decrypted from .enc file)
        Bitmap bitmapToRender = sourceBitmap;
        if (bitmapToRender == null && encryptedSourceFile != null) {
            bitmapToRender = AesEncryptionHelper.getInstance().decryptFileToBitmap(encryptedSourceFile);
        }

        if (bitmapToRender == null) {
            throw new IOException("Failed to obtain bitmap for PDF generation.");
        }

        int pageWidth = bitmapToRender.getWidth();
        int pageHeight = bitmapToRender.getHeight();

        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("Invalid bitmap dimensions: " + pageWidth + "x" + pageHeight);
        }

        // 2. Initialize native Android PdfDocument
        PdfDocument document = new PdfDocument();

        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // 3. Draw document image 1:1 onto canvas
            canvas.drawBitmap(bitmapToRender, 0, 0, null);

            // 4. Configure transparent paint for searchable text layer
            Paint textPaint = new Paint();
            textPaint.setColor(Color.TRANSPARENT); // Invisible to human eye, but encoded in PDF text stream
            textPaint.setAntiAlias(true);

            // 5. Render OCR text elements at their exact bounding box coordinates
            if (boundingBoxes != null && !boundingBoxes.isEmpty()) {
                for (OcrBoundingBox box : boundingBoxes) {
                    Rect rect = box.getBoundingBox();
                    String text = box.getText();

                    if (rect != null && !rect.isEmpty() && text != null && !text.trim().isEmpty()) {
                        // Scale text size to match box height
                        float boxHeight = Math.max(12f, (float) rect.height());
                        textPaint.setTextSize(boxHeight * 0.85f);

                        // In Android Canvas, drawText Y coordinate represents the baseline
                        float baselineY = rect.bottom;
                        float x = rect.left;

                        canvas.drawText(text, x, baselineY, textPaint);
                    }
                }
            }

            document.finishPage(page);

            // 6. Ensure export directory exists: context.getExternalFilesDir / GhostDocs / exports
            File exportDir = new File(context.getExternalFilesDir(null), "GhostDocs/exports");
            if (!exportDir.exists()) {
                boolean created = exportDir.mkdirs();
                Log.d(TAG, "Export directory created: " + created);
            }

            // Clean title for safe filesystem filename
            String safeTitle = docTitle.replaceAll("[^a-zA-Z0-9.-]", "_");
            if (safeTitle.length() > 30) {
                safeTitle = safeTitle.substring(0, 30);
            }

            File pdfFile = new File(exportDir, safeTitle + "_" + docId.substring(0, Math.min(8, docId.length())) + ".pdf");

            // 7. Write PDF document to disk
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            Log.i(TAG, "Searchable PDF generated successfully: " + pdfFile.getAbsolutePath()
                    + " (" + pdfFile.length() + " bytes)");

            return pdfFile;

        } finally {
            document.close();
        }
    }
}

