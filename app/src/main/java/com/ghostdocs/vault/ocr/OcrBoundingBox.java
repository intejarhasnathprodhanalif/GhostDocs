package com.ghostdocs.vault.ocr;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * OcrBoundingBox — Encapsulates an OCR Text Element with Coordinates
 *
 * <p>Stores the exact bounding box and text line extracted by Google ML Kit.
 * This information is used both for regex keyword matching and for rendering
 * transparent searchable text in Step 8's PDF generator.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> Private fields with immutable getters.</li>
 * </ul>
 */
public class OcrBoundingBox {

    private final String text;
    private final Rect boundingBox;

    public OcrBoundingBox(@NonNull String text, @Nullable Rect boundingBox) {
        this.text = text;
        this.boundingBox = boundingBox != null ? new Rect(boundingBox) : new Rect();
    }

    @NonNull
    public String getText() {
        return text;
    }

    @NonNull
    public Rect getBoundingBox() {
        return new Rect(boundingBox);
    }
}

