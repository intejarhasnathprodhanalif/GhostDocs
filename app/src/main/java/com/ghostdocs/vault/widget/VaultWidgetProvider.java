package com.ghostdocs.vault.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

import com.ghostdocs.vault.R;

/**
 * VaultWidgetProvider — Home Screen App Widget
 *
 * <p>Provides a home screen widget that displays real-time vault statistics
 * (document count, watcher status) and a quick-launch button to open the vault.
 *
 * <p>Widget metadata is declared in {@code res/xml/ghostdocs_widget_info.xml}.
 * Layout is defined in {@code res/layout/widget_vault.xml}.
 *
 * <p><b>Current behavior (Step 1 stub):</b> Shows static placeholder text.
 *
 * <p><b>Full implementation (Step 9):</b> Will:
 * <ul>
 *   <li>Query Firestore for the live document count.</li>
 *   <li>Read watcher status from Firebase Realtime Database.</li>
 *   <li>Wire the open-vault button to a PendingIntent for VaultActivity.</li>
 * </ul>
 *
 * <p>OOP Note: Extends {@link AppWidgetProvider} (Inheritance), which itself
 * extends {@link android.content.BroadcastReceiver}. Update logic will be
 * extracted to a static helper method (Encapsulation) in Step 9.
 */
public class VaultWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context,
                         AppWidgetManager appWidgetManager,
                         int[] appWidgetIds) {

        // Update each widget instance independently
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    /**
     * Builds and pushes the RemoteViews for a single widget instance.
     *
     * <p>{@link RemoteViews} only supports a limited subset of View classes
     * (TextView, ImageView, Button, ProgressBar, etc.) — no custom views.
     *
     * @param context          application context
     * @param appWidgetManager the system widget manager
     * @param appWidgetId      the specific widget instance to update
     */
    private static void updateWidget(Context context,
                                     AppWidgetManager appWidgetManager,
                                     int appWidgetId) {

        RemoteViews views = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_vault
        );

        // Stub: set placeholder text — replaced with live data in Step 9
        views.setTextViewText(R.id.tv_widget_doc_count,
                context.getString(R.string.widget_doc_count, 0));
        views.setTextViewText(R.id.tv_widget_watcher_status,
                context.getString(R.string.widget_watcher_off));

        // Push the updated RemoteViews to the widget
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onEnabled(Context context) {
        // Called when the first widget instance is added to the home screen
        super.onEnabled(context);
    }

    @Override
    public void onDisabled(Context context) {
        // Called when the last widget instance is removed from the home screen
        super.onDisabled(context);
    }
}
