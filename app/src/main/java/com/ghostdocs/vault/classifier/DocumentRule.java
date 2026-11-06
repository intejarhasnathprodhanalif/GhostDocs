package com.ghostdocs.vault.classifier;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * DocumentRule — Abstraction for Classification Rules
 *
 * <p>Defines the contract for identifying document categories and extracting
 * relevant regex tags from raw OCR text.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Abstraction:</b> Callers interact with rules purely through this interface.</li>
 *   <li><b>Polymorphism:</b> Each concrete rule provides its own regex patterns and extraction logic.</li>
 * </ul>
 */
public interface DocumentRule {

    /**
     * Evaluates whether the OCR text matches this rule's criteria.
     *
     * @param text Raw extracted OCR text.
     * @return true if the document belongs to this category.
     */
    boolean matches(@NonNull String text);

    /**
     * Returns the standardized document type string (e.g. DocumentModel.TYPE_NID).
     */
    @NonNull
    String getDocType();

    /**
     * Extracts keyword tags and specific IDs (e.g. "TrxID: 9X3AB4KD", "NID: 1994...").
     *
     * @param text Raw extracted OCR text.
     * @return List of extracted keyword strings.
     */
    @NonNull
    List<String> extractTags(@NonNull String text);
}

