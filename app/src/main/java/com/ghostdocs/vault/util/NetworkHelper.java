package com.ghostdocs.vault.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * NetworkHelper — Network & Airplane Mode Detection and Interruption Handling
 *
 * <p>Provides robust real-time monitoring of device connectivity to protect
 * cryptographic pipelines and Firebase cloud synchronization from hanging or
 * corruption when network disconnects or Airplane Mode is toggled:
 * <ul>
 *   <li><b>Airplane Mode Detection:</b> Real-time interrogation via {@link Settings.Global#AIRPLANE_MODE_ON}.</li>
 *   <li><b>Active Internet Capability:</b> Verifies {@link NetworkCapabilities#NET_CAPABILITY_INTERNET}.</li>
 *   <li><b>Interruption Dialogs:</b> Distinguishes between Airplane Mode and connection loss with context-aware advice.</li>
 * </ul>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> System service querying and dialog assembly are isolated within clean static methods.</li>
 * </ul>
 */
public final class NetworkHelper {

    private static final String TAG = "GhostDocs.Network";

    public static final String SCENARIO_AIRPLANE_MODE = "AIRPLANE_MODE";
    public static final String SCENARIO_NETWORK_LOST = "NETWORK_LOST";

    private NetworkHelper() {
        // Private constructor for utility class
    }

    /**
     * Checks if Airplane Mode is currently enabled on the device.
     *
     * @param context Application or Activity context.
     * @return {@code true} if Airplane Mode is active; {@code false} otherwise.
     */
    public static boolean isAirplaneModeOn(@Nullable Context context) {
        if (context == null) return false;
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Airplane Mode setting", e);
            return false;
        }
    }

    /**
     * Checks whether an active network connection with internet capability exists.
     *
     * @param context Application or Activity context.
     * @return {@code true} if connected to an active network with internet capability; {@code false} otherwise.
     */
    public static boolean isNetworkConnected(@Nullable Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = cm.getActiveNetwork();
                if (activeNet == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                NetworkInfo netInfo = cm.getActiveNetworkInfo();
                return netInfo != null && netInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking network connectivity", e);
            return false;
        }
    }

    /**
     * Checks if the device is fully online and ready for cloud/network operations.
     * Returns {@code false} if either Airplane Mode is active or no network is connected.
     *
     * @param context Application or Activity context.
     * @return {@code true} if online without Airplane Mode; {@code false} otherwise.
     */
    public static boolean isOnline(@Nullable Context context) {
        if (context == null) return false;
        if (isAirplaneModeOn(context)) return false;
        return isNetworkConnected(context);
    }

    /**
     * Identifies the specific reason for offline state / network interruption.
     *
     * @param context Application or Activity context.
     * @return {@link #SCENARIO_AIRPLANE_MODE} or {@link #SCENARIO_NETWORK_LOST}.
     */
    @NonNull
    public static String getInterruptionScenario(@Nullable Context context) {
        if (isAirplaneModeOn(context)) {
            return SCENARIO_AIRPLANE_MODE;
        }
        return SCENARIO_NETWORK_LOST;
    }

    /**
     * Shows a polished Material Alert Dialog explaining why a network operation was terminated or could not start.
     *
     * @param context Activity context to anchor the dialog.
     * @param scenario {@link #SCENARIO_AIRPLANE_MODE} or {@link #SCENARIO_NETWORK_LOST}.
     * @param actionDescription Human-readable description of the attempted action (e.g. "update password").
     * @param onDismiss Optional callback invoked when the user acknowledges the dialog.
     */
    public static void showNetworkInterruptionDialog(@NonNull Context context,
                                                     @NonNull String scenario,
                                                     @Nullable String actionDescription,
                                                     @Nullable Runnable onDismiss) {
        String title;
        StringBuilder message = new StringBuilder();

        String action = (actionDescription != null && !actionDescription.isEmpty())
                ? actionDescription
                : "complete this operation";

        if (SCENARIO_AIRPLANE_MODE.equals(scenario)) {
            title = "Airplane Mode Active";
            message.append("GhostDocs was unable to ").append(action).append(" because Airplane Mode is currently enabled on your device.\n\n")
                   .append("To proceed:\n")
                   .append("1. Turn off Airplane Mode in your device Quick Settings.\n")
                   .append("2. Connect to Wi-Fi or mobile data.\n")
                   .append("3. Try again.");
        } else {
            title = "Network Interrupted";
            message.append("GhostDocs was unable to ").append(action).append(" because the internet connection was lost or unavailable.\n\n")
                   .append("To proceed:\n")
                   .append("1. Verify your Wi-Fi or mobile data connection.\n")
                   .append("2. Ensure internet access is active.\n")
                   .append("3. Try again.");
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message.toString())
                .setPositiveButton("OK", (dialog, which) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Shows a specialized Material Alert Dialog when the batch securing pipeline was terminated mid-process.
     * Informs the user of exact partial completion count and confirms that all pending files were left safely untouched.
     *
     * @param context Activity context.
     * @param scenario {@link #SCENARIO_AIRPLANE_MODE} or {@link #SCENARIO_NETWORK_LOST}.
     * @param successCount Number of documents successfully encrypted and synced before interruption.
     * @param totalCount Total number of documents selected.
     * @param onDismiss Optional callback after dismissal.
     */
    public static void showSecuringInterruptedDialog(@NonNull Context context,
                                                    @NonNull String scenario,
                                                    int successCount,
                                                    int totalCount,
                                                    @Nullable Runnable onDismiss) {
        String title;
        StringBuilder message = new StringBuilder();

        if (SCENARIO_AIRPLANE_MODE.equals(scenario)) {
            title = "Airplane Mode Enabled — Securing Terminated";
            message.append("The securing process was immediately terminated because Airplane Mode was activated on your device.\n\n");
        } else {
            title = "Connection Lost — Securing Terminated";
            message.append("The securing process was immediately terminated because your internet connection was lost.\n\n");
        }

        if (successCount > 0 && totalCount > 1) {
            int remaining = totalCount - successCount;
            message.append("Progress Summary:\n")
                   .append("• ").append(successCount).append(" of ").append(totalCount).append(" documents were securely vaulted.\n")
                   .append("• ").append(remaining).append(" document(s) were safely aborted.\n\n")
                   .append("Data Protection Guarantee:\n")
                   .append("Original photos for aborted documents were preserved in your gallery. No partial or corrupted files were created.\n\n");
        } else {
            message.append("Data Protection Guarantee:\n")
                   .append("No documents were modified. Your original photos remain completely intact and safe in your device gallery.\n\n");
        }

        if (SCENARIO_AIRPLANE_MODE.equals(scenario)) {
            message.append("Turn off Airplane Mode and connect to the internet to secure your documents.");
        } else {
            message.append("Check your Wi-Fi or mobile data connection and try again.");
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message.toString())
                .setPositiveButton("Got It", (dialog, which) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .setCancelable(false)
                .show();
    }
}

