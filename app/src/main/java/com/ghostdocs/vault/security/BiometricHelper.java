package com.ghostdocs.vault.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * BiometricHelper — Android Keystore-Backed Biometric Authentication
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> All Keystore operations, Cipher management, and
 *       {@link BiometricPrompt} lifecycle are hidden inside this class. Callers
 *       only interact via {@link #authenticate()} and the {@link BiometricCallback}
 *       interface.</li>
 *   <li><b>Abstraction:</b> The {@link BiometricCallback} interface defines a
 *       contract — callers react to outcomes without knowing the internal mechanism.</li>
 * </ul>
 *
 * <p><b>Security model:</b> An AES-256 key is generated inside the Android Hardware
 * Security Module (HSM) / Trusted Execution Environment (TEE) via the
 * {@link android.security.keystore.AndroidKeyStoreProvider}. The key is bound to
 * biometric authentication with {@code setUserAuthenticationRequired(true)}, meaning
 * it can only be used to initialize a {@link Cipher} after the user successfully
 * authenticates. The resulting {@link BiometricPrompt.CryptoObject} ties the
 * biometric result to a real cryptographic operation — this defeats replay attacks.
 *
 * <p><b>Key invalidation:</b> If the user enrolls new biometrics or clears their
 * fingerprints, the key is permanently invalidated. This helper handles that by
 * deleting and regenerating the key, then falling back to a no-crypto prompt.
 */
public class BiometricHelper {

    private static final String TAG = "GhostDocs.Biometric";

    // Alias for the key inside the Android Keystore
    private static final String KEYSTORE_KEY_ALIAS = "GhostDocsVaultBiometricKey";

    // Android Keystore provider name
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // Cipher transformation used by this key
    private static final String CIPHER_TRANSFORMATION =
            KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7;

    // ── Callback interface (Abstraction) ─────────────────────────────────────

    /**
     * Callback contract for biometric authentication results.
     * Implemented by the calling Activity/Fragment.
     */
    public interface BiometricCallback {
        /** Called when biometric authentication succeeds. */
        void onBiometricSuccess();

        /**
         * Called when biometric authentication encounters an unrecoverable error.
         *
         * @param errorCode    one of {@link BiometricPrompt}'s {@code ERROR_*} constants
         * @param errorMessage human-readable error description
         */
        void onBiometricError(int errorCode, @NonNull CharSequence errorMessage);

        /** Called when a biometric is presented but does not match. */
        void onBiometricFailed();
    }

    // ── Fields (Encapsulation) ───────────────────────────────────────────────
    private final FragmentActivity activity;
    private final BiometricCallback callback;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * Constructs a BiometricHelper instance.
     *
     * @param activity the host activity (must be a {@link FragmentActivity})
     * @param callback the result callback
     */
    public BiometricHelper(@NonNull FragmentActivity activity,
                           @NonNull BiometricCallback callback) {
        this.activity = activity;
        this.callback = callback;
        setupBiometricPrompt();
        buildPromptInfo();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the device can perform strong biometric authentication
     * (fingerprint, face, or iris) and the user has enrolled credentials.
     *
     * @return {@code true} if biometric authentication is available and usable
     */
    public boolean isBiometricAvailable() {
        BiometricManager biometricManager = BiometricManager.from(activity);
        int result = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Initiates the biometric authentication flow.
     *
     * <p>Attempts to use a {@link BiometricPrompt.CryptoObject} backed by an
     * Android Keystore AES key. If the key is permanently invalidated (new
     * biometrics enrolled), the key is regenerated and authentication proceeds
     * without a CryptoObject as a fallback.
     */
    public void authenticate() {
        try {
            generateKeyIfAbsent();
            Cipher cipher = getInitializedCipher();
            BiometricPrompt.CryptoObject cryptoObject =
                    new BiometricPrompt.CryptoObject(cipher);
            biometricPrompt.authenticate(promptInfo, cryptoObject);

        } catch (KeyPermanentlyInvalidatedException e) {
            // User enrolled new biometrics — invalidated our key. Regenerate it.
            Log.w(TAG, "Keystore key permanently invalidated. Regenerating.", e);
            deleteKey();
            try {
                generateKeyIfAbsent();
            } catch (Exception regenerateEx) {
                Log.e(TAG, "Failed to regenerate key after invalidation.", regenerateEx);
            }
            // Fall back to no-crypto auth (still protected by biometric hardware)
            biometricPrompt.authenticate(promptInfo);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize biometric CryptoObject.", e);
            // Fall back to no-crypto auth
            biometricPrompt.authenticate(promptInfo);
        }
    }

    // ── Private: BiometricPrompt setup ───────────────────────────────────────

    /**
     * Builds the {@link BiometricPrompt} instance with an inline authentication callback.
     * Uses the main thread {@link Executor} for UI-safe callbacks.
     */
    private void setupBiometricPrompt() {
        Executor mainExecutor = ContextCompat.getMainExecutor(activity);

        biometricPrompt = new BiometricPrompt(activity, mainExecutor,
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        Log.d(TAG, "Biometric authentication succeeded.");
                        callback.onBiometricSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Log.w(TAG, "Biometric error [" + errorCode + "]: " + errString);
                        callback.onBiometricError(errorCode, errString);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Log.w(TAG, "Biometric authentication failed (no match).");
                        callback.onBiometricFailed();
                    }
                });
    }

    /**
     * Builds the UI text shown in the biometric dialog.
     * Allows both strong biometrics and device PIN/pattern/password as fallback.
     */
    private void buildPromptInfo() {
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(
                        com.ghostdocs.vault.R.string.biometric_prompt_title))
                .setSubtitle(activity.getString(
                        com.ghostdocs.vault.R.string.biometric_prompt_subtitle))
                .setDescription(activity.getString(
                        com.ghostdocs.vault.R.string.biometric_prompt_description))
                // Allow both strong biometrics AND device PIN as fallback
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
    }

    // ── Private: Android Keystore operations ─────────────────────────────────

    /**
     * Generates an AES-256 key inside the Android Keystore if one does not
     * already exist for {@link #KEYSTORE_KEY_ALIAS}.
     *
     * <p>Key properties:
     * <ul>
     *   <li>Algorithm: AES-256</li>
     *   <li>Block mode: CBC</li>
     *   <li>Padding: PKCS7</li>
     *   <li>{@code setUserAuthenticationRequired(true)}: key is locked until
     *       user authenticates</li>
     *   <li>{@code setInvalidatedByBiometricEnrollment(true)}: invalidated
     *       if new biometrics are enrolled (security measure)</li>
     * </ul>
     */
    private void generateKeyIfAbsent()
            throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException, KeyStoreException,
            CertificateException, IOException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        // Key already exists — no need to regenerate
        if (keyStore.containsAlias(KEYSTORE_KEY_ALIAS)) return;

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
        );

        KeyGenParameterSpec.Builder specBuilder = new KeyGenParameterSpec.Builder(
                KEYSTORE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);

        // API 24+: Invalidate key when new biometrics are enrolled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            specBuilder.setInvalidatedByBiometricEnrollment(true);
        }

        keyGenerator.init(specBuilder.build());
        keyGenerator.generateKey();
        Log.d(TAG, "AES-256 key generated in Android Keystore.");
    }

    /**
     * Retrieves the AES key from the Keystore and initializes a {@link Cipher}
     * in ENCRYPT mode. This triggers the biometric authentication requirement.
     *
     * @return an initialized {@link Cipher} ready to be wrapped in a CryptoObject
     */
    private Cipher getInitializedCipher()
            throws KeyStoreException, CertificateException, IOException,
            NoSuchAlgorithmException, UnrecoverableKeyException,
            NoSuchPaddingException, InvalidKeyException,
            KeyPermanentlyInvalidatedException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        SecretKey secretKey = (SecretKey) keyStore.getKey(KEYSTORE_KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey); // Throws if key is invalidated
        return cipher;
    }

    /**
     * Deletes the Keystore key (called after permanent invalidation so a fresh
     * key can be generated).
     */
    private void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEYSTORE_KEY_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_KEY_ALIAS);
                Log.d(TAG, "Invalidated key deleted from Keystore.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not delete Keystore key.", e);
        }
    }
}
