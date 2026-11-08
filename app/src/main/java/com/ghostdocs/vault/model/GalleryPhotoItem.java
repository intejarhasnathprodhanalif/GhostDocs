package com.ghostdocs.vault.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * GalleryPhotoItem — Model representing a pre-existing photo or screenshot in device storage.
 *
 * <p>Used by {@link com.ghostdocs.vault.activities.ImportPhotosActivity} and
 * {@link com.ghostdocs.vault.adapter.GalleryPhotoAdapter} for batch selection and vaulting.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> All fields are private, accessible only via standard getters and setters.</li>
 *   <li><b>Value Object:</b> Implements {@link #equals} and {@link #hashCode} based on {@link Uri}.</li>
 * </ul>
 */
public class GalleryPhotoItem {

    private final Uri uri;
    private final String displayName;
    private final String mimeType;
    private final long dateAdded;
    private final long size;
    private boolean selected;
    private boolean alreadyVaulted;
    private boolean suggested;
    private String suggestionReason;

    public GalleryPhotoItem(@NonNull Uri uri,
                            @NonNull String displayName,
                            @Nullable String mimeType,
                            long dateAdded,
                            long size,
                            boolean alreadyVaulted) {
        this.uri = uri;
        this.displayName = displayName;
        this.mimeType = mimeType != null ? mimeType : "image/jpeg";
        this.dateAdded = dateAdded;
        this.size = size;
        this.alreadyVaulted = alreadyVaulted;
        this.selected = false;
        this.suggested = false;
        this.suggestionReason = "";
    }

    @NonNull
    public Uri getUri() {
        return uri;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public String getMimeType() {
        return mimeType;
    }

    public long getDateAdded() {
        return dateAdded;
    }

    public long getSize() {
        return size;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isAlreadyVaulted() {
        return alreadyVaulted;
    }

    public void setAlreadyVaulted(boolean alreadyVaulted) {
        this.alreadyVaulted = alreadyVaulted;
    }

    public boolean isSuggested() {
        return suggested;
    }

    public void setSuggested(boolean suggested) {
        this.suggested = suggested;
    }

    @NonNull
    public String getSuggestionReason() {
        return suggestionReason != null ? suggestionReason : "";
    }

    public void setSuggestionReason(@Nullable String suggestionReason) {
        this.suggestionReason = suggestionReason != null ? suggestionReason : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GalleryPhotoItem that = (GalleryPhotoItem) o;
        return Objects.equals(uri, that.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }
}

