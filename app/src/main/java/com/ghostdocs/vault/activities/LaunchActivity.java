package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.security.BiometricHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * LaunchActivity — Application Entry Point & Biometric Auth Gate
 *
 * <p>This is the MAIN/LAUNCHER activity declared in AndroidManifest.xml.
 * It is the ONLY entry point into the app — users cannot reach {@link VaultActivity}
 * without passing through this gate.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>Display splash screen with 1.2-second branding delay.</li>
 *   <li>Check {@link FirebaseAuth} state via {@code getCurrentUser()}.</li>
 *   <li>If NOT authenticated → route to {@link LoginActivity}.</li>
 *   <li>If authenticated → invoke {@link BiometricHelper#authenticate()}.</li>
 *   <li>On biometric SUCCESS → route to {@link VaultActivity} and {@code finish()}.</li>
 *   <li>On biometric ERROR/CANCEL → show error; stay on splash (user can retry).</li>
 * </ol>
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 *   <li><b>Encapsulation:</b> All biometric logic is delegated to
 *       {@link BiometricHelper}; this Activity only reacts to the callback.</li>
 *   <li><b>Polymorphism:</b> Implements {@link BiometricHelper.BiometricCallback}
 *       inline — the specific callback type is resolved at runtime.</li>
 * </ul>
 */
public class LaunchActivity extends AppCompatActivity {

    private static final String TAG = "GhostDocs.Launch";
    private static final int SPLASH_DELAY_MS = 1200;

    // ── Views ────────────────────────────────────────────────────────────────
    private ProgressBar progressIndicator;

    // ── Auth & Biometric ─────────────────────────────────────────────────────
    private FirebaseAuth firebaseAuth;
    private BiometricHelper biometricHelper;

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        progressIndicator = findViewById(R.id.progress_indicator);
        firebaseAuth = FirebaseAuth.getInstance();

        // Initialise BiometricHelper with this Activity and our inline callback
        biometricHelper = new BiometricHelper(this, buildBiometricCallback());

        // Brief splash delay, then evaluate auth state
        new Handler(Looper.getMainLooper()).postDelayed(
                this::checkAuthAndRoute,
                SPLASH_DELAY_MS
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // Auth routing
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates the Firebase Auth state and routes accordingly.
     *
     * <ul>
     *   <li>Logged in → trigger biometric gate.</li>
     *   <li>Not logged in → go to {@link LoginActivity}.</li>
     * </ul>
     */
    private void checkAuthAndRoute() {
        FirebaseUser currentUser = firebaseAuth.getCurrentUser();

        if (currentUser != null) {
            Log.d(TAG, "User authenticated as: " + currentUser.getEmail());
            triggerBiometricGate();
        } else {
            Log.d(TAG, "No authenticated user. Routing to LoginActivity.");
            goToLogin();
        }
    }

    /**
     * Shows the biometric prompt if hardware/enrollment is available.
     * If biometric is not available on this device, skips directly to vault
     * (the Keystore key is the actual security layer; biometric is a UX gate).
     */
    private void triggerBiometricGate() {
        progressIndicator.setVisibility(View.GONE);

        if (biometricHelper.isBiometricAvailable()) {
            biometricHelper.authenticate();
        } else {
            // Device has no biometric hardware or no enrolled credentials.
            // Log and fall through — Firebase Auth is still the security gate.
            Log.w(TAG, "Biometric not available. Skipping to vault.");
            Toast.makeText(this,
                    getString(R.string.error_biometric_unavailable),
                    Toast.LENGTH_LONG).show();
            goToVault();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // BiometricHelper callback (Polymorphism via interface)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds the {@link BiometricHelper.BiometricCallback} that reacts to
     * the biometric prompt outcome.
     *
     * <p>This anonymous class implementation demonstrates Polymorphism — the
     * concrete behaviour is resolved at runtime through the
     * {@link BiometricHelper.BiometricCallback} interface.
     *
     * @return the callback instance
     */
    private BiometricHelper.BiometricCallback buildBiometricCallback() {
        return new BiometricHelper.BiometricCallback() {

            @Override
            public void onBiometricSuccess() {
                Log.d(TAG, "Biometric gate passed. Entering vault.");
                goToVault();
            }

            @Override
            public void onBiometricError(int errorCode, CharSequence errorMessage) {
                // User cancelled or hit an unrecoverable error
                Log.w(TAG, "Biometric error [" + errorCode + "]: " + errorMessage);

                if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // User deliberately cancelled — stay on splash, let them retry or exit
                    progressIndicator.setVisibility(View.VISIBLE);
                    Toast.makeText(LaunchActivity.this,
                            getString(R.string.error_auth_failed),
                            Toast.LENGTH_SHORT).show();
                } else {
                    // Unrecoverable hardware/lockout error
                    Toast.makeText(LaunchActivity.this,
                            errorMessage, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onBiometricFailed() {
                // Wrong fingerprint/face — the prompt handles retries internally;
                // we just log it here
                Log.w(TAG, "Biometric did not match. Prompt will allow retry.");
            }
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // Navigation
    // ════════════════════════════════════════════════════════════════════════

    private void goToVault() {
        startActivity(new Intent(this, VaultActivity.class));
        finish(); // Remove LaunchActivity from the back stack
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
