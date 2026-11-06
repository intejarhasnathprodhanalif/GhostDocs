package com.ghostdocs.vault.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * PermissionHelper — Runtime Permission Manager
 *
 * <p>Centralises all runtime permission logic for GhostDocs, handling the
 * Android version fragmentation between API 26–35:
 * <ul>
 *   <li>Android 13+ (API 33): {@code READ_MEDIA_IMAGES}, {@code POST_NOTIFICATIONS}</li>
 *   <li>Android 12 and below: {@code READ_EXTERNAL_STORAGE}</li>
 *   <li>Android 11+ (API 30): {@code MANAGE_EXTERNAL_STORAGE} (special action)</li>
 * </ul>
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> All permission name strings, version checks, and
 *       intent construction are hidden from callers. Activities only call
 *       {@link #requestStoragePermissions} and {@link #hasStoragePermission}.</li>
 *   <li><b>Static utility methods:</b> No instance needed — stateless helper class.</li>
 * </ul>
 *
 * <p><b>Usage in Activities:</b>
 * <pre>{@code
 *   if (!PermissionHelper.hasStoragePermission(this)) {
 *       PermissionHelper.requestStoragePermissions(this, permissionLauncher);
 *   }
 * }</pre>
 */
public final class PermissionHelper {

    private static final String TAG = "GhostDocs.Permissions";

    // Request code (used if falling back to old onRequestPermissionsResult)
    public static final int REQUEST_CODE_STORAGE    = 1001;
    public static final int REQUEST_CODE_NOTIFY     = 1002;
    public static final int REQUEST_CODE_ALL        = 1003;

    // Private constructor — utility class, no instances allowed (Encapsulation)
    private PermissionHelper() {
        throw new UnsupportedOperationException("PermissionHelper is a static utility class.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Storage Permission
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the app currently holds the required storage
     * read permission for the running Android version.
     *
     * <p>Permission mapping:
     * <ul>
     *   <li>API 33+ → {@code READ_MEDIA_IMAGES}</li>
     *   <li>API 26–32 → {@code READ_EXTERNAL_STORAGE}</li>
     * </ul>
     *
     * @param context any context
     * @return {@code true} if storage is accessible
     */
    public static boolean hasStoragePermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            // Android 8–12
            return hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    /**
     * Requests the appropriate storage permission(s) using an
     * {@link ActivityResultLauncher} (non-deprecated approach).
     *
     * @param activity the host activity
     * @param launcher the {@code ActivityResultLauncher<String[]>} registered in the Activity
     */
    public static void requestStoragePermissions(
            @NonNull Activity activity,
            @NonNull ActivityResultLauncher<String[]> launcher) {

        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: granular media permissions
            if (!hasPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            // Android 8–12: legacy external storage permission
            if (!hasPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!permissions.isEmpty()) {
            Log.d(TAG, "Requesting storage permissions: " + permissions);
            launcher.launch(permissions.toArray(new String[0]));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Notification Permission (Android 13+)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the app can post notifications.
     * Always returns {@code true} below Android 13 (permission not needed).
     *
     * @param context any context
     * @return {@code true} if POST_NOTIFICATIONS is granted or not required
     */
    public static boolean hasNotificationPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(context, Manifest.permission.POST_NOTIFICATIONS);
        }
        return true; // Not required below API 33
    }

    /**
     * Requests {@code POST_NOTIFICATIONS} permission on Android 13+.
     * No-op on older versions.
     *
     * @param activity the host activity
     * @param launcher the permission result launcher
     */
    public static void requestNotificationPermission(
            @NonNull Activity activity,
            @NonNull ActivityResultLauncher<String[]> launcher) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasNotificationPermission(activity)) {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.");
            launcher.launch(new String[]{ Manifest.permission.POST_NOTIFICATIONS });
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // All Permissions (Batch request on first launch)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if ALL permissions required by GhostDocs are granted.
     *
     * @param context any context
     * @return {@code true} if storage + notification permissions are all granted
     */
    public static boolean hasAllRequiredPermissions(@NonNull Context context) {
        return hasStoragePermission(context) && hasNotificationPermission(context);
    }

    /**
     * Requests all missing permissions in a single batch launcher call.
     * This is called once during the first run of {@code VaultActivity}.
     *
     * @param activity the host activity
     * @param launcher the permission result launcher
     */
    public static void requestAllPermissions(
            @NonNull Activity activity,
            @NonNull ActivityResultLauncher<String[]> launcher) {

        List<String> missing = new ArrayList<>();

        // Storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)) {
                missing.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (!hasPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!missing.isEmpty()) {
            Log.d(TAG, "Requesting permissions batch: " + missing);
            launcher.launch(missing.toArray(new String[0]));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MANAGE_EXTERNAL_STORAGE — Special permission (Android 11+)
    // Requires navigating to system Settings; cannot be granted via dialog
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the app holds the
     * {@code MANAGE_EXTERNAL_STORAGE} special permission (Android 11+).
     * Always returns {@code true} below Android 11.
     *
     * @param context any context
     * @return {@code true} if broad storage management is available
     */
    public static boolean hasManageStoragePermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Cannot use ContextCompat for this special permission
            return android.os.Environment.isExternalStorageManager();
        }
        return true; // Not required below API 30
    }

    /**
     * Opens the system's "All Files Access" settings screen so the user can
     * manually grant {@code MANAGE_EXTERNAL_STORAGE}.
     *
     * <p>Must be called from an Activity context.
     *
     * @param activity the host activity
     */
    public static void openManageStorageSettings(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(intent);
            } catch (Exception e) {
                // Some OEMs don't support the direct package URI — fall back to general settings
                Log.w(TAG, "Cannot open app-specific manage-storage settings. Using fallback.", e);
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rationale helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the system recommends showing a rationale dialog
     * before requesting the given permission (user previously denied it once).
     *
     * @param activity   the host activity
     * @param permission the permission to check
     * @return {@code true} if a rationale should be shown
     */
    public static boolean shouldShowRationale(@NonNull Activity activity,
                                               @NonNull String permission) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }

    /**
     * Opens the app's system settings page (for cases where the user has
     * permanently denied a permission — "Don't ask again").
     *
     * @param activity the host activity
     */
    public static void openAppSettings(@NonNull Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks if a single permission is currently granted.
     *
     * @param context    any context
     * @param permission the Manifest permission string
     * @return {@code true} if granted
     */
    private static boolean hasPermission(@NonNull Context context,
                                          @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
