package com.ghostdocs.vault;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;

/**
 * GhostDocsApp — Application Subclass
 *
 * <p>Entry point for global application-level initialization. Declared in
 * AndroidManifest.xml via android:name=".GhostDocsApp".
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create Android {@link NotificationChannel}s on API 26+ (required before
 *       posting any notification).</li>
 *   <li>Provide a global application context reference.</li>
 *   <li>Future: Initialize Firebase App, OpenCV, and Crashlytics here.</li>
 * </ul>
 *
 * <p>OOP Note: This class extends {@link Application} (Inheritance) and encapsulates
 * all one-time initialization logic, keeping Activity/Service classes clean.
 */
public class GhostDocsApp extends Application {

    // ── Singleton context reference ──────────────────────────────────────────
    private static GhostDocsApp instance;

    /**
     * Returns the global Application instance for use in non-Activity contexts
     * (e.g., inside Services, Repositories, or utility classes).
     *
     * @return the singleton {@link GhostDocsApp} instance
     */
    public static GhostDocsApp getInstance() {
        return instance;
    }

    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Configure Firestore offline persistence (required by spec)
        configureFirestore();

        // Create notification channels once at app startup
        createNotificationChannels();
    }

    /**
     * Configures Cloud Firestore with offline persistence and unlimited cache size.
     *
     * <p>Offline persistence lets the RecyclerView display documents instantly
     * from the local cache while the Firestore server sync runs in the background.
     * This is required by the project spec.
     */
    private void configureFirestore() {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                            PersistentCacheSettings.newBuilder()
                                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                                    .build()
                    )
                    .build();
            db.setFirestoreSettings(settings);
            Log.d("GhostDocsApp", "Firestore offline persistence enabled (unlimited cache).");
        } catch (Exception e) {
            // Settings can only be applied before Firestore is used for the first time.
            // If the app was previously initialized without these settings, this is a no-op.
            Log.w("GhostDocsApp", "Could not set Firestore settings (may already be initialised).", e);
        }
    }

    /**
     * Creates all {@link NotificationChannel}s required by GhostDocs.
     *
     * <p>Must be called before posting any notification on Android 8.0+.
     * Safe to call multiple times — the system ignores duplicates.
     *
     * <p>Channels:
     * <ol>
     *   <li><b>Watcher Channel</b> — LOW importance, persistent foreground
     *       service notification for the background media watcher.</li>
     *   <li><b>Document Alert Channel</b> — HIGH importance, alert fired when
     *       a sensitive document is detected and encrypted.</li>
     * </ol>
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return; // NotificationChannel not available below API 26
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (manager == null) return;

        // ── Channel 1: Watcher persistent foreground notification ───────────
        NotificationChannel watcherChannel = new NotificationChannel(
                getString(R.string.watcher_notification_channel_id),
                getString(R.string.watcher_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // Silent; no sound/vibration
        );
        watcherChannel.setDescription(getString(R.string.watcher_notification_channel_desc));
        watcherChannel.setShowBadge(false);
        manager.createNotificationChannel(watcherChannel);

        // ── Channel 2: Document detected alert ──────────────────────────────
        NotificationChannel docChannel = new NotificationChannel(
                getString(R.string.doc_channel_id),
                getString(R.string.doc_channel_name),
                NotificationManager.IMPORTANCE_HIGH  // Pops up with sound
        );
        docChannel.setDescription(getString(R.string.doc_channel_desc));
        docChannel.setShowBadge(true);
        docChannel.enableVibration(true);
        manager.createNotificationChannel(docChannel);
    }
}

