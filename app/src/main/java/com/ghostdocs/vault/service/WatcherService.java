package com.ghostdocs.vault.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.observer.MediaContentObserver;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.NotificationHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * WatcherService — The Invisible Background Guardian
 *
 * <p>Runs continuously as a Foreground Service to protect sensitive media in real-time.
 * Monitors Android's {@link MediaStore} via {@link MediaContentObserver}, dispatches heavy
 * image analysis tasks to {@link VaultExecutor}, and streams live telemetry to Firebase
 * Realtime Database.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link Service}.</li>
 *   <li><b>Encapsulation:</b> Observer lifecycle, Realtime Database sync handles, and
 *       pipeline dispatching are private.</li>
 *   <li><b>Polymorphism:</b> Implements {@link MediaContentObserver.OnImageCapturedListener}
 *       to receive new media callbacks polymorphically.</li>
 * </ul>
 */
public class WatcherService extends Service implements MediaContentObserver.OnImageCapturedListener {

    private static final String TAG = "GhostDocs.Watcher";

    // ── Status Constants for Realtime Database ───────────────────────────────
    public static final String STATUS_ACTIVE     = "ACTIVE";
    public static final String STATUS_SCANNING   = "SCANNING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_OFFLINE    = "OFFLINE";
    public static final String STATUS_STOPPED    = "STOPPED";

    // ── Components ───────────────────────────────────────────────────────────
    private MediaContentObserver mediaObserver;
    private DatabaseReference watcherDbRef;
    private String currentUserId;
    private boolean isObserving = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WatcherService onCreate: starting foreground guardian");

        // 1. Immediately promote to Foreground Service with persistent notification
        Notification notification = NotificationHelper.buildWatcherNotification(
                this,
                getString(R.string.watcher_notification_text)
        );
        startForeground(NotificationHelper.WATCHER_NOTIFICATION_ID, notification);

        // 2. Initialize Realtime Database Sync
        initRealtimeDatabaseSync();

        // 3. Register MediaStore ContentObserver
        registerMediaObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "WatcherService onStartCommand called.");
        updateWatcherStatus(STATUS_ACTIVE, "Guardian actively monitoring MediaStore");
        return START_STICKY; // System restarts service if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "WatcherService onDestroy: stopping watcher");

        // Unregister observer
        unregisterMediaObserver();

        // Mark status as STOPPED in Realtime Database
        updateWatcherStatus(STATUS_STOPPED, "Watcher manually or system terminated");

        // Stop foreground
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bound interactions not needed
    }

    // ════════════════════════════════════════════════════════════════════════
    // MediaStore ContentObserver Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    private void registerMediaObserver() {
        if (isObserving) return;

        try {
            Handler handler = new Handler(Looper.getMainLooper());
            mediaObserver = new MediaContentObserver(handler, this, this);

            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mediaObserver
            );

            isObserving = true;
            Log.i(TAG, "MediaContentObserver registered successfully on " + MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register MediaContentObserver", e);
        }
    }

    private void unregisterMediaObserver() {
        if (!isObserving || mediaObserver == null) return;
        try {
            getContentResolver().unregisterContentObserver(mediaObserver);
            isObserving = false;
            Log.d(TAG, "MediaContentObserver unregistered.");
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering MediaContentObserver", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MediaContentObserver.OnImageCapturedListener (Polymorphism)
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onImageCaptured(@NonNull Uri imageUri, @NonNull String displayName,
                                @NonNull String mimeType, long dateAdded) {
        Log.i(TAG, "Captured image event received in WatcherService: " + displayName);

        // Update Realtime Database status to SCANNING
        updateWatcherStatus(STATUS_SCANNING, "Analyzing new image: " + displayName);

        // Update Foreground Notification text
        updateForegroundNotification("Analyzing capture: " + displayName);

        // Dispatch heavy processing pipeline to background thread pool
        VaultExecutor.getInstance().execute(new Runnable() {
            @Override
            public void run() {
                processIncomingMedia(imageUri, displayName, mimeType, dateAdded);
            }
        });
    }

    /**
     * Pipeline processing method executed entirely on a worker thread.
     * Steps 5-8 will integrate OCR, OpenCV crop, AES-256 encryption, and PDF export here.
     */
    private void processIncomingMedia(Uri imageUri, String displayName, String mimeType, long dateAdded) {
        try {
            Log.d(TAG, "Background processing started for: " + displayName);

            // Step 4 Hook: Telemetry update
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("displayName", displayName);
            logEntry.put("uri", imageUri.toString());
            logEntry.put("mimeType", mimeType);
            logEntry.put("detectedAt", ServerValue.TIMESTAMP);

            if (watcherDbRef != null) {
                watcherDbRef.child("recent_captures").push().setValue(logEntry);
            }

            // Simulated step placeholder (Steps 5-8 will hook concrete BaseVaultTask subclasses here)
            Thread.sleep(800);

            // Finished step
            updateWatcherStatus(STATUS_ACTIVE, "Monitoring active. Last verified: " + displayName);
            updateForegroundNotification(getString(R.string.watcher_notification_text));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Error in processIncomingMedia pipeline", e);
            updateWatcherStatus(STATUS_ACTIVE, "Idle after processing error");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Firebase Realtime Database Sync
    // ════════════════════════════════════════════════════════════════════════

    private void initRealtimeDatabaseSync() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                currentUserId = currentUser.getUid();
                watcherDbRef = FirebaseDatabase.getInstance()
                        .getReference("watcher_status")
                        .child(currentUserId);

                // Configure onDisconnect handler to mark offline automatically
                Map<String, Object> offlineMap = new HashMap<>();
                offlineMap.put("status", STATUS_OFFLINE);
                offlineMap.put("lastSeen", ServerValue.TIMESTAMP);
                watcherDbRef.onDisconnect().updateChildren(offlineMap);

                Log.d(TAG, "Firebase Realtime Database watcher sync initialized for user: " + currentUserId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Firebase Realtime DB init deferred (Auth not ready or offline)", e);
        }
    }

    private void updateWatcherStatus(String status, String details) {
        try {
            if (watcherDbRef == null) {
                initRealtimeDatabaseSync();
            }

            if (watcherDbRef != null) {
                Map<String, Object> update = new HashMap<>();
                update.put("status", status);
                update.put("details", details);
                update.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
                update.put("updatedAt", ServerValue.TIMESTAMP);

                watcherDbRef.updateChildren(update);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update Realtime Database status", e);
        }
    }

    private void updateForegroundNotification(String statusText) {
        Notification notification = NotificationHelper.buildWatcherNotification(this, statusText);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NotificationHelper.WATCHER_NOTIFICATION_ID, notification);
        }
    }
}
