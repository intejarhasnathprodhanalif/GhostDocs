package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.classifier.RegexClassifierEngine;
import com.ghostdocs.vault.ocr.OcrBoundingBox;
import com.ghostdocs.vault.util.PdfExportHelper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OcrTask — On-Device Text Recognition & Classification Task
 *
 * <p>Executes Google ML Kit Vision Text Recognition on an incoming image URI, extracts
 * bounding boxes, passes the text through the {@link RegexClassifierEngine}, and outputs
 * structured document metadata.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link BaseVaultTask}.</li>
 *   <li><b>Polymorphism:</b> Overrides {@link #doInBackground()} with ML Kit pipeline logic.</li>
 *   <li><b>Encapsulation:</b> Result data, ML Kit options, and bounding box transformation
 *       are encapsulated within {@link OcrResult}.</li>
 * </ul>
 */
public class OcrTask extends BaseVaultTask<OcrTask.OcrResult> {

    private static final String TAG = "GhostDocs.OcrTask";

    // ── Encapsulated OCR Result Object ───────────────────────────────────────
    public static class OcrResult {
        private final String fullText;
        private final RegexClassifierEngine.ClassificationResult classification;
        private final List<OcrBoundingBox> boundingBoxes;
        private final int imageWidth;
        private final int imageHeight;

        public OcrResult(@NonNull String fullText,
                         @NonNull RegexClassifierEngine.ClassificationResult classification,
                         @NonNull List<OcrBoundingBox> boundingBoxes,
                         int imageWidth, int imageHeight) {
            this.fullText = fullText;
            this.classification = classification;
            this.boundingBoxes = Collections.unmodifiableList(boundingBoxes);
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }

        @NonNull public String getFullText() { return fullText; }
        @NonNull public RegexClassifierEngine.ClassificationResult getClassification() { return classification; }
        @NonNull public List<OcrBoundingBox> getBoundingBoxes() { return boundingBoxes; }
        public int getImageWidth() { return imageWidth; }
        public int getImageHeight() { return imageHeight; }
    }

    private static final int MAX_PDF_OCR_PAGES = 4;
    private static final int PDF_OCR_MAX_DIMENSION = 1200;

    private final Uri imageUri;
    private final Bitmap sourceBitmap;
    private final String fallbackTitle;
    private final boolean isPdf;
    private final RegexClassifierEngine classifierEngine;

    public OcrTask(@NonNull Context context,
                   @NonNull Uri imageUri,
                   @NonNull String fallbackTitle,
                   @Nullable TaskCallback<OcrResult> callback) {
        this(context, imageUri, fallbackTitle,
                PdfExportHelper.isPdf(context.getContentResolver().getType(imageUri), fallbackTitle),
                callback);
    }

    public OcrTask(@NonNull Context context,
                   @NonNull Uri documentUri,
                   @NonNull String fallbackTitle,
                   boolean isPdf,
                   @Nullable TaskCallback<OcrResult> callback) {
        super(context, callback);
        this.imageUri = documentUri;
        this.sourceBitmap = null;
        this.fallbackTitle = fallbackTitle;
        this.isPdf = isPdf;
        this.classifierEngine = new RegexClassifierEngine();
    }

    public OcrTask(@NonNull Context context,
                   @NonNull Bitmap bitmap,
                   @NonNull String fallbackTitle,
                   @Nullable TaskCallback<OcrResult> callback) {
        super(context, callback);
        this.imageUri = null;
        this.sourceBitmap = bitmap;
        this.fallbackTitle = fallbackTitle;
        this.isPdf = false;
        this.classifierEngine = new RegexClassifierEngine();
    }

    @Override
    public OcrResult doInBackground() throws Exception {
        Log.d(TAG, "Starting on-device ML Kit OCR (isPdf=" + isPdf + ") for URI: " + imageUri);

        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        List<OcrBoundingBox> boundingBoxes = new ArrayList<>();
        int width = 0;
        int height = 0;
        String rawText;

        try {
            if (isPdf && imageUri != null) {
                StringBuilder combinedText = new StringBuilder();
                final int[] firstDims = new int[2];

                PdfExportHelper.processPdfPages(context, imageUri, MAX_PDF_OCR_PAGES, PDF_OCR_MAX_DIMENSION,
                        (pageIndex, pageBitmap) -> {
                            if (pageIndex == 0) {
                                firstDims[0] = pageBitmap.getWidth();
                                firstDims[1] = pageBitmap.getHeight();
                            }
                            Text visionText = Tasks.await(
                                    recognizer.process(InputImage.fromBitmap(pageBitmap, 0)));
                            if (visionText != null) {
                                String pageText = visionText.getText();
                                if (pageText != null && !pageText.trim().isEmpty()) {
                                    if (combinedText.length() > 0) {
                                        combinedText.append("\n\n");
                                    }
                                    combinedText.append(pageText.trim());
                                }
                                if (pageIndex == 0) {
                                    collectBoundingBoxes(visionText, boundingBoxes);
                                }
                            }
                        });

                width = firstDims[0];
                height = firstDims[1];
                rawText = combinedText.toString();
            } else {
                // Single bitmap or image URI
                InputImage inputImage;
                if (sourceBitmap != null) {
                    inputImage = InputImage.fromBitmap(sourceBitmap, 0);
                } else if (imageUri != null) {
                    inputImage = InputImage.fromFilePath(context, imageUri);
                } else {
                    throw new IllegalArgumentException("Neither sourceBitmap nor imageUri provided for OcrTask.");
                }
                width = inputImage.getWidth();
                height = inputImage.getHeight();

                Text visionText = Tasks.await(recognizer.process(inputImage));
                rawText = visionText != null ? visionText.getText() : "";
                if (visionText != null) {
                    collectBoundingBoxes(visionText, boundingBoxes);
                }
            }
        } finally {
            recognizer.close(); // Prevent resource leaks
        }

        Log.i(TAG, "OCR recognition completed. Extracted length: " + rawText.length() + " characters.");

        // Run Custom Regex Classification Engine across full (or multi-page) text
        RegexClassifierEngine.ClassificationResult classification =
                classifierEngine.classify(rawText, fallbackTitle);

        Log.i(TAG, "Document classified as: " + classification.getDocType()
                + " | Title: " + classification.getTitle()
                + " | Tags: " + classification.getTags());

        return new OcrResult(rawText, classification, boundingBoxes, width, height);
    }

    private static void collectBoundingBoxes(@NonNull Text visionText, @NonNull List<OcrBoundingBox> out) {
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                out.add(new OcrBoundingBox(line.getText(), line.getBoundingBox()));
            }
        }
    }
}

