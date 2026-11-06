package com.ghostdocs.vault.util;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.activities.SecurePromptActivity;
import com.ghostdocs.vault.activities.VaultActivity;
import com.ghostdocs.vault.service.WatcherService;

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

    /**
     * Shows an interactive heads-up notification with action buttons asking the user
     * whether to secure a detected capture into the vault or keep it in the gallery.
     */
    public static void showSecurityPromptNotification(@NonNull Context context,
                                                      int notificationId,
                                                      @NonNull Uri imageUri,
                                                      @NonNull String displayName,
                                                      @NonNull String mimeType,
                                                      long dateAdded,
                                                      @NonNull String smartTitle,
                                                      @NonNull String docType,
                                                      @NonNull String snippet) {
        if (!PermissionHelper.hasNotificationPermission(context)) {
            return;
        }

        // 1. PendingIntent for "Secure to Vault" action button
        Intent secureIntent = new Intent(context, WatcherService.class);
        secureIntent.setAction(WatcherService.ACTION_SECURE_CAPTURE);
        secureIntent.putExtra(WatcherService.EXTRA_IMAGE_URI, imageUri.toString());
        secureIntent.putExtra(WatcherService.EXTRA_DISPLAY_NAME, displayName);
        secureIntent.putExtra(WatcherService.EXTRA_MIME_TYPE, mimeType);
        secureIntent.putExtra(WatcherService.EXTRA_DATE_ADDED, dateAdded);
        secureIntent.putExtra(WatcherService.EXTRA_NOTIFICATION_ID, notificationId);

        PendingIntent securePendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            securePendingIntent = PendingIntent.getForegroundService(
                    context,
                    notificationId * 2,
                    secureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        } else {
            securePendingIntent = PendingIntent.getService(
                    context,
                    notificationId * 2,
                    secureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        // 2. PendingIntent for "Keep in Gallery" action button
        Intent ignoreIntent = new Intent(context, WatcherService.class);
        ignoreIntent.setAction(WatcherService.ACTION_IGNORE_CAPTURE);
        ignoreIntent.putExtra(WatcherService.EXTRA_NOTIFICATION_ID, notificationId);

        PendingIntent ignorePendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ignorePendingIntent = PendingIntent.getForegroundService(
                    context,
                    notificationId * 2 + 1,
                    ignoreIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        } else {
            ignorePendingIntent = PendingIntent.getService(
                    context,
                    notificationId * 2 + 1,
                    ignoreIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        // 3. PendingIntent for clicking notification body -> opens SecurePromptActivity
        Intent promptIntent = new Intent(context, SecurePromptActivity.class);
        promptIntent.putExtra(WatcherService.EXTRA_IMAGE_URI, imageUri.toString());
        promptIntent.putExtra(WatcherService.EXTRA_DISPLAY_NAME, displayName);
        promptIntent.putExtra(WatcherService.EXTRA_MIME_TYPE, mimeType);
        promptIntent.putExtra(WatcherService.EXTRA_DATE_ADDED, dateAdded);
        promptIntent.putExtra(WatcherService.EXTRA_NOTIFICATION_ID, notificationId);
        promptIntent.putExtra("smart_title", smartTitle);
        promptIntent.putExtra("doc_type", docType);
        promptIntent.putExtra("snippet", snippet);
        promptIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                promptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "🛡️ Secure \"" + smartTitle + "\"?";
        String contentText = "Detected " + docType + ". Tap Secure to encrypt in Vault, or Keep in Gallery.";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                context.getString(R.string.doc_channel_id))
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(contentText + "\n\n" + (snippet.isEmpty() ? "" : "\"" + snippet + "\"")))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_menu_save, "🔒 Secure to Vault", securePendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "❌ Keep in Gallery", ignorePendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            manager.notify(notificationId, builder.build());
        } catch (SecurityException ignored) {
            // Handled if POST_NOTIFICATIONS is revoked
        }
    }
}

