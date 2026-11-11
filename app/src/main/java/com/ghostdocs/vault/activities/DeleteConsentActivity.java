package com.ghostdocs.vault.activities;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.MediaDeleteHelper;
import com.ghostdocs.vault.util.PermissionHelper;

import java.util.Collections;

/**
 * DeleteConsentActivity — Foreground Activity Helper for Scoped Storage Deletion Consent.
 *
 * <p>On Android 10–14+, background services ({@code WatcherService}) cannot launch the OS
 * confirmation sheet required to delete gallery photos created by other apps unless
 * {@code MANAGE_EXTERNAL_STORAGE} is granted. When the user taps the notification action,
 * this lightweight Activity launches the system delete consent prompt cleanly.
 */
public class DeleteConsentActivity extends AppCompatActivity {

    private static final String TAG = "GhostDocs.DeleteConsent";

    public static final String EXTRA_TARGET_URI = "extra_target_uri";
    public static final String EXTRA_RECOVERABLE_SENDER = "extra_recoverable_sender";
    public static final String EXTRA_NOTIFICATION_ID = "extra_notification_id";

    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "Original removed from gallery.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Original kept in gallery.", Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    }
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        int notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (notifId != -1) {
            NotificationManagerCompat.from(this).cancel(notifId);
        }

        try {
            String uriStr = intent.getStringExtra(EXTRA_TARGET_URI);
            Uri targetUri = uriStr != null ? Uri.parse(uriStr) : null;

            if (targetUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Uri canonical = MediaDeleteHelper.resolveDeletableMediaUri(this, targetUri, null, 0);
                if (canonical != null && MediaDeleteHelper.isCanonicalMediaStoreUri(canonical)) {
                    PendingIntent pi = MediaStore.createDeleteRequest(
                            getContentResolver(),
                            Collections.singletonList(canonical)
                    );
                    deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                    return;
                }
            }

            IntentSender recoverableSender = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(EXTRA_RECOVERABLE_SENDER, IntentSender.class)
                    : intent.getParcelableExtra(EXTRA_RECOVERABLE_SENDER);
            if (recoverableSender != null) {
                deleteLauncher.launch(new IntentSenderRequest.Builder(recoverableSender).build());
                return;
            }

            // Non-media files (such as PDFs) on Android 11+ require All Files Access to delete directly
            if (targetUri != null) {
                if (PermissionHelper.hasManageStoragePermission(this)) {
                    VaultExecutor.getInstance().execute(() -> {
                        MediaDeleteHelper.DeleteAttemptResult res =
                                MediaDeleteHelper.tryDirectDelete(this, targetUri, null, 0);
                        runOnUiThread(() -> {
                            Toast.makeText(this,
                                    res.isDeleted()
                                            ? "Original document removed from storage."
                                            : "Could not remove original file from this provider.",
                                    Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    });
                    return;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    new AlertDialog.Builder(this)
                            .setTitle("All Files Access Needed")
                            .setMessage("Android requires 'All Files Access' to remove secured PDFs directly from device storage.")
                            .setPositiveButton("Open Settings", (d, w) -> {
                                PermissionHelper.openManageStorageSettings(this);
                                finish();
                            })
                            .setNegativeButton("Keep File", (d, w) -> finish())
                            .setOnCancelListener(d -> finish())
                            .show();
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch system delete consent dialog", e);
        }

        Toast.makeText(this, "Could not launch storage delete prompt.", Toast.LENGTH_SHORT).show();
        finish();
    }
}

