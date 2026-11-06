package com.ghostdocs.vault.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.activities.LaunchActivity;

/**
 * VaultWidgetProvider — Home Screen Vault AppWidget
 *
 * <p>Renders a live home-screen widget displaying real-time vault metrics:
 * <ul>
 *   <li>Total secured document count.</li>
 *   <li>Live status of the Invisible Watcher service (ON / OFF).</li>
 *   <li>Direct biometric-gated launcher shortcut to open the vault.</li>
 * </ul>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link AppWidgetProvider}.</li>
 *   <li><b>Encapsulation:</b> RemoteViews construction and state synchronization are
 *       encapsulated inside {@link #buildRemoteViews}.</li>
 * </ul>
 */
public class VaultWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "GhostDocs.Widget";
    private static final String PREFS_NAME = "GhostDocsPrefs";
    private static final String PREF_CACHED_DOC_COUNT = "widget_cached_doc_count";
    private static final String PREF_WATCHER_ON = "watcher_enabled";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(TAG, "onUpdate called for " + appWidgetIds.length + " widgets.");
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int docCount = prefs.getInt(PREF_CACHED_DOC_COUNT, 0);
        boolean watcherActive = prefs.getBoolean(PREF_WATCHER_ON, false);

        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = buildRemoteViews(context, docCount, watcherActive);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    /**
     * Builds the RemoteViews hierarchy with live text, colors, and PendingIntents.
     */
    @NonNull
    private static RemoteViews buildRemoteViews(@NonNull Context context, int docCount, boolean watcherActive) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_vault);

        // 1. Set document count
        String countText = docCount == 1 ? "1 doc secured" : docCount + " docs secured";
        views.setTextViewText(R.id.tv_widget_doc_count, countText);

        // 2. Set watcher status text and color
        if (watcherActive) {
            views.setTextViewText(R.id.tv_widget_watcher_status, context.getString(R.string.widget_watcher_on));
            views.setTextColor(R.id.tv_widget_watcher_status, Color.parseColor("#00E676")); // Green
        } else {
            views.setTextViewText(R.id.tv_widget_watcher_status, context.getString(R.string.widget_watcher_off));
            views.setTextColor(R.id.tv_widget_watcher_status, Color.parseColor("#9E9E9E")); // Muted
        }

        // 3. Attach PendingIntent to open the vault via biometric-gated LaunchActivity
        Intent launchIntent = new Intent(context, LaunchActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        views.setOnClickPendingIntent(R.id.btn_widget_open, pendingIntent);

        return views;
    }

    /**
     * Public static method to broadcast updates to all active home screen widgets
     * whenever vault contents or watcher status change.
     *
     * @param context Application context.
     * @param docCount Current count of documents secured in the vault.
     * @param watcherActive Whether the background WatcherService is currently alive.
     */
    public static void updateAllWidgets(@NonNull Context context, int docCount, boolean watcherActive) {
        try {
            // Cache state to SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(PREF_CACHED_DOC_COUNT, docCount)
                    .putBoolean(PREF_WATCHER_ON, watcherActive)
                    .apply();

            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName widgetComponent = new ComponentName(context, VaultWidgetProvider.class);
            int[] widgetIds = manager.getAppWidgetIds(widgetComponent);

            if (widgetIds != null && widgetIds.length > 0) {
                RemoteViews views = buildRemoteViews(context, docCount, watcherActive);
                manager.updateAppWidget(widgetIds, views);
                Log.d(TAG, "Pushed update to " + widgetIds.length + " active widgets: " + docCount + " docs.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update widgets", e);
        }
    }
}
