package com.ghostdocs.vault.util;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MediaDeleteHelper — Scoped Storage URI Resolution & Safe Gallery / PDF Deletion
 *
 * <p>Resolves picker/document URIs (such as Android 13+ {@code media/picker/...},
 * {@code com.android.providers.media.documents}, {@code com.android.externalstorage.documents},
 * and {@code com.android.providers.downloads.documents}) to canonical {@link MediaStore} URIs
 * and filesystem paths so deletion works reliably for both images and PDFs.
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
     * accepted by {@link MediaStore#createDeleteRequest} (Images, Video, or Audio).
     * Note: {@code MediaStore.Files} (/file/) URIs are NOT accepted by createDeleteRequest.
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
     * Returns true if {@code uri} points to a MediaStore.Files / Downloads collection row.
     */
    public static boolean isMediaStoreFileUri(@Nullable Uri uri) {
        if (uri == null) return false;
        if (!"content".equalsIgnoreCase(uri.getScheme())) return false;
        if (!"media".equalsIgnoreCase(uri.getAuthority())) return false;
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/file/") || lower.contains("/downloads/");
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
     * Resolves a gallery/picker/documents URI to its underlying {@link MediaStore}
     * URI whenever possible (media URI for images/video/audio, or Files URI for PDFs),
     * or returns null if it cannot be resolved.
     */
    @Nullable
    public static Uri resolveDeletableMediaUri(@NonNull Context context,
                                                @NonNull Uri sourceUri,
                                                @Nullable String displayNameHint,
                                                long sizeHint) {
        if (isCanonicalMediaStoreUri(sourceUri) || isMediaStoreFileUri(sourceUri)) {
            return sourceUri;
        }

        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return null;

        String authority = sourceUri.getAuthority();

        // 1. DocumentsProvider URI: com.android.providers.media.documents
        try {
            if (DocumentsContract.isDocumentUri(context, sourceUri)
                    && "com.android.providers.media.documents".equalsIgnoreCase(authority)) {
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
                    } else if ("document".equals(type)) {
                        return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "media.documents URI parse skipped for " + sourceUri + ": " + e.getMessage());
        }

        // 2. DownloadsProvider msf:<id> URI (Android 10+ downloads documents)
        try {
            if (DocumentsContract.isDocumentUri(context, sourceUri)
                    && "com.android.providers.downloads.documents".equalsIgnoreCase(authority)) {
                String docId = DocumentsContract.getDocumentId(sourceUri);
                if (docId != null && docId.startsWith("msf:")) {
                    long mediaId = Long.parseLong(docId.substring(4));
                    Uri candidate = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId);
                    if (rowExists(resolver, candidate, MediaStore.Files.FileColumns._ID)) {
                        return candidate;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "downloads.documents msf URI parse skipped: " + e.getMessage());
        }

        // 3. Android 13+ Photo Picker URI: content://media/picker/.../media/<id>
        String path = sourceUri.getPath();
        if ("media".equalsIgnoreCase(authority) && path != null && path.toLowerCase(Locale.ROOT).contains("/picker")) {
            String lastSegment = sourceUri.getLastPathSegment();
            if (lastSegment != null) {
                try {
                    long pickerMediaId = Long.parseLong(lastSegment);
                    Uri candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pickerMediaId);
                    if (rowExists(resolver, candidate, MediaStore.Images.Media._ID)) {
                        return candidate;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 4. Filesystem path lookup in MediaStore.Files (handles externalstorage & downloads raw: URIs)
        String directPath = resolveDirectDocumentPath(context, sourceUri);
        if (directPath != null) {
            Uri byPath = findFileUriByDataPath(resolver, directPath);
            if (byPath != null) {
                return byPath;
            }
        }

        // 5. Fallback lookup by DISPLAY_NAME + SIZE
        String[] meta = readDisplayNameAndSize(resolver, sourceUri, displayNameHint, sizeHint);
        String name = meta[0];
        long size = 0;
        try {
            size = Long.parseLong(meta[1]);
        } catch (NumberFormatException ignored) {}

        if (name != null && !name.isEmpty()) {
            boolean looksPdf = name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    || "application/pdf".equalsIgnoreCase(resolver.getType(sourceUri));
            if (!looksPdf) {
                Uri matchedImage = findUriByNameAndSize(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, size);
                if (matchedImage != null) {
                    return matchedImage;
                }
            }
            Uri matchedFile = findUriByNameAndSize(resolver, MediaStore.Files.getContentUri("external"), name, size);
            if (matchedFile != null) {
                return matchedFile;
            }
        }

        return null;
    }

    /**
     * Attempts to delete the original image or PDF.
     *
     * <ol>
     *   <li>If {@code sourceUri} is a deletable SAF document URI (e.g. from OpenMultipleDocuments),
     *       calls {@link DocumentsContract#deleteDocument}.</li>
     *   <li>If All Files Access ({@code MANAGE_EXTERNAL_STORAGE}) is granted (or API &lt; 30),
     *       deletes the backing file directly via {@link File#delete()} and removes/rescans MediaStore.</li>
     *   <li>Falls back to {@link ContentResolver#delete} and collects canonical media URIs or
     *       {@link RecoverableSecurityException} senders for Scoped Storage user consent.</li>
     * </ol>
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

        String[] meta = readDisplayNameAndSize(resolver, sourceUri, displayName, size);
        String resolvedName = meta[0];
        long resolvedSize = 0;
        try {
            resolvedSize = Long.parseLong(meta[1]);
        } catch (NumberFormatException ignored) {}

        Uri resolvedUri = resolveDeletableMediaUri(context, sourceUri, resolvedName, resolvedSize);
        Uri canonicalMediaUri = isCanonicalMediaStoreUri(resolvedUri) ? resolvedUri : null;
        String filePath = resolveFilesystemPath(context, sourceUri, resolvedUri, resolvedName, resolvedSize);
        IntentSender recoverableSender = null;

        // 1. Try SAF DocumentsContract.deleteDocument first (works for user-picked PDFs without All Files Access)
        try {
            if (DocumentsContract.isDocumentUri(context, sourceUri)) {
                boolean docDeleted = DocumentsContract.deleteDocument(resolver, sourceUri);
                if (docDeleted) {
                    Log.i(TAG, "Original deleted via DocumentsContract.deleteDocument: " + sourceUri);
                    cleanupMediaStoreRowQuietly(resolver, resolvedUri);
                    if (filePath != null && !filePath.trim().isEmpty()) {
                        MediaScannerConnection.scanFile(context.getApplicationContext(),
                                new String[]{ filePath }, null, null);
                    }
                    return new DeleteAttemptResult(true, canonicalMediaUri, null);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "DocumentsContract.deleteDocument skipped for " + sourceUri + ": " + e.getMessage());
        }

        // 2. If All Files Access is granted (or below Android 11), delete backing file directly on disk
        if (PermissionHelper.hasManageStoragePermission(context)) {
            if (filePath != null && !filePath.trim().isEmpty()) {
                try {
                    File targetFile = new File(filePath);
                    boolean removed = !targetFile.exists() || targetFile.delete();
                    if (removed) {
                        Log.i(TAG, "Original deleted directly from filesystem: " + filePath);
                        cleanupMediaStoreRowQuietly(resolver, resolvedUri != null ? resolvedUri : sourceUri);
                        MediaScannerConnection.scanFile(context.getApplicationContext(),
                                new String[]{ targetFile.getAbsolutePath() }, null, null);
                        return new DeleteAttemptResult(true, canonicalMediaUri, null);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Direct filesystem delete failed for " + filePath + ": " + e.getMessage());
                }
            }
        }

        // 3. Try ContentResolver.delete on resolved MediaStore URI (works for owned rows & legacy storage)
        Uri targetUri = resolvedUri != null ? resolvedUri : sourceUri;
        try {
            int rows = resolver.delete(targetUri, null, null);
            if (rows > 0) {
                Log.i(TAG, "Original media deleted via ContentResolver: " + targetUri);
                return new DeleteAttemptResult(true, canonicalMediaUri, null);
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
            Log.w(TAG, "Direct ContentResolver delete failed on " + targetUri + ": " + e.getMessage());
        }

        // 4. Also attempt deleting sourceUri directly if it differed from targetUri
        if (!targetUri.equals(sourceUri)) {
            try {
                int rows = resolver.delete(sourceUri, null, null);
                if (rows > 0) {
                    Log.i(TAG, "Original source URI deleted via ContentResolver: " + sourceUri);
                    return new DeleteAttemptResult(true, canonicalMediaUri, null);
                }
            } catch (Exception ignored) {}
        }

        return new DeleteAttemptResult(false, canonicalMediaUri, recoverableSender);
    }

    /**
     * Resolves an underlying filesystem path for a URI (file://, externalstorage.documents,
     * downloads.documents raw:, or MediaStore _data column).
     */
    @Nullable
    public static String resolveFilesystemPath(@NonNull Context context,
                                               @NonNull Uri sourceUri,
                                               @Nullable Uri resolvedMediaUri,
                                               @Nullable String displayName,
                                               long size) {
        String directPath = resolveDirectDocumentPath(context, sourceUri);
        if (directPath != null) {
            return directPath;
        }

        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return null;

        String fromResolved = queryDataPath(resolver, resolvedMediaUri);
        if (fromResolved != null) return fromResolved;

        String fromSource = queryDataPath(resolver, sourceUri);
        if (fromSource != null) return fromSource;

        if (displayName != null && !displayName.trim().isEmpty()) {
            Uri fileUri = findUriByNameAndSize(
                    resolver, MediaStore.Files.getContentUri("external"), displayName.trim(), size);
            if (fileUri != null) {
                return queryDataPath(resolver, fileUri);
            }
        }
        return null;
    }

    @Nullable
    private static String resolveDirectDocumentPath(@NonNull Context context, @NonNull Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        try {
            if (!DocumentsContract.isDocumentUri(context, uri)) {
                return null;
            }
            String authority = uri.getAuthority();
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId == null) return null;

            if ("com.android.externalstorage.documents".equalsIgnoreCase(authority)) {
                String[] parts = docId.split(":", 2);
                if (parts.length == 2 && "primary".equalsIgnoreCase(parts[0])) {
                    return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + parts[1];
                }
            } else if ("com.android.providers.downloads.documents".equalsIgnoreCase(authority)) {
                if (docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static String queryDataPath(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) return null;
        try (Cursor c = resolver.query(
                uri,
                new String[]{ MediaStore.MediaColumns.DATA },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (idx != -1) {
                    String path = c.getString(idx);
                    if (path != null && !path.trim().isEmpty()) {
                        return path;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static Uri findFileUriByDataPath(@NonNull ContentResolver resolver, @NonNull String filePath) {
        Uri filesUri = MediaStore.Files.getContentUri("external");
        try (Cursor c = resolver.query(
                filesUri,
                new String[]{ MediaStore.Files.FileColumns._ID },
                MediaStore.MediaColumns.DATA + " = ?",
                new String[]{ filePath },
                null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID));
                return ContentUris.withAppendedId(filesUri, id);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @NonNull
    private static String[] readDisplayNameAndSize(@NonNull ContentResolver resolver,
                                                   @NonNull Uri sourceUri,
                                                   @Nullable String nameHint,
                                                   long sizeHint) {
        String name = nameHint != null ? nameHint.trim() : null;
        long size = sizeHint;
        if (name == null || name.isEmpty() || size <= 0) {
            try (Cursor meta = resolver.query(sourceUri, null, null, null, null)) {
                if (meta != null && meta.moveToFirst()) {
                    if (name == null || name.isEmpty()) {
                        int nameIdx = meta.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIdx != -1) {
                            String rawName = meta.getString(nameIdx);
                            if (rawName != null) name = rawName.trim();
                        }
                    }
                    if (size <= 0) {
                        int sizeIdx = meta.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeIdx != -1) size = meta.getLong(sizeIdx);
                    }
                }
            } catch (Exception ignored) {}
        }
        return new String[]{ name != null ? name : "", String.valueOf(size) };
    }

    private static void cleanupMediaStoreRowQuietly(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        if (uri == null) return;
        try {
            resolver.delete(uri, null, null);
        } catch (Exception ignored) {}
    }

    private static boolean rowExists(@NonNull ContentResolver resolver,
                                     @NonNull Uri candidate,
                                     @NonNull String idColumn) {
        try (Cursor c = resolver.query(
                candidate,
                new String[]{ idColumn },
                null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static Uri findUriByNameAndSize(@NonNull ContentResolver resolver,
                                            @NonNull Uri collectionUri,
                                            @NonNull String displayName,
                                            long size) {
        String[] projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE
        };
        String selection;
        String[] args;
        if (size > 0) {
            selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " + MediaStore.MediaColumns.SIZE + " = ?";
            args = new String[]{ displayName, String.valueOf(size) };
        } else {
            selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
            args = new String[]{ displayName };
        }
        String sortOrder = MediaStore.MediaColumns.DATE_ADDED + " DESC";

        try (Cursor c = resolver.query(
                collectionUri,
                projection,
                selection,
                args,
                sortOrder)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(collectionUri, id);
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore lookup by DISPLAY_NAME/SIZE failed: " + e.getMessage());
        }
        return null;
    }
}
