package com.ghostdocs.vault.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.ghostdocs.vault.service.WatcherService;

/**
 * BootReceiver — Device Boot BroadcastReceiver
 *
 * <p>Listens for device restart broadcasts ({@link Intent#ACTION_BOOT_COMPLETED},
 * and OEM-specific variants like HTC's {@code QUICKBOOT_POWERON}).
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link BroadcastReceiver}.</li>
 *   <li><b>Encapsulation:</b> Validates boot actions and checks the user's
 *       {@link SharedPreferences} preference ({@code auto_start_watcher}) before
 *       activating the service.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "GhostDocs.BootReceiver";
    private static final String PREFS_NAME = "GhostDocsPrefs";
    private static final String PREF_AUTO_START = "auto_start_watcher";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        final String action = intent.getAction();
        if (action == null) return;

        // Check for standard Android boot as well as OEM fast-boot broadcasts
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.i(TAG, "Device boot completed broadcast received: " + action);

            // Verify if user enabled auto-start in Settings
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean autoStartEnabled = prefs.getBoolean(PREF_AUTO_START, true);

            if (autoStartEnabled) {
                Log.i(TAG, "Auto-start enabled. Launching WatcherService...");
                startWatcherService(context);
            } else {
                Log.i(TAG, "Auto-start disabled by user in Settings. WatcherService will not start.");
            }
        }
    }

    /**
     * Safely initiates the {@link WatcherService} complying with Android O+ foreground requirements.
     */
    private void startWatcherService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, WatcherService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch WatcherService from BootReceiver", e);
        }
    }
}
