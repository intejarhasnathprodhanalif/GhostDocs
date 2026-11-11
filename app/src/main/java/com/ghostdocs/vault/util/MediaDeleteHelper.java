package com.ghostdocs.vault.util;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MediaDeleteHelper — Scoped Storage URI Resolution & Safe Gallery Deletion
 *
 * <p>Resolves picker/document URIs (such as Android 13+ {@code media/picker/...} or
 * {@code com.android.providers.media.documents} URIs) to canonical writable
 * {@link MediaStore} URIs so batch deletion via {@link MediaStore#createDeleteRequest}
 * works reliably instead of failing silently.
 */
public final class MediaDeleteHelper {

    private static final String TAG = "GhostDocs.MediaDelete";

    private MediaDeleteHelper() {
        throw new UnsupportedOperationException("MediaDeleteHelper is a static utility class.");
    }

    public static class DeleteAttemptResult {
        private final boolean deleted;
        @Nullable private final Uri canonicalMediaUri;
        @Nullable private final IntentSender recoverableIntentSender;

        public DeleteAttemptResult(boolean deleted,
                                   @Nullable Uri canonicalMediaUri,
                                   @Nullable IntentSender recoverableIntentSender) {
            this.deleted = deleted;
            this.canonicalMediaUri = canonicalMediaUri;
            this.recoverableIntentSender = recoverableIntentSender;
        }

        public boolean isDeleted() {
            return deleted;
        }

        @Nullable
        public Uri getCanonicalMediaUri() {
            return canonicalMediaUri;
        }

        @Nullable
        public IntentSender getRecoverableIntentSender() {
            return recoverableIntentSender;
        }
    }

    /**
     * Returns true if {@code uri} is a canonical MediaStore media item URI
     * accepted by {@link MediaStore#createDeleteRequest}.
     */
    public static boolean isCanonicalMediaStoreUri(@Nullable Uri uri) {
        if (uri == null) return false;
        if (!"content".equalsIgnoreCase(uri.getScheme())) return false;
        if (!"media".equalsIgnoreCase(uri.getAuthority())) return false;
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/picker")) return false;
        return lower.contains("/images/media/")
                || lower.contains("/video/media/")
                || lower.contains("/audio/media/");
    }

    /**
     * Filters and deduplicates a list of URIs to only canonical MediaStore media URIs
     * valid for {@link MediaStore#createDeleteRequest}.
     */
    @NonNull
    public static List<Uri> filterCanonicalMediaUris(@NonNull List<Uri> uris) {
        Set<Uri> unique = new LinkedHashSet<>();
        for (Uri u : uris) {
            if (isCanonicalMediaStoreUri(u)) {
                unique.add(u);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Resolves a gallery/picker/documents URI to its underlying canonical {@link MediaStore}
     * media URI whenever possible, or returns null if it cannot be resolved.
     */
    @Nullable
    public static Uri resolveDeletableMediaUri(@NonNull Context context,
                                                @NonNull Uri sourceUri,
                                                @Nullable String displayNameHint,
                                                long sizeHint) {
        if (isCanonicalMediaStoreUri(sourceUri)) {
            return sourceUri;
        }

        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return null;

        // 1. DocumentsProvider URI: content://com.android.providers.media.documents/document/image%3A12345
        try {
            if (DocumentsContract.isDocumentUri(context, sourceUri)
                    && "com.android.providers.media.documents".equalsIgnoreCase(sourceUri.getAuthority())) {
                String docId = DocumentsContract.getDocumentId(sourceUri);
                if (docId != null && docId.contains(":")) {
                    String[] parts = docId.split(":", 2);
                    String type = parts[0].toLowerCase(Locale.ROOT);
                    long mediaId = Long.parseLong(parts[1]);
                    if ("image".equals(type)) {
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId);
                    } else if ("video".equals(type)) {
                        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                    } else if ("audio".equals(type)) {
                        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "media.documents URI parse skipped for " + sourceUri + ": " + e.getMessage());
        }

        // 2. Android 13+ Photo Picker URI: content://media/picker/.../media/<id>
        String authority = sourceUri.getAuthority();
        String path = sourceUri.getPath();
        if ("media".equalsIgnoreCase(authority) && path != null && path.toLowerCase(Locale.ROOT).contains("/picker")) {
            String lastSegment = sourceUri.getLastPathSegment();
            if (lastSegment != null) {
                try {
                    long pickerMediaId = Long.parseLong(lastSegment);
                    Uri candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pickerMediaId);
                    if (mediaRowExists(resolver, candidate)) {
                        return candidate;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 3. Generic fallback (vendor gallery pickers / MediaStore.Files / etc.):
        // Look up matching row in MediaStore.Images.Media by DISPLAY_NAME + SIZE
        String name = displayNameHint;
        long size = sizeHint;
        if (name == null || name.trim().isEmpty() || size <= 0) {
            try (Cursor meta = resolver.query(sourceUri, null, null, null, null)) {
                if (meta != null && meta.moveToFirst()) {
                    if (name == null || name.trim().isEmpty()) {
                        int nameIdx = meta.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIdx != -1) name = meta.getString(nameIdx);
                    }
                    if (size <= 0) {
                        int sizeIdx = meta.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeIdx != -1) size = meta.getLong(sizeIdx);
                    }
                }
            } catch (Exception ignored) {}
        }

        if (name != null && !name.trim().isEmpty()) {
            Uri matched = findImageByNameAndSize(resolver, name.trim(), size);
            if (matched != null) {
                return matched;
            }
        }

        return null;
    }

    /**
     * Attempts to delete the original media item directly. If Scoped Storage blocks direct
     * deletion, returns the canonical MediaStore URI (for API 30+ {@code createDeleteRequest})
     * and/or the Android 10 {@link RecoverableSecurityException} {@link IntentSender}.
     */
    @NonNull
    public static DeleteAttemptResult tryDirectDelete(@NonNull Context context,
                                                      @NonNull Uri sourceUri,
                                                      @Nullable String displayName,
                                                      long size) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return new DeleteAttemptResult(false, null, null);
        }

        Uri canonicalUri = resolveDeletableMediaUri(context, sourceUri, displayName, size);
        Uri targetUri = canonicalUri != null ? canonicalUri : sourceUri;
        IntentSender recoverableSender = null;

        // First try canonical MediaStore URI (or original URI)
        try {
            int rows = resolver.delete(targetUri, null, null);
            if (rows > 0) {
                Log.i(TAG, "Original media deleted directly: " + targetUri);
                return new DeleteAttemptResult(true, canonicalUri, null);
            }
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se instanceof RecoverableSecurityException) {
                RecoverableSecurityException rse = (RecoverableSecurityException) se;
                recoverableSender = rse.getUserAction().getActionIntent().getIntentSender();
                Log.w(TAG, "RecoverableSecurityException for: " + targetUri);
            } else {
                Log.w(TAG, "SecurityException deleting " + targetUri + ": " + se.getMessage());
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct delete failed on " + targetUri + ": " + e.getMessage());
        }

        // If canonical differed from sourceUri and wasn't deleted, try deleting sourceUri as well
        if (canonicalUri != null && !canonicalUri.equals(sourceUri)) {
            try {
                int rows = resolver.delete(sourceUri, null, null);
                if (rows > 0) {
                    Log.i(TAG, "Original source URI deleted directly: " + sourceUri);
                    return new DeleteAttemptResult(true, canonicalUri, null);
                }
            } catch (Exception ignored) {}
        }

        return new DeleteAttemptResult(false, canonicalUri, recoverableSender);
    }

    private static boolean mediaRowExists(@NonNull ContentResolver resolver, @NonNull Uri candidate) {
        try (Cursor c = resolver.query(
                candidate,
                new String[]{ MediaStore.Images.Media._ID },
                null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static Uri findImageByNameAndSize(@NonNull ContentResolver resolver,
                                              @NonNull String displayName,
                                              long size) {
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.SIZE
        };
        String selection;
        String[] args;
        if (size > 0) {
            selection = MediaStore.Images.Media.DISPLAY_NAME + " = ? AND " + MediaStore.Images.Media.SIZE + " = ?";
            args = new String[]{ displayName, String.valueOf(size) };
        } else {
            selection = MediaStore.Images.Media.DISPLAY_NAME + " = ?";
            args = new String[]{ displayName };
        }
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor c = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore lookup by DISPLAY_NAME/SIZE failed: " + e.getMessage());
        }
        return null;
    }
}
