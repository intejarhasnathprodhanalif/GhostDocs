package com.ghostdocs.vault.activities;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.lifecycle.Lifecycle;

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
 *   <li>On biometric BACK/CANCEL → display requirement message and keep prompt active.</li>
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
    private static final int REPROMPT_DELAY_MS = 150;

    // ── Views ────────────────────────────────────────────────────────────────
    private ProgressBar progressIndicator;

    // ── Auth & Biometric ─────────────────────────────────────────────────────
    private FirebaseAuth firebaseAuth;
    private BiometricHelper biometricHelper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean authGateTriggered = false;
    private boolean isAuthenticating = false;
    private boolean isAuthenticated = false;
    private boolean isForeground = false;
    private Toast activeToast;

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

        // Intercept system back button on LaunchActivity so the auth gate cannot be bypassed or dismissed
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showAuthToast(getString(R.string.error_auth_required));
                if (authGateTriggered && !isAuthenticated && !isAuthenticating) {
                    triggerBiometricGate();
                }
            }
        });

        // Dynamic launch entrance animations
        View icon = findViewById(R.id.tv_ghost_icon);
        View appName = findViewById(R.id.tv_app_name);
        View tagline = findViewById(R.id.tv_tagline);
        View badge = findViewById(R.id.tv_security_badge);

        if (icon != null) {
            icon.setAlpha(0f);
            icon.setScaleX(0.6f);
            icon.setScaleY(0.6f);
            icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550)
                    .setInterpolator(new OvershootInterpolator(1.8f))
                    .withEndAction(() -> {
                        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(
                                icon,
                                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.07f),
                                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.07f)
                        );
                        pulse.setDuration(800);
                        pulse.setRepeatMode(ValueAnimator.REVERSE);
                        pulse.setRepeatCount(ValueAnimator.INFINITE);
                        pulse.start();
                    }).start();
        }
        if (appName != null) {
            appName.setAlpha(0f);
            appName.setTranslationY(30f);
            appName.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(150).start();
        }
        if (tagline != null) {
            tagline.setAlpha(0f);
            tagline.setTranslationY(20f);
            tagline.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(250).start();
        }
        if (badge != null) {
            badge.setAlpha(0f);
            badge.setTranslationY(25f);
            badge.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(350).start();
        }

        // Brief splash delay, then evaluate auth state
        mainHandler.postDelayed(this::checkAuthAndRoute, SPLASH_DELAY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        // Re-present biometric gate if returning to foreground while still locked
        if (authGateTriggered && !isAuthenticated && !isAuthenticating) {
            mainHandler.postDelayed(() -> {
                if (isForeground && !isAuthenticated && !isAuthenticating) {
                    triggerBiometricGate();
                }
            }, REPROMPT_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (activeToast != null) {
            activeToast.cancel();
        }
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
        if (isFinishing() || isDestroyed()) return;

        FirebaseUser currentUser = firebaseAuth.getCurrentUser();

        if (currentUser != null) {
            Log.d(TAG, "User authenticated as: " + currentUser.getEmail());
            authGateTriggered = true;
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
        if (isFinishing() || isDestroyed() || isAuthenticated || isAuthenticating) return;

        progressIndicator.setVisibility(View.GONE);

        if (biometricHelper.isBiometricAvailable()) {
            isAuthenticating = true;
            biometricHelper.authenticate();
        } else {
            // Device has no biometric hardware or no enrolled credentials.
            // Log and fall through — Firebase Auth is still the security gate.
            Log.w(TAG, "Biometric not available. Skipping to vault.");
            showAuthToast(getString(R.string.error_biometric_unavailable));
            goToVault();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // BiometricHelper callback (Polymorphism via interface)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds the {@link BiometricHelper.BiometricCallback} that reacts to
     * the biometric prompt outcome.
     */
    private BiometricHelper.BiometricCallback buildBiometricCallback() {
        return new BiometricHelper.BiometricCallback() {

            @Override
            public void onBiometricSuccess() {
                Log.d(TAG, "Biometric gate passed. Entering vault.");
                isAuthenticating = false;
                isAuthenticated = true;
                if (activeToast != null) {
                    activeToast.cancel();
                }
                goToVault();
            }

            @Override
            public void onBiometricError(int errorCode, CharSequence errorMessage) {
                Log.w(TAG, "Biometric error [" + errorCode + "]: " + errorMessage);
                isAuthenticating = false;
                progressIndicator.setVisibility(View.GONE);

                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // User tapped Back (<) or attempted to dismiss the prompt.
                    // Show a clear requirement message and immediately re-open the prompt
                    // so the authentication gate cannot be dismissed while in the app.
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                        showAuthToast(getString(R.string.error_auth_required));
                        scheduleReprompt();
                    }
                } else if (errorCode == BiometricPrompt.ERROR_CANCELED) {
                    // System canceled (e.g. screen rotation or brief interruption).
                    // Re-prompt only if still foreground.
                    scheduleReprompt();
                } else {
                    // Lockout or unrecoverable hardware error — show system message
                    showAuthToast(errorMessage != null ? errorMessage.toString()
                            : getString(R.string.error_auth_failed));
                }
            }

            @Override
            public void onBiometricFailed() {
                // Wrong fingerprint/face — the prompt handles retries internally
                Log.w(TAG, "Biometric did not match. Prompt will allow retry.");
            }
        };
    }

    /**
     * Re-presents the biometric/PIN authentication prompt after a brief delay
     * so the OS finishes dismissing the canceled prompt dialog before re-opening.
     */
    private void scheduleReprompt() {
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && isForeground && !isAuthenticated && !isAuthenticating) {
                triggerBiometricGate();
            }
        }, REPROMPT_DELAY_MS);
    }

    private void showAuthToast(String message) {
        if (activeToast != null) {
            activeToast.cancel();
        }
        activeToast = Toast.makeText(LaunchActivity.this, message, Toast.LENGTH_SHORT);
        activeToast.show();
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
