package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.NetworkHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.FirebaseUserMetadata;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ProfileActivity — User Profile & Security Management Screen
 *
 * <p>Provides full control over user identity, credentials, and vault stats:
 * <ul>
 *   <li><b>Display Name / Username:</b> Updates {@link FirebaseUser#updateProfile(UserProfileChangeRequest)}
 *       and syncs to Cloud Firestore under {@code users/{uid}}.</li>
 *   <li><b>Password Management:</b> Re-authenticates using {@link EmailAuthProvider#getCredential(String, String)},
 *       validates password security requirements, and updates credentials via {@link FirebaseUser#updatePassword(String)}.
 *       Also provides a one-click password reset email option.</li>
 *   <li><b>OAuth Awareness:</b> Detects Google OAuth accounts vs. native email accounts to adapt security controls.</li>
 *   <li><b>Vault Overview:</b> Displays verification status, member-since timestamp, hardware AES-256 standard,
 *       and real-time secured document counts.</li>
 *   <li><b>Account Lifecycle:</b> Offers secure sign-out and permanent vault + account deletion with cascade cleanup.</li>
 * </ul>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> Firebase Auth and Firestore calls, input validation, and layout bindings
 *       are isolated in well-defined private methods.</li>
 *   <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 * </ul>
 */
public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "GhostDocs.Profile";

    // ── Header Views ─────────────────────────────────────────────────────────
    private TextView tvAvatarIcon;
    private ImageView ivProfilePicture;
    private View layoutAvatarContainer;
    private View btnChangePhoto;
    private TextView tvProfileName;
    private TextView tvProfileEmail;
    private TextView tvEmailStatus;
    private TextView tvMemberSince;

    // ── Photo Picker Launcher ────────────────────────────────────────────────
    private final ActivityResultLauncher<String> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handleCustomPhotoSelected(uri);
                }
            });

    // ── Edit Name Views ──────────────────────────────────────────────────────
    private TextInputLayout tilUsername;
    private TextInputEditText etUsername;
    private MaterialButton btnSaveUsername;

    // ── Security & Password Views ────────────────────────────────────────────
    private RelativeLayout layoutPasswordFields;
    private RelativeLayout layoutGoogleAccount;
    private TextInputLayout tilCurrentPassword;
    private TextInputEditText etCurrentPassword;
    private TextInputLayout tilNewPassword;
    private TextInputEditText etNewPassword;
    private TextInputLayout tilConfirmPassword;
    private TextInputEditText etConfirmPassword;
    private MaterialButton btnUpdatePassword;
    private MaterialButton btnSendResetEmail;

    // ── Vault Stats Views ────────────────────────────────────────────────────
    private TextView tvStatDocsCount;
    private TextView tvUserId;

    // ── Account Action Views ─────────────────────────────────────────────────
    private MaterialButton btnProfileSignOut;
    private MaterialButton btnDeleteAccount;

    // ── Loading Overlay Views ────────────────────────────────────────────────
    private RelativeLayout layoutProgressOverlay;
    private ProgressBar pbProfileLoading;
    private TextView tvLoadingText;

    // ── Firebase References ──────────────────────────────────────────────────
    private FirebaseAuth firebaseAuth;
    private FirebaseFirestore firestore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        firebaseAuth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();

        bindViews();
        setupClickListeners();
        loadUserProfile();
        loadVaultStats();
        animateProfileCards();
    }

    private void animateProfileCards() {
        int[] cardIds = new int[] {
                R.id.card_profile_header,
                R.id.card_edit_name,
                R.id.card_security,
                R.id.card_vault_stats,
                R.id.card_account_actions
        };
        long delay = 60;
        for (int id : cardIds) {
            View card = findViewById(id);
            if (card != null) {
                card.setAlpha(0f);
                card.setTranslationY(30f);
                card.animate().alpha(1f).translationY(0f).setDuration(380).setStartDelay(delay).start();
                delay += 80;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh auth state in case user verified email externally
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user != null) {
            user.reload().addOnCompleteListener(task -> {
                if (!isFinishing() && !isDestroyed()) {
                    loadUserProfile();
                }
            });
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // View Binding & Click Listeners
    // ════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        // Toolbar
        findViewById(R.id.btn_back_profile).setOnClickListener(v -> finish());

        // Header
        tvAvatarIcon          = findViewById(R.id.tv_avatar_icon);
        ivProfilePicture      = findViewById(R.id.iv_profile_picture);
        layoutAvatarContainer = findViewById(R.id.layout_avatar_container);
        btnChangePhoto        = findViewById(R.id.btn_change_photo);
        tvProfileName         = findViewById(R.id.tv_profile_name);
        tvProfileEmail        = findViewById(R.id.tv_profile_email);
        tvEmailStatus         = findViewById(R.id.tv_email_status);
        tvMemberSince         = findViewById(R.id.tv_member_since);

        // Edit Name
        tilUsername     = findViewById(R.id.til_username);
        etUsername      = findViewById(R.id.et_username);
        btnSaveUsername = findViewById(R.id.btn_save_username);

        // Security / Password
        layoutPasswordFields = findViewById(R.id.layout_password_fields);
        layoutGoogleAccount  = findViewById(R.id.layout_google_account);
        tilCurrentPassword  = findViewById(R.id.til_current_password);
        etCurrentPassword   = findViewById(R.id.et_current_password);
        tilNewPassword      = findViewById(R.id.til_new_password);
        etNewPassword       = findViewById(R.id.et_new_password);
        tilConfirmPassword  = findViewById(R.id.til_confirm_password);
        etConfirmPassword   = findViewById(R.id.et_confirm_password);
        btnUpdatePassword   = findViewById(R.id.btn_update_password);
        btnSendResetEmail   = findViewById(R.id.btn_send_reset_email);

        // Stats
        tvStatDocsCount     = findViewById(R.id.tv_stat_docs_count);
        tvUserId            = findViewById(R.id.tv_user_id);

        // Account Actions
        btnProfileSignOut   = findViewById(R.id.btn_profile_sign_out);
        btnDeleteAccount    = findViewById(R.id.btn_delete_account);

        // Loading Overlay
        layoutProgressOverlay = findViewById(R.id.layout_progress_overlay);
        pbProfileLoading      = findViewById(R.id.pb_profile_loading);
        tvLoadingText         = findViewById(R.id.tv_loading_text);
    }

    private void setupClickListeners() {
        if (layoutAvatarContainer != null) {
            layoutAvatarContainer.setOnClickListener(v -> promptChangeProfilePhoto());
        }
        if (btnChangePhoto != null) {
            btnChangePhoto.setOnClickListener(v -> promptChangeProfilePhoto());
        }
        btnSaveUsername.setOnClickListener(v -> handleSaveUsername());
        btnUpdatePassword.setOnClickListener(v -> handleUpdatePassword());
        btnSendResetEmail.setOnClickListener(v -> handleSendResetEmail());
        btnProfileSignOut.setOnClickListener(v -> confirmSignOut());
        btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());

        // UID click listener to copy or view full UID
        tvUserId.setOnClickListener(v -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {
                new AlertDialog.Builder(this)
                        .setTitle("Account UID")
                        .setMessage(user.getUid())
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Profile Data Loading & Formatting
    // ════════════════════════════════════════════════════════════════════════

    private void loadUserProfile() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            redirectToLogin();
            return;
        }

        // 1. Display Name
        String name = user.getDisplayName();
        if (TextUtils.isEmpty(name)) {
            name = "Ghost Vault User";
        }
        tvProfileName.setText(name);
        etUsername.setText(user.getDisplayName() != null ? user.getDisplayName() : "");
        loadProfilePicture(user, name);

        // 2. Email
        String email = user.getEmail();
        if (TextUtils.isEmpty(email)) {
            email = "No email registered";
        }
        tvProfileEmail.setText(email);

        // 3. Verification Badge
        if (user.isEmailVerified()) {
            tvEmailStatus.setText("Email Verified");
            tvEmailStatus.setBackgroundResource(R.drawable.bg_badge_verified);
            tvEmailStatus.setTextColor(getResources().getColor(R.color.ghostSuccess));
            tvEmailStatus.setOnClickListener(null);
        } else {
            tvEmailStatus.setText("Unverified (Tap to verify)");
            tvEmailStatus.setBackgroundResource(R.drawable.bg_badge_unverified);
            tvEmailStatus.setTextColor(getResources().getColor(R.color.ghostWarning));
            tvEmailStatus.setOnClickListener(v -> handleSendVerificationEmail());
        }

        // 4. Member Since
        FirebaseUserMetadata metadata = user.getMetadata();
        if (metadata != null && metadata.getCreationTimestamp() > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
            String dateStr = sdf.format(new Date(metadata.getCreationTimestamp()));
            tvMemberSince.setText("Member since " + dateStr + " • Hardware AES-256 Active");
        } else {
            tvMemberSince.setText("Account Active • Hardware AES-256 Active");
        }

        // 5. Account UID
        String uid = user.getUid();
        if (uid.length() > 14) {
            tvUserId.setText(uid.substring(0, 10) + "..." + uid.substring(uid.length() - 4));
        } else {
            tvUserId.setText(uid);
        }

        // 6. Provider Verification (Google OAuth vs Password)
        checkProviderType(user);
    }

    /**
     * Determines whether the user signed in through Google OAuth or standard Password.
     */
    private void checkProviderType(@NonNull FirebaseUser user) {
        boolean hasPasswordProvider = false;
        boolean hasGoogleProvider   = false;

        for (UserInfo userInfo : user.getProviderData()) {
            String providerId = userInfo.getProviderId();
            if (EmailAuthProvider.PROVIDER_ID.equals(providerId)) {
                hasPasswordProvider = true;
            } else if (GoogleAuthProvider.PROVIDER_ID.equals(providerId) || "google.com".equals(providerId)) {
                hasGoogleProvider = true;
            }
        }

        if (hasGoogleProvider && !hasPasswordProvider) {
            layoutPasswordFields.setVisibility(View.GONE);
            layoutGoogleAccount.setVisibility(View.VISIBLE);
        } else {
            layoutPasswordFields.setVisibility(View.VISIBLE);
            layoutGoogleAccount.setVisibility(View.GONE);
        }
    }

    /**
     * Sets avatar text to uppercase initials of user name or email.
     */
    private void updateAvatarInitials(@NonNull String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || "Ghost Vault User".equalsIgnoreCase(trimmed)) {
            tvAvatarIcon.setText("GD");
            return;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            String initials = ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
            tvAvatarIcon.setText(initials);
        } else {
            String initial = ("" + trimmed.charAt(0)).toUpperCase(Locale.ROOT);
            tvAvatarIcon.setText(initial);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Profile Picture Loading & Management
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Loads the profile picture according to priority:
     * 1. Locally set custom profile picture (picked from gallery).
     * 2. Google Account profile picture (if signed in via Google).
     * 3. Default initials fallback badge ("GD" or initials).
     */
    private void loadProfilePicture(@NonNull FirebaseUser user, @NonNull String name) {
        File customPhotoFile = new File(getFilesDir(), "profile_" + user.getUid() + ".jpg");
        if (customPhotoFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(customPhotoFile.getAbsolutePath());
            if (bitmap != null) {
                displayProfileBitmap(bitmap);
                return;
            }
        }

        // Check if user has a Google photo URL
        Uri photoUri = user.getPhotoUrl();
        if (photoUri == null) {
            for (UserInfo info : user.getProviderData()) {
                if (info.getPhotoUrl() != null) {
                    photoUri = info.getPhotoUrl();
                    break;
                }
            }
        }

        if (photoUri != null) {
            File googlePhotoFile = new File(getFilesDir(), "google_photo_" + user.getUid() + ".jpg");
            if (googlePhotoFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(googlePhotoFile.getAbsolutePath());
                if (bitmap != null) {
                    displayProfileBitmap(bitmap);
                    return;
                }
            }

            // Fetch Google profile photo asynchronously
            final String urlString = photoUri.toString();
            VaultExecutor.getInstance().execute(() -> {
                try {
                    java.net.URL url = new java.net.URL(urlString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setDoInput(true);
                    conn.connect();
                    InputStream in = conn.getInputStream();
                    Bitmap downloaded = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();

                    if (downloaded != null) {
                        saveBitmapToFile(downloaded, googlePhotoFile);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                displayProfileBitmap(downloaded);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to download Google profile photo", e);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            showDefaultAvatar(name);
                        }
                    });
                }
            });
        } else {
            showDefaultAvatar(name);
        }
    }

    private void displayProfileBitmap(@NonNull Bitmap bitmap) {
        if (ivProfilePicture != null) {
            ivProfilePicture.setImageBitmap(bitmap);
            ivProfilePicture.setVisibility(View.VISIBLE);
        }
        if (tvAvatarIcon != null) {
            tvAvatarIcon.setVisibility(View.GONE);
        }
    }

    private void showDefaultAvatar(@NonNull String name) {
        if (ivProfilePicture != null) {
            ivProfilePicture.setVisibility(View.GONE);
        }
        if (tvAvatarIcon != null) {
            tvAvatarIcon.setVisibility(View.VISIBLE);
            updateAvatarInitials(name);
        }
    }

    /**
     * Prompts the user with a dialog to change, reset, or remove their profile picture.
     */
    private void promptChangeProfilePhoto() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        File customPhotoFile = new File(getFilesDir(), "profile_" + user.getUid() + ".jpg");
        boolean hasCustom = customPhotoFile.exists();
        boolean hasGoogle = (user.getPhotoUrl() != null);

        List<String> options = new ArrayList<>();
        options.add("Choose from Gallery");
        if (hasCustom && hasGoogle) {
            options.add("Use Google Account Photo");
        }
        if (hasCustom || (ivProfilePicture != null && ivProfilePicture.getVisibility() == View.VISIBLE)) {
            options.add("Remove Photo");
        }

        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Profile Picture")
                .setItems(items, (dialog, which) -> {
                    String selected = items[which];
                    if ("Choose from Gallery".equals(selected)) {
                        photoPickerLauncher.launch("image/*");
                    } else if ("Use Google Account Photo".equals(selected)) {
                        if (customPhotoFile.exists()) {
                            customPhotoFile.delete();
                        }
                        loadUserProfile();
                        Toast.makeText(this, "Reverted to Google Account photo", Toast.LENGTH_SHORT).show();
                    } else if ("Remove Photo".equals(selected)) {
                        removeProfilePhoto(user);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Persists the chosen custom image into internal app storage and updates UI.
     */
    private void handleCustomPhotoSelected(@NonNull Uri imageUri) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        showLoading(true);
        VaultExecutor.getInstance().execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(imageUri);
                Bitmap raw = BitmapFactory.decodeStream(in);
                if (in != null) in.close();

                if (raw == null) {
                    runOnUiThread(() -> {
                        hideLoading();
                        Toast.makeText(this, "Unable to read selected image.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                Bitmap scaled = scaleBitmapDown(raw, 512);
                File customPhotoFile = new File(getFilesDir(), "profile_" + user.getUid() + ".jpg");
                saveBitmapToFile(scaled, customPhotoFile);

                runOnUiThread(() -> {
                    hideLoading();
                    displayProfileBitmap(scaled);
                    Toast.makeText(this, "Profile picture updated.", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error saving custom profile picture", e);
                runOnUiThread(() -> {
                    hideLoading();
                    Toast.makeText(this, "Failed to set profile picture: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void removeProfilePhoto(@NonNull FirebaseUser user) {
        File customPhotoFile = new File(getFilesDir(), "profile_" + user.getUid() + ".jpg");
        if (customPhotoFile.exists()) {
            customPhotoFile.delete();
        }
        File googlePhotoFile = new File(getFilesDir(), "google_photo_" + user.getUid() + ".jpg");
        if (googlePhotoFile.exists()) {
            googlePhotoFile.delete();
        }

        String name = user.getDisplayName();
        showDefaultAvatar(name != null ? name : "Ghost Vault User");
        Toast.makeText(this, "Profile picture removed.", Toast.LENGTH_SHORT).show();
    }

    private void saveBitmapToFile(@NonNull Bitmap bitmap, @NonNull File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
        }
    }

    private Bitmap scaleBitmapDown(@NonNull Bitmap bitmap, int maxDimension) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap;
        }
        float ratio = Math.min((float) maxDimension / width, (float) maxDimension / height);
        int newWidth = Math.round(width * ratio);
        int newHeight = Math.round(height * ratio);
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    /**
     * Queries Firestore asynchronously to display the total count of secured files.
     */
    private void loadVaultStats() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        firestore.collection("users")
                .document(user.getUid())
                .collection("documents")
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (isFinishing() || isDestroyed()) return;
                    int count = snapshots.size();
                    tvStatDocsCount.setText(count + (count == 1 ? " document" : " documents"));
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvStatDocsCount.setText("Secured");
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Feature 1: Update Display Name / Username
    // ════════════════════════════════════════════════════════════════════════

    private void handleSaveUsername() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            redirectToLogin();
            return;
        }

        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "update your username",
                    null
            );
            return;
        }

        String newName = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
        if (TextUtils.isEmpty(newName)) {
            tilUsername.setError("Please enter a username or display name");
            return;
        }
        tilUsername.setError(null);

        showLoading("Saving username...");

        // 1. Update Firebase Auth Profile
        UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                .setDisplayName(newName)
                .build();

        user.updateProfile(profileUpdates).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                // 2. Sync to Firestore users collection
                Map<String, Object> userData = new HashMap<>();
                userData.put("displayName", newName);
                userData.put("updatedAt", FieldValue.serverTimestamp());

                firestore.collection("users")
                        .document(user.getUid())
                        .set(userData, SetOptions.merge())
                        .addOnCompleteListener(firestoreTask -> {
                            hideLoading();
                            tvProfileName.setText(newName);
                            updateAvatarInitials(newName);
                            Toast.makeText(ProfileActivity.this,
                                    "Username updated successfully!",
                                    Toast.LENGTH_SHORT).show();
                        });
            } else {
                hideLoading();
                String error = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                Toast.makeText(ProfileActivity.this,
                        "Failed to update username: " + error,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Feature 2: Change Password & Reset Email
    // ════════════════════════════════════════════════════════════════════════

    private void handleUpdatePassword() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            redirectToLogin();
            return;
        }

        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "update your password",
                    null
            );
            return;
        }

        String email = user.getEmail();
        if (TextUtils.isEmpty(email)) {
            Toast.makeText(this, "No registered email address found.", Toast.LENGTH_SHORT).show();
            return;
        }

        String currentPassword = etCurrentPassword.getText() != null ? etCurrentPassword.getText().toString() : "";
        String newPassword     = etNewPassword.getText() != null ? etNewPassword.getText().toString() : "";
        String confirmPassword = etConfirmPassword.getText() != null ? etConfirmPassword.getText().toString() : "";

        // Validations
        if (TextUtils.isEmpty(currentPassword)) {
            tilCurrentPassword.setError("Current password is required");
            return;
        }
        tilCurrentPassword.setError(null);

        if (TextUtils.isEmpty(newPassword) || newPassword.length() < 6) {
            tilNewPassword.setError("Password must be at least 6 characters");
            return;
        }
        tilNewPassword.setError(null);

        if (!newPassword.equals(confirmPassword)) {
            tilConfirmPassword.setError("Passwords do not match");
            return;
        }
        tilConfirmPassword.setError(null);

        showLoading("Re-authenticating & updating password...");

        // Re-authenticate first to ensure session is valid
        AuthCredential credential = EmailAuthProvider.getCredential(email, currentPassword);
        user.reauthenticate(credential).addOnCompleteListener(reauthTask -> {
            if (reauthTask.isSuccessful()) {
                // Update password
                user.updatePassword(newPassword).addOnCompleteListener(updateTask -> {
                    hideLoading();
                    if (updateTask.isSuccessful()) {
                        etCurrentPassword.setText("");
                        etNewPassword.setText("");
                        etConfirmPassword.setText("");
                        tilCurrentPassword.setError(null);
                        tilNewPassword.setError(null);
                        tilConfirmPassword.setError(null);

                        new AlertDialog.Builder(ProfileActivity.this)
                                .setTitle("Password Changed")
                                .setMessage("Your GhostDocs vault password has been updated successfully.")
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        String error = updateTask.getException() != null
                                ? updateTask.getException().getMessage() : "Password update failed.";
                        Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                hideLoading();
                if (reauthTask.getException() instanceof FirebaseAuthInvalidCredentialsException) {
                    tilCurrentPassword.setError("Current password is incorrect");
                } else {
                    String error = reauthTask.getException() != null
                            ? reauthTask.getException().getMessage() : "Authentication failed";
                    tilCurrentPassword.setError(error);
                }
            }
        });
    }

    private void handleSendResetEmail() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null || TextUtils.isEmpty(user.getEmail())) {
            Toast.makeText(this, "No registered email address found.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "send a password reset email",
                    null
            );
            return;
        }

        final String email = user.getEmail();
        new AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage("Send password reset instructions to " + email + "?")
                .setPositiveButton("Send Email", (dialog, which) -> {
                    if (!NetworkHelper.isOnline(ProfileActivity.this)) {
                        NetworkHelper.showNetworkInterruptionDialog(
                                ProfileActivity.this,
                                NetworkHelper.getInterruptionScenario(ProfileActivity.this),
                                "send a password reset email",
                                null
                        );
                        return;
                    }
                    showLoading("Sending reset email...");
                    firebaseAuth.sendPasswordResetEmail(email)
                            .addOnCompleteListener(task -> {
                                hideLoading();
                                if (task.isSuccessful()) {
                                    Toast.makeText(ProfileActivity.this,
                                            "Password reset link sent to " + email,
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    String error = task.getException() != null
                                            ? task.getException().getMessage() : "Failed to send reset email";
                                    Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Feature 3: Email Verification
    // ════════════════════════════════════════════════════════════════════════

    private void handleSendVerificationEmail() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null || TextUtils.isEmpty(user.getEmail())) return;

        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "send an email verification link",
                    null
            );
            return;
        }

        final String email = user.getEmail();
        new AlertDialog.Builder(this)
                .setTitle("Verify Email Address")
                .setMessage("Send email verification link to " + email + "?")
                .setPositiveButton("Send Link", (dialog, which) -> {
                    if (!NetworkHelper.isOnline(ProfileActivity.this)) {
                        NetworkHelper.showNetworkInterruptionDialog(
                                ProfileActivity.this,
                                NetworkHelper.getInterruptionScenario(ProfileActivity.this),
                                "send an email verification link",
                                null
                        );
                        return;
                    }
                    showLoading("Sending verification link...");
                    user.sendEmailVerification().addOnCompleteListener(task -> {
                        hideLoading();
                        if (task.isSuccessful()) {
                            Toast.makeText(ProfileActivity.this,
                                    "Verification link sent to " + email + ". Check your inbox.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            String error = task.getException() != null
                                    ? task.getException().getMessage() : "Failed to send verification email.";
                            Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Feature 4: Sign Out & Account Deletion
    // ════════════════════════════════════════════════════════════════════════

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out of GhostDocs?")
                .setPositiveButton("Sign Out", (dialog, which) -> {
                    try {
                        stopService(new Intent(this, com.ghostdocs.vault.service.WatcherService.class));
                    } catch (Exception ignored) {}
                    firebaseAuth.signOut();
                    redirectToLogin();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAccount() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Account & Vault")
                .setMessage("WARNING: This will permanently erase your account, delete all encrypted files from your device vault, and remove all Firestore metadata.\n\nThis action CANNOT be undone. Are you sure you wish to continue?")
                .setPositiveButton("DELETE EVERYTHING", (dialog, which) -> executeDeleteAccount())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Executes irreversible account deletion:
     * 1. Purges local encrypted files on background thread via {@link VaultExecutor}.
     * 2. Purges user document collections from Firestore.
     * 3. Deletes user identity from Firebase Authentication.
     */
    private void executeDeleteAccount() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            redirectToLogin();
            return;
        }

        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "delete your account and cloud vault",
                    null
            );
            return;
        }

        final String uid = user.getUid();
        showLoading("Permanently purging vault & account...");

        VaultExecutor.getInstance().execute(() -> {
            try {
                // 1. Purge local encrypted files in internal vault
                File vaultDir = new File(getFilesDir(), "vault");
                if (vaultDir.exists() && vaultDir.isDirectory()) {
                    File[] files = vaultDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            boolean deleted = f.delete();
                            Log.d(TAG, "Deleted vault file " + f.getName() + ": " + deleted);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed cleaning local vault files", e);
            }

            // 2. Purge Firestore metadata
            firestore.collection("users")
                    .document(uid)
                    .collection("documents")
                    .get()
                    .addOnCompleteListener(queryTask -> {
                        if (queryTask.isSuccessful() && queryTask.getResult() != null) {
                            WriteBatch batch = firestore.batch();
                            for (DocumentSnapshot doc : queryTask.getResult().getDocuments()) {
                                batch.delete(doc.getReference());
                            }
                            batch.delete(firestore.collection("users").document(uid));
                            batch.commit().addOnCompleteListener(batchTask -> deleteFirebaseAuthUser(user));
                        } else {
                            deleteFirebaseAuthUser(user);
                        }
                    });
        });
    }

    private void deleteFirebaseAuthUser(@NonNull FirebaseUser user) {
        runOnUiThread(() -> user.delete().addOnCompleteListener(deleteTask -> {
            hideLoading();
            if (deleteTask.isSuccessful()) {
                Toast.makeText(ProfileActivity.this,
                        "GhostDocs account and vault data permanently deleted.",
                        Toast.LENGTH_LONG).show();
                redirectToLogin();
            } else {
                if (deleteTask.getException() instanceof FirebaseAuthRecentLoginRequiredException) {
                    new AlertDialog.Builder(ProfileActivity.this)
                            .setTitle("Re-authentication Required")
                            .setMessage("For security purposes, account deletion requires a recent sign-in. Please sign out, log back in, and try deleting your account again.")
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    String error = deleteTask.getException() != null
                            ? deleteTask.getException().getMessage() : "Failed to delete account.";
                    Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
        }));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Navigation & UI Helpers
    // ════════════════════════════════════════════════════════════════════════

    private void showLoading(String message) {
        if (tvLoadingText != null) {
            tvLoadingText.setText(message);
        }
        if (layoutProgressOverlay != null) {
            layoutProgressOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoading() {
        if (layoutProgressOverlay != null) {
            layoutProgressOverlay.setVisibility(View.GONE);
        }
    }

    private void redirectToLogin() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}

