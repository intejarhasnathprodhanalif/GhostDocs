package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.classifier.RegexClassifierEngine;
import com.ghostdocs.vault.ocr.OcrBoundingBox;
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

    private final Uri imageUri;
    private final String fallbackTitle;
    private final RegexClassifierEngine classifierEngine;

    public OcrTask(@NonNull Context context,
                   @NonNull Uri imageUri,
                   @NonNull String fallbackTitle,
                   @Nullable TaskCallback<OcrResult> callback) {
        super(context, callback);
        this.imageUri = imageUri;
        this.fallbackTitle = fallbackTitle;
        this.classifierEngine = new RegexClassifierEngine();
    }

    @Override
    protected OcrResult doInBackground() throws Exception {
        Log.d(TAG, "Starting on-device ML Kit OCR for URI: " + imageUri);

        // 1. Build ML Kit InputImage from file path
        InputImage inputImage = InputImage.fromFilePath(context, imageUri);
        int width = inputImage.getWidth();
        int height = inputImage.getHeight();

        // 2. Initialize ML Kit Text Recognizer
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        Text visionText;
        try {
            // Await synchronously on worker thread (safe: we are inside VaultExecutor)
            visionText = Tasks.await(recognizer.process(inputImage));
        } finally {
            recognizer.close(); // Prevent resource leaks
        }

        String rawText = visionText != null ? visionText.getText() : "";
        Log.i(TAG, "OCR recognition completed. Extracted length: " + rawText.length() + " characters.");

        // 3. Extract bounding boxes for all text blocks & lines (crucial for Step 8 PDF overlay)
        List<OcrBoundingBox> boundingBoxes = new ArrayList<>();
        if (visionText != null) {
            for (Text.TextBlock block : visionText.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    boundingBoxes.add(new OcrBoundingBox(line.getText(), line.getBoundingBox()));
                }
            }
        }

        // 4. Run Custom Regex Classification Engine & format org.json.JSONObject
        RegexClassifierEngine.ClassificationResult classification =
                classifierEngine.classify(rawText, fallbackTitle);

        Log.i(TAG, "Document classified as: " + classification.getDocType()
                + " | Title: " + classification.getTitle()
                + " | Tags: " + classification.getTags());

        return new OcrResult(rawText, classification, boundingBoxes, width, height);
    }
}

