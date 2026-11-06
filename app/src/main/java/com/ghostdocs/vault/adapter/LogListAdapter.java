package com.ghostdocs.vault.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.R;

import java.util.List;

/**
 * LogListAdapter — Custom ArrayAdapter for the Settings & Log ListView
 *
 * <p>Powers the {@link android.widget.ListView} in {@code SettingsActivity}.
 * Each item displays a primary label and an optional secondary value/status.
 *
 * <p><b>OOP Design — Inheritance:</b>
 * Extends {@link ArrayAdapter}{@code <LogItem>} rather than
 * {@link ArrayAdapter}{@code <String>} to carry both a label and a value,
 * demonstrating that inheritance allows specialising generic base classes with
 * domain-specific behaviour while reusing the full ListView adapter lifecycle.
 *
 * <p><b>OOP Design — Encapsulation:</b>
 * The {@link LogItem} inner class encapsulates a label/value pair. External
 * code constructs items and passes the list to the adapter; it never manipulates
 * the TextViews directly.
 *
 * <p><b>ViewHolder pattern:</b> Although ListView has no built-in ViewHolder
 * support, we implement it manually via {@code convertView.getTag()} for smooth
 * scrolling — matching the pattern mandated by RecyclerView.
 */
public class LogListAdapter extends ArrayAdapter<LogListAdapter.LogItem> {

    // ════════════════════════════════════════════════════════════════════════
    // Inner data class (Encapsulation)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * LogItem — a simple value object pairing a label with an optional value.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code label="Auto-start Watcher", value="ON"}</li>
     *   <li>{@code label="Delete Original", value="OFF"}</li>
     *   <li>{@code label="Export Vault as PDF", value="›"}</li>
     * </ul>
     */
    public static class LogItem {
        private final String label;
        private String value;

        public LogItem(@NonNull String label, @Nullable String value) {
            this.label = label;
            this.value = value != null ? value : "›";
        }

        /** Convenience constructor for navigation-only items (shows "›"). */
        public LogItem(@NonNull String label) {
            this(label, "›");
        }

        public String getLabel() { return label; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ── Manual ViewHolder (Encapsulation — hides view references) ────────────
    private static class ViewHolder {
        TextView tvLabel;
        TextView tvValue;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param context  the host context
     * @param items    list of {@link LogItem} objects to display
     */
    public LogListAdapter(@NonNull Context context, @NonNull List<LogItem> items) {
        super(context, 0, items);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ArrayAdapter overrides
    // ════════════════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView,
                        @NonNull ViewGroup parent) {

        ViewHolder holder;

        if (convertView == null) {
            // Inflate the item layout only when no recycled view is available
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_log, parent, false);

            holder = new ViewHolder();
            holder.tvLabel = convertView.findViewById(R.id.tv_log_label);
            holder.tvValue = convertView.findViewById(R.id.tv_log_value);
            convertView.setTag(holder); // Cache for reuse (ViewHolder pattern)
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        LogItem item = getItem(position);
        if (item != null) {
            holder.tvLabel.setText(item.getLabel());
            holder.tvValue.setText(item.getValue());
        }

        return convertView;
    }
}
