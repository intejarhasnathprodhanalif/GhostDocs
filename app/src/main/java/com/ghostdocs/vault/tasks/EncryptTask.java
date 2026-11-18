package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.security.AesEncryptionHelper;
import com.ghostdocs.vault.util.MediaDeleteHelper;

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
        private final boolean originalDeleted;
        @Nullable private final IntentSender recoverableIntentSender;
        private final long fileSize;

        public EncryptResult(@NonNull File encryptedFile, boolean originalDeleted) {
            this(encryptedFile, originalDeleted, null);
        }

        public EncryptResult(@NonNull File encryptedFile,
                             boolean originalDeleted,
                             @Nullable IntentSender recoverableIntentSender) {
            this.encryptedFile = encryptedFile;
            this.encryptedPath = encryptedFile.getAbsolutePath();
            this.originalDeleted = originalDeleted;
            this.recoverableIntentSender = recoverableIntentSender;
            this.fileSize = encryptedFile.length();
        }

        @NonNull public File getEncryptedFile() { return encryptedFile; }
        @NonNull public String getEncryptedPath() { return encryptedPath; }
        public boolean isOriginalDeleted() { return originalDeleted; }
        @Nullable public IntentSender getRecoverableIntentSender() { return recoverableIntentSender; }
        public long getFileSize() { return fileSize; }
    }

    private final Bitmap sourceBitmap;
    private final Uri originalMediaUri;
    private final String docId;
    private final boolean autoDeleteOriginal;

    public EncryptTask(@NonNull Context context,
                       @NonNull Bitmap bitmap,
                       @Nullable Uri originalMediaUri,
                       @NonNull String docId,
                       boolean autoDeleteOriginal,
                       @Nullable TaskCallback<EncryptResult> callback) {
        super(context, callback);
        this.sourceBitmap = bitmap;
        this.originalMediaUri = originalMediaUri;
        this.docId = docId;
        this.autoDeleteOriginal = autoDeleteOriginal;
    }

    @Override
    public EncryptResult doInBackground() throws Exception {
        Log.d(TAG, "Starting AES-256-GCM encryption for document: " + docId);

        // 1. Encrypt primary bitmap into internal vault directory
        File encryptedFile = AesEncryptionHelper.getInstance()
                .encryptBitmapToFile(context, sourceBitmap, docId);

        // 2. Auto-erase original file from MediaStore (Scoped Storage compliant)
        boolean deleted = false;
        IntentSender recoverableSender = null;
        if (autoDeleteOriginal && originalMediaUri != null) {
            MediaDeleteHelper.DeleteAttemptResult deleteResult =
                    MediaDeleteHelper.tryDirectDelete(context, originalMediaUri, null, 0);
            deleted = deleteResult.isDeleted();
            recoverableSender = deleteResult.getRecoverableIntentSender();
        }

        return new EncryptResult(encryptedFile, deleted, recoverableSender);
    }
}

