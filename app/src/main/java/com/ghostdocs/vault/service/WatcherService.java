package com.ghostdocs.vault.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.activities.VaultActivity;

/**
 * WatcherService — The Invisible Background Guardian
 *
 * <p>Runs as a Foreground Service to stay alive while the app is in the background.
 * Declared in {@code AndroidManifest.xml} with {@code foregroundServiceType="dataSync"}.
 *
 * <p><b>Current behavior (Step 1 stub):</b> Starts as a foreground service and shows
 * the persistent watcher notification. No actual media observation yet.
 *
 * <p><b>Full implementation (Step 4):</b> Will:
 * <ul>
 *   <li>Register a {@link com.ghostdocs.vault.observer.MediaContentObserver} on
 *       {@link android.provider.MediaStore} to detect new images/screenshots.</li>
 *   <li>Manage a Java {@link java.util.concurrent.ExecutorService} thread pool for
 *       all heavy background tasks (OCR, crop, encrypt, PDF).</li>
 *   <li>Update Firebase Realtime Database with live watcher status.</li>
 * </ul>
 *
 * <p>OOP Note: Extends {@link Service} (Inheritance). Business logic will be
 * delegated to task classes (Polymorphism via {@code BaseVaultTask}) in Step 4+.
 */
public class WatcherService extends Service {

    // Foreground notification ID — must be > 0
    private static final int NOTIFICATION_ID = 1001;

    // ── Service lifecycle ────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        // Start as foreground immediately to prevent system from killing the service
        startForeground(NOTIFICATION_ID, buildWatcherNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: system will restart this service if killed,
        // passing a null intent on restart
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO (Step 4): Unregister MediaContentObserver and shut down ExecutorService
        stopForeground(true);
    }

    /**
     * This service does not support binding.
     * All interaction happens via startService() Intent commands.
     *
     * @return null — not a bound service
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Notification ─────────────────────────────────────────────────────────

    /**
     * Builds the persistent foreground service notification shown in the
     * Android status bar while the watcher is active.
     *
     * <p>Tapping the notification opens {@link VaultActivity}.
     *
     * @return a fully configured {@link Notification}
     */
    private Notification buildWatcherNotification() {
        Intent openVaultIntent = new Intent(this, VaultActivity.class);
        openVaultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openVaultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(
                this,
                getString(R.string.watcher_notification_channel_id))
                .setContentTitle(getString(R.string.watcher_notification_title))
                .setContentText(getString(R.string.watcher_notification_text))
                .setSmallIcon(android.R.drawable.ic_lock_lock) // Replaced with custom icon in Step 2
                .setContentIntent(pendingIntent)
                .setOngoing(true)         // Cannot be dismissed by user swipe
                .setSilent(true)          // No sound/vibration for persistent notification
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}

