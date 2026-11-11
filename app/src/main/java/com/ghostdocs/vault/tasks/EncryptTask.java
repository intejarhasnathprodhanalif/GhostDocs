package com.ghostdocs.vault.tasks;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.security.AesEncryptionHelper;

import java.io.File;

/**
 * EncryptTask — AES-256-GCM Encryption & MediaStore Scoped Storage Deletion
 *
 * <p>Executes JPEG compression, AES-256-GCM encryption with Android KeyStore keys,
 * writes the resulting cipher to app-private storage, and securely erases the original
 * media from Android's MediaStore.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link BaseVaultTask}.</li>
 *   <li><b>Polymorphism:</b> Overrides {@link #doInBackground()} with cryptographic and Scoped Storage logic.</li>
 *   <li><b>Encapsulation:</b> Cryptographic results and deletion status are held in {@link EncryptResult}.</li>
 * </ul>
 */
public class EncryptTask extends BaseVaultTask<EncryptTask.EncryptResult> {

    private static final String TAG = "GhostDocs.EncryptTask";

    // ── Encapsulated Result Object ───────────────────────────────────────────
    public static class EncryptResult {
        private final File encryptedFile;
        private final String encryptedPath;
        @Nullable private final String originalEncryptedPath;
        private final boolean originalDeleted;
        private final long fileSize;

        public EncryptResult(@NonNull File encryptedFile, boolean originalDeleted) {
            this(encryptedFile, null, originalDeleted);
        }

        public EncryptResult(@NonNull File encryptedFile,
                             @Nullable String originalEncryptedPath,
                             boolean originalDeleted) {
            this.encryptedFile = encryptedFile;
            this.encryptedPath = encryptedFile.getAbsolutePath();
            this.originalEncryptedPath = originalEncryptedPath;
            this.originalDeleted = originalDeleted;
            this.fileSize = encryptedFile.length();
        }

        @NonNull public File getEncryptedFile() { return encryptedFile; }
        @NonNull public String getEncryptedPath() { return encryptedPath; }
        @Nullable public String getOriginalEncryptedPath() { return originalEncryptedPath; }
        public boolean isOriginalDeleted() { return originalDeleted; }
        public long getFileSize() { return fileSize; }
    }

    private final Bitmap sourceBitmap;
    @Nullable private final Bitmap originalBitmap;
    private final Uri originalMediaUri;
    private final String docId;
    private final boolean autoDeleteOriginal;

    public EncryptTask(@NonNull Context context,
                       @NonNull Bitmap bitmap,
                       @Nullable Uri originalMediaUri,
                       @NonNull String docId,
                       boolean autoDeleteOriginal,
                       @Nullable TaskCallback<EncryptResult> callback) {
        this(context, bitmap, null, originalMediaUri, docId, autoDeleteOriginal, callback);
    }

    public EncryptTask(@NonNull Context context,
                       @NonNull Bitmap bitmap,
                       @Nullable Bitmap originalBitmap,
                       @Nullable Uri originalMediaUri,
                       @NonNull String docId,
                       boolean autoDeleteOriginal,
                       @Nullable TaskCallback<EncryptResult> callback) {
        super(context, callback);
        this.sourceBitmap = bitmap;
        this.originalBitmap = originalBitmap;
        this.originalMediaUri = originalMediaUri;
        this.docId = docId;
        this.autoDeleteOriginal = autoDeleteOriginal;
    }

    @Override
    public EncryptResult doInBackground() throws Exception {
        Log.d(TAG, "Starting AES-256-GCM encryption for document: " + docId);

        // 1. Encrypt primary (cropped/flattened) bitmap into internal vault directory
        File encryptedFile = AesEncryptionHelper.getInstance()
                .encryptBitmapToFile(context, sourceBitmap, docId);

        // 2. If image was cropped, also encrypt the uncropped full original bitmap
        String origEncPath = null;
        if (originalBitmap != null && !originalBitmap.isRecycled() && originalBitmap != sourceBitmap) {
            File origFile = AesEncryptionHelper.getInstance()
                    .encryptBitmapToFile(context, originalBitmap, docId + "_orig");
            origEncPath = origFile.getAbsolutePath();
            Log.d(TAG, "Encrypted uncropped original backup: " + origEncPath);
        }

        // 3. Auto-erase original file from MediaStore (Scoped Storage compliant)
        boolean deleted = false;
        if (autoDeleteOriginal && originalMediaUri != null) {
            deleted = deleteOriginalMedia(originalMediaUri);
        }

        return new EncryptResult(encryptedFile, origEncPath, deleted);
    }

    /**
     * Attempts to delete the original image from Android's MediaStore.
     * Complies with Scoped Storage restrictions across Android 10 to 14+.
     */
    private boolean deleteOriginalMedia(@NonNull Uri mediaUri) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return false;

        try {
            int rowsDeleted = resolver.delete(mediaUri, null, null);
            if (rowsDeleted > 0) {
                Log.i(TAG, "Original MediaStore photo deleted successfully: " + mediaUri);
                return true;
            } else {
                Log.w(TAG, "ContentResolver returned 0 rows deleted for URI: " + mediaUri);
            }
        } catch (SecurityException se) {
            // Android 10+ (Q) throws RecoverableSecurityException if file was created by another app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se instanceof RecoverableSecurityException) {
                Log.w(TAG, "RecoverableSecurityException on Scoped Storage deletion for: " + mediaUri
                        + ". File is protected by another app's UID.");
            } else {
                Log.e(TAG, "SecurityException while deleting MediaStore original: " + mediaUri, se);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete original MediaStore image: " + mediaUri, e);
        }

        return false;
    }
}

