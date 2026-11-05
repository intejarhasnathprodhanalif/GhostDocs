package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * LaunchActivity — Application Entry Point & Auth Gate
 *
 * <p>This is the MAIN/LAUNCHER activity declared in AndroidManifest.xml.
 * It is the first screen Android instantiates on app launch.
 *
 * <p><b>Current behavior (Step 1 stub):</b> Checks Firebase Auth state after
 * a brief splash delay, then routes to the appropriate screen.
 *
 * <p><b>Full implementation (Step 2):</b> Will integrate BiometricPrompt backed
 * by the Android Keystore before allowing vault access.
 *
 * <p>OOP Note: Extends {@link AppCompatActivity} (Inheritance). Auth-checking
 * logic will be extracted into a dedicated helper class (Encapsulation) in Step 2.
 */
public class LaunchActivity extends AppCompatActivity {

    // Splash display duration before routing (ms)
    private static final int SPLASH_DELAY_MS = 1500;

    private FirebaseAuth firebaseAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        // Initialize Firebase Auth instance
        firebaseAuth = FirebaseAuth.getInstance();

        // After splash delay, check auth state and route accordingly
        new Handler(Looper.getMainLooper()).postDelayed(
                this::checkAuthAndRoute,
                SPLASH_DELAY_MS
        );
    }

    /**
     * Checks the current Firebase Authentication state.
     *
     * <ul>
     *   <li>If a user is already signed in → route to {@link VaultActivity}
     *       (Step 2 will intercept this with BiometricPrompt first).</li>
     *   <li>If no user is signed in → route to {@link LoginActivity}.</li>
     * </ul>
     */
    private void checkAuthAndRoute() {
        FirebaseUser currentUser = firebaseAuth.getCurrentUser();

        if (currentUser != null) {
            // User is authenticated — go to vault
            // TODO (Step 2): Insert BiometricPrompt challenge here before routing
            startActivity(new Intent(this, VaultActivity.class));
        } else {
            // No authenticated user — go to login
            startActivity(new Intent(this, LoginActivity.class));
        }

        // Prevent user from pressing Back to return to splash screen
        finish();
    }
}
