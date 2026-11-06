package com.ghostdocs.vault.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * LoginActivity — Firebase Authentication Screen
 *
 * <p>
 * Handles two sign-in flows and account creation:
 * <ol>
 * <li><b>Email/Password</b> via {@link FirebaseAuth}</li>
 * <li><b>Google Sign-In</b> via {@link GoogleSignInClient} + Firebase
 * credential</li>
 * <li><b>Register</b> (create new account) with the same email/password
 * fields</li>
 * </ol>
 *
 * <p>
 * The screen toggles between Sign-In and Register mode via a single button,
 * keeping the UI minimal. All async Firebase calls run on Firebase's default
 * thread pool; callbacks are delivered to the main thread automatically.
 *
 * <p>
 * <b>OOP Design:</b>
 * <ul>
 * <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 * <li><b>Encapsulation:</b> Firebase Auth state, Google client, and UI state
 * are all private fields — external classes cannot manipulate them.</li>
 * <li><b>Separation of concerns:</b> Input validation, Firebase calls, and
 * UI feedback are separated into distinct private methods.</li>
 * </ul>
 *
 * <p>
 * <b>Prerequisites:</b>
 * <ul>
 * <li>{@code google-services.json} must be in {@code app/} with Authentication
 * enabled (Email/Password + Google providers) in the Firebase console.</li>
 * <li>Your app's SHA-1 fingerprint must be registered in the Firebase console
 * for Google Sign-In to work.</li>
 * </ul>
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "GhostDocs.Login";

    // ── UI State ─────────────────────────────────────────────────────────────
    /** {@code true} = Sign In mode | {@code false} = Register mode */
    private boolean isSignInMode = true;

    // ── Views ────────────────────────────────────────────────────────────────
    private TextInputLayout tilEmail, tilPassword;
    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnPrimaryAction, btnGoogleSignIn;
    private TextView tvError, tvSectionLabel, tvToggleLabel, tvToggleAction;
    private ProgressBar progressBar;

    // ── Firebase & Google ────────────────────────────────────────────────────
    private FirebaseAuth firebaseAuth;
    private GoogleSignInClient googleSignInClient;

    // ── Google Sign-In Launcher (non-deprecated ActivityResult API) ───────────
    private final ActivityResultLauncher<Intent> googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleGoogleSignInResult);

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        bindViews();
        initFirebase();
        initGoogleSignIn();
        setClickListeners();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Initialisation
    // ════════════════════════════════════════════════════════════════════════

    /** Binds all XML view references to fields. */
    private void bindViews() {
        tilEmail = findViewById(R.id.til_email);
        tilPassword = findViewById(R.id.til_password);
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnPrimaryAction = findViewById(R.id.btn_primary_action);
        btnGoogleSignIn = findViewById(R.id.btn_google_sign_in);
        tvError = findViewById(R.id.tv_error);
        tvSectionLabel = findViewById(R.id.tv_section_label);
        tvToggleLabel = findViewById(R.id.tv_toggle_label);
        tvToggleAction = findViewById(R.id.tv_toggle_action);
        progressBar = findViewById(R.id.progress_bar);
    }

    /** Initialises the Firebase Auth singleton. */
    private void initFirebase() {
        firebaseAuth = FirebaseAuth.getInstance();
    }

    /**
     * Configures the {@link GoogleSignInClient}.
     *
     * <p>
     * {@code default_web_client_id} is auto-generated from
     * {@code google-services.json}
     * by the Google Services Gradle plugin — it is NOT a manual string resource.
     * It maps to the OAuth 2.0 Web Client ID in your Firebase project.
     */
    private void initGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    /** Wires all click listeners. */
    private void setClickListeners() {
        // Primary action: sign in OR register depending on current mode
        btnPrimaryAction.setOnClickListener(v -> {
            if (isSignInMode) {
                attemptEmailSignIn();
            } else {
                attemptRegister();
            }
        });

        // Google Sign-In
        btnGoogleSignIn.setOnClickListener(v -> launchGoogleSignIn());

        // Toggle between Sign-In ↔ Register mode
        tvToggleAction.setOnClickListener(v -> toggleMode());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Mode toggling (Sign-In ↔ Register)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Switches the UI between Sign-In mode and Register mode.
     * Updates button labels and clears any displayed errors.
     */
    private void toggleMode() {
        isSignInMode = !isSignInMode;
        hideError();
        clearInputErrors();

        if (isSignInMode) {
            tvSectionLabel.setText(getString(R.string.login_title));
            btnPrimaryAction.setText(getString(R.string.btn_login_email));
            tvToggleLabel.setText(getString(R.string.label_no_account));
            tvToggleAction.setText(getString(R.string.btn_register));
        } else {
            tvSectionLabel.setText(getString(R.string.btn_register));
            btnPrimaryAction.setText(getString(R.string.btn_register));
            tvToggleLabel.setText("Already have an account?");
            tvToggleAction.setText(getString(R.string.btn_login_email));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Email / Password Sign-In
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Validates inputs and calls {@link FirebaseAuth#signInWithEmailAndPassword}.
     * All heavy work happens on Firebase's internal thread pool; this method
     * returns immediately and results arrive via the success/failure listeners.
     */
    private void attemptEmailSignIn() {
        if (!validateInputs())
            return;

        String email = getEmailInput();
        String password = getPasswordInput();

        showLoading(true);

        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    showLoading(false);
                    Log.d(TAG, "Email sign-in successful: " + authResult.getUser().getEmail());
                    onAuthSuccess();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Log.w(TAG, "Email sign-in failed.", e);
                    showError(mapFirebaseAuthError(e));
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Email / Password Register
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Validates inputs and calls
     * {@link FirebaseAuth#createUserWithEmailAndPassword}.
     */
    private void attemptRegister() {
        if (!validateInputs())
            return;

        String email = getEmailInput();
        String password = getPasswordInput();

        showLoading(true);

        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    showLoading(false);
                    Log.d(TAG, "Registration successful: " + authResult.getUser().getEmail());
                    // Send verification email (non-blocking, best-effort)
                    sendVerificationEmail(authResult.getUser());
                    onAuthSuccess();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Log.w(TAG, "Registration failed.", e);
                    showError(mapFirebaseAuthError(e));
                });
    }

    /**
     * Sends an email verification link to the newly registered user.
     * Failure is intentionally swallowed — it is a best-effort operation.
     *
     * @param user the newly created Firebase user
     */
    private void sendVerificationEmail(FirebaseUser user) {
        if (user == null)
            return;
        user.sendEmailVerification()
                .addOnSuccessListener(unused -> Log.d(TAG, "Verification email sent."))
                .addOnFailureListener(e -> Log.w(TAG, "Failed to send verification email.", e));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Google Sign-In
    // ════════════════════════════════════════════════════════════════════════

    /** Launches the Google account picker intent. */
    private void launchGoogleSignIn() {
        showLoading(true);
        // Sign out from any previously cached Google account to force account picker
        googleSignInClient.signOut().addOnCompleteListener(task -> {
            Intent signInIntent = googleSignInClient.getSignInIntent();
            googleSignInLauncher.launch(signInIntent);
        });
    }

    /**
     * Handles the result from the Google account picker activity.
     * Extracts the ID token and exchanges it for a Firebase credential.
     *
     * @param result the activity result from the Google Sign-In launcher
     */
    private void handleGoogleSignInResult(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            showLoading(false);
            Log.w(TAG, "Google Sign-In cancelled or failed. Result code: "
                    + result.getResultCode());
            return;
        }

        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            Log.d(TAG, "Google sign-in account retrieved: " + account.getEmail());
            firebaseAuthWithGoogle(account.getIdToken());
        } catch (ApiException e) {
            showLoading(false);
            Log.w(TAG, "Google Sign-In ApiException: " + e.getStatusCode(), e);
            showError("Google Sign-In failed. Please try again.");
        }
    }

    /**
     * Exchanges the Google ID token for a Firebase {@link AuthCredential} and
     * signs the user in to Firebase Authentication.
     *
     * @param idToken the Google ID token from
     *                {@link GoogleSignInAccount#getIdToken()}
     */
    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);

        firebaseAuth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {
                    showLoading(false);
                    Log.d(TAG, "Firebase Google auth success: "
                            + authResult.getUser().getEmail());
                    onAuthSuccess();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Log.w(TAG, "Firebase Google auth failed.", e);
                    showError(getString(R.string.error_firebase));
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Post-authentication navigation
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Called after any successful authentication (email or Google).
     * Routes to {@link LaunchActivity} which will now trigger the BiometricPrompt
     * (since Firebase Auth state is now SIGNED_IN).
     */
    private void onAuthSuccess() {
        Toast.makeText(this, "Welcome to GhostDocs!", Toast.LENGTH_SHORT).show();
        // Go back through LaunchActivity so the biometric gate runs
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Input validation
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Validates email and password fields.
     * Sets inline TextInputLayout errors for specific fields.
     *
     * @return {@code true} if both fields are valid
     */
    private boolean validateInputs() {
        clearInputErrors();
        boolean valid = true;

        String email = getEmailInput();
        String password = getPasswordInput();

        if (TextUtils.isEmpty(email)) {
            tilEmail.setError("Email is required");
            valid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Enter a valid email address");
            valid = false;
        }

        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("Password is required");
            valid = false;
        } else if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            valid = false;
        }

        return valid;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Error mapping
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Maps Firebase Auth exceptions to human-readable, user-friendly messages.
     *
     * @param e the exception from Firebase Auth
     * @return a localised error string
     */
    private String mapFirebaseAuthError(Exception e) {
        if (e instanceof FirebaseAuthInvalidUserException) {
            return "No account found with this email. Please register.";
        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return "Incorrect email or password. Please try again.";
        } else if (e instanceof FirebaseAuthWeakPasswordException) {
            return "Password is too weak. Use at least 6 characters.";
        } else if (e instanceof FirebaseAuthUserCollisionException) {
            return "An account already exists with this email.";
        } else {
            return getString(R.string.error_firebase);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI helpers
    // ════════════════════════════════════════════════════════════════════════

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPrimaryAction.setEnabled(!loading);
        btnGoogleSignIn.setEnabled(!loading);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void clearInputErrors() {
        tilEmail.setError(null);
        tilPassword.setError(null);
    }

    private String getEmailInput() {
        return etEmail.getText() != null
                ? etEmail.getText().toString().trim()
                : "";
    }

    private String getPasswordInput() {
        return etPassword.getText() != null
                ? etPassword.getText().toString()
                : "";
    }
}