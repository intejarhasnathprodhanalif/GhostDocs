package com.ghostdocs.vault.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.ghostdocs.vault.service.WatcherService;

/**
 * BootReceiver — Device Boot BroadcastReceiver
 *
 * <p>Listens for the {@link Intent#ACTION_BOOT_COMPLETED} broadcast sent by
 * Android after the device finishes booting. Automatically restarts the
 * {@link WatcherService} so the vault watcher is always active.
 *
 * <p>Also handles {@code QUICKBOOT_POWERON} for HTC and other OEM devices
 * that use a non-standard boot broadcast.
 *
 * <p>Declared in {@code AndroidManifest.xml} with:
 * <pre>
 *   android:exported="true"
 *   RECEIVE_BOOT_COMPLETED permission
 * </pre>
 *
 * <p>OOP Note: Extends {@link BroadcastReceiver} (Inheritance). Encapsulates
 * all boot-restart logic, keeping activities and services free of this concern.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "GhostDocs.BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (action == null) return;

        // Handle BOOT_COMPLETED and OEM variants
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.i(TAG, "Boot completed — restarting WatcherService");
            restartWatcherService(context);
        }
    }

    /**
     * Starts the {@link WatcherService} as a foreground service.
     *
     * <p>On Android 8.0+ (API 26+) background service start restrictions apply,
     * so we must use {@link Context#startForegroundService(Intent)}.
     *
     * @param context the broadcast receiver context
     */
    private void restartWatcherService(Context context) {
        Intent serviceIntent = new Intent(context, WatcherService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+: use startForegroundService — the service MUST call
            // startForeground() within 5 seconds or the system will ANR it.
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}

