package com.ghostdocs.vault.security;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AesEncryptionHelper — AES-256-GCM Hardware-Backed Encryption Helper
 *
 * <p>Implements authenticated encryption with associated data (AEAD) using
 * AES-256 in Galois/Counter Mode (GCM) with a 128-bit authentication tag.
 * The encryption key is generated and stored securely in the hardware-backed
 * {@code AndroidKeyStore}.
 *
 * <p><b>Encrypted File Binary Layout:</b>
 * <pre>
 * +------------------------+---------------------------------------+
 * | 12-byte GCM IV (Nonce) | Ciphertext + 16-byte GCM Auth Tag     |
 * +------------------------+---------------------------------------+
 * </pre>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> Keystore alias, cipher configuration, GCM nonce generation,
 *       and byte streaming are completely private.</li>
 * </ul>
 */
public final class AesEncryptionHelper {

    private static final String TAG = "GhostDocs.Crypto";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "GhostDocsStorageKey_AES256";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int JPEG_COMPRESSION_QUALITY = 90;

    private static volatile AesEncryptionHelper instance;

    private AesEncryptionHelper() {
        initKeyStoreKey();
    }

    /**
     * Singleton accessor for the encryption helper.
     */
    public static AesEncryptionHelper getInstance() {
        if (instance == null) {
            synchronized (AesEncryptionHelper.class) {
                if (instance == null) {
                    instance = new AesEncryptionHelper();
                }
            }
        }
        return instance;
    }

    /**
     * Ensures an AES-256 key exists in the hardware-backed Android KeyStore.
     */
    private void initKeyStoreKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                );

                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(false) // We supply our own SecureRandom IV
                        .build();

                keyGenerator.init(spec);
                keyGenerator.generateKey();
                Log.i(TAG, "Hardware AES-256 key successfully generated in Android KeyStore.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Android KeyStore AES key", e);
        }
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    /**
     * Compresses a Bitmap to JPEG, encrypts it using AES-256-GCM, and writes the
     * encrypted bytes to the target destination file in app-private storage.
     *
     * @param context Application context.
     * @param bitmap  Source bitmap to compress and encrypt.
     * @param docId   Unique document identifier for naming the file.
     * @return The encrypted {@link File} object.
     * @throws Exception If compression, encryption, or file I/O fails.
     */
    @NonNull
    public File encryptBitmapToFile(@NonNull Context context,
                                    @NonNull Bitmap bitmap,
                                    @NonNull String docId) throws Exception {

        // 1. Compress Bitmap to JPEG byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, baos);
        byte[] plaintextBytes = baos.toByteArray();
        baos.close();

        // 2. Generate random 12-byte IV
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        // 3. Encrypt via AES-256-GCM
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), parameterSpec);

        byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

        // 4. Ensure internal vault directory exists: context.getFilesDir() / vault
        File vaultDir = new File(context.getFilesDir(), "vault");
        if (!vaultDir.exists()) {
            boolean created = vaultDir.mkdirs();
            Log.d(TAG, "Vault directory created: " + created);
        }

        File encryptedFile = new File(vaultDir, docId + ".enc");

        // 5. Write IV followed by Ciphertext + Tag
        try (FileOutputStream fos = new FileOutputStream(encryptedFile)) {
            fos.write(iv);
            fos.write(ciphertextWithTag);
            fos.flush();
        }

        Log.i(TAG, "Encrypted file written: " + encryptedFile.getAbsolutePath()
                + " (Size: " + encryptedFile.length() + " bytes)");

        return encryptedFile;
    }

    /**
     * Decrypts an AES-256-GCM encrypted file and decodes it back into a {@link Bitmap}.
     *
     * @param encryptedFile The .enc file containing [12-byte IV][Ciphertext + Tag].
     * @return Decrypted {@link Bitmap}.
     * @throws Exception If decryption fails or file is tampered with (GCM auth tag mismatch).
     */
    @Nullable
    public Bitmap decryptFileToBitmap(@NonNull File encryptedFile) throws Exception {
        byte[] decryptedBytes = decryptFileToBytes(encryptedFile);
        if (decryptedBytes == null || decryptedBytes.length == 0) {
            return null;
        }
        return BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length);
    }

    /**
     * Decrypts an AES-256-GCM encrypted file and returns the raw decrypted bytes.
     *
     * @param encryptedFile The encrypted file.
     * @return Decrypted byte array.
     */
    @NonNull
    public byte[] decryptFileToBytes(@NonNull File encryptedFile) throws Exception {
        if (!encryptedFile.exists()) {
            throw new IOException("Encrypted file does not exist: " + encryptedFile.getAbsolutePath());
        }

        long fileLength = encryptedFile.length();
        if (fileLength < GCM_IV_LENGTH_BYTES) {
            throw new IOException("Corrupt encrypted file (smaller than IV header): " + encryptedFile.getName());
        }

        byte[] fileBytes = new byte[(int) fileLength];
        try (FileInputStream fis = new FileInputStream(encryptedFile)) {
            int read = fis.read(fileBytes);
            if (read != fileLength) {
                throw new IOException("Failed to read entire encrypted file into memory.");
            }
        }

        // Extract 12-byte IV
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        System.arraycopy(fileBytes, 0, iv, 0, GCM_IV_LENGTH_BYTES);

        // Extract ciphertext + tag
        int ciphertextLength = fileBytes.length - GCM_IV_LENGTH_BYTES;
        byte[] ciphertext = new byte[ciphertextLength];
        System.arraycopy(fileBytes, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertextLength);

        // Decrypt
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), parameterSpec);

        return cipher.doFinal(ciphertext);
    }
}
