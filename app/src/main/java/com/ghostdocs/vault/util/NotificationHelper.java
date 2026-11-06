package com.ghostdocs.vault.util;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.activities.VaultActivity;

/**
 * NotificationHelper — Centralized Notification Dispatcher
 *
 * <p>Encapsulates Android Notification construction, channel routing, and pending intents
 * for both the persistent Watcher foreground notification and event-driven document alerts.
 */
public final class NotificationHelper {

    public static final int WATCHER_NOTIFICATION_ID = 1001;
    private static final int DOC_ALERT_NOTIFICATION_ID_BASE = 2000;
    private static int alertCounter = 0;

    private NotificationHelper() {
        throw new UnsupportedOperationException("NotificationHelper is a static utility class.");
    }

    /**
     * Builds the persistent foreground notification required to keep WatcherService active.
     */
    @NonNull
    public static Notification buildWatcherNotification(@NonNull Context context, @NonNull String statusText) {
        Intent intent = new Intent(context, VaultActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, context.getString(R.string.watcher_notification_channel_id))
                .setContentTitle(context.getString(R.string.watcher_notification_title))
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Triggers a heads-up notification when a sensitive document is detected, encrypted,
     * and the original is deleted.
     *
     * @param context Application or service context.
     * @param docTitle Title of the encrypted document.
     * @param tagType Extracted sensitive category (e.g. NID, TrxID, Prescription).
     */
    public static void showDocumentSecuredNotification(@NonNull Context context,
                                                       @NonNull String docTitle,
                                                       @NonNull String tagType) {
        if (!PermissionHelper.hasNotificationPermission(context)) {
            return;
        }

        Intent openVaultIntent = new Intent(context, VaultActivity.class);
        openVaultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                alertCounter + 1,
                openVaultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String message = "[" + tagType + "] " + docTitle + " secured into vault. Original erased.";

        Notification notification = new NotificationCompat.Builder(
                context,
                context.getString(R.string.doc_channel_id))
                .setContentTitle(context.getString(R.string.doc_detected_title))
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();

        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            manager.notify(DOC_ALERT_NOTIFICATION_ID_BASE + (alertCounter++ % 100), notification);
        } catch (SecurityException ignored) {
            // Handled if POST_NOTIFICATIONS is revoked
        }
    }
}

