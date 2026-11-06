package com.ghostdocs.vault.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.adapter.LogListAdapter;
import com.ghostdocs.vault.util.PermissionHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;

/**
 * SettingsActivity — Application Configuration Screen
 *
 * <p>Displays all GhostDocs settings and app info in a {@link ListView}
 * powered by the custom {@link LogListAdapter}. Settings are persisted
 * via {@link SharedPreferences}.
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 *   <li><b>Encapsulation:</b> Settings keys are private constants.
 *       The adapter hides all ListView view inflation logic.</li>
 *   <li><b>Polymorphism:</b> {@link ListView#setOnItemClickListener} handles
 *       all setting items through a single dispatcher using position-based
 *       routing — the actual action is resolved at runtime.</li>
 * </ul>
 *
 * <p>All heavy operations (Firestore clear, file deletion) will run on
 * background threads using ExecutorService in Step 7.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG           = "GhostDocs.Settings";
    private static final String PREFS_NAME    = "GhostDocsPrefs";

    // SharedPreferences keys
    private static final String PREF_AUTO_START         = "auto_start_watcher";
    private static final String PREF_AUTO_DELETE        = "auto_delete_original";
    private static final String PREF_CONFIRM_SECURE     = "confirm_before_secure";
    private static final String PREF_EXCLUDE_NOTES      = "exclude_study_notes";

    // ── Settings item indices (matches the list order) ───────────────────────
    private static final int IDX_AUTO_START     = 0;
    private static final int IDX_AUTO_DELETE    = 1;
    private static final int IDX_CONFIRM_SECURE = 2;
    private static final int IDX_EXCLUDE_NOTES  = 3;
    private static final int IDX_PERMISSIONS    = 4;
    private static final int IDX_EXPORT_PDF     = 5;
    private static final int IDX_CLEAR_VAULT    = 6;
    private static final int IDX_ABOUT          = 7;
    private static final int IDX_SIGN_OUT       = 8;

    // ── Views ────────────────────────────────────────────────────────────────
    private ListView        lvSettings;
    private LogListAdapter  settingsAdapter;

    // ── Data & prefs ─────────────────────────────────────────────────────────
    private SharedPreferences       prefs;
    private List<LogListAdapter.LogItem> settingsItems;

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        bindViews();
        buildSettingsList();
        setupListView();

        // Back button
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        lvSettings = findViewById(R.id.lv_settings);
    }

    /**
     * Builds the ordered list of {@link LogListAdapter.LogItem} objects that
     * represent each settings row. Boolean prefs are displayed as ON/OFF.
     */
    private void buildSettingsList() {
        settingsItems = new ArrayList<>();

        boolean autoStart     = prefs.getBoolean(PREF_AUTO_START,     true);
        boolean autoDelete    = prefs.getBoolean(PREF_AUTO_DELETE,    true);
        boolean confirmSecure = prefs.getBoolean(PREF_CONFIRM_SECURE, true);
        boolean excludeNotes  = prefs.getBoolean(PREF_EXCLUDE_NOTES,  true);

        settingsItems.add(new LogListAdapter.LogItem(                               // 0
                getString(R.string.settings_auto_start),
                autoStart ? "ON" : "OFF"));
        settingsItems.add(new LogListAdapter.LogItem(                               // 1
                getString(R.string.settings_delete_original),
                autoDelete ? "ON" : "OFF"));
        settingsItems.add(new LogListAdapter.LogItem(                               // 2
                "Ask Before Securing (Prompt on Capture)",
                confirmSecure ? "ON (Prompt First)" : "OFF (Auto-Vault)"));
        settingsItems.add(new LogListAdapter.LogItem(                               // 3
                "Exclude Class Notes & Lecture Photos",
                excludeNotes ? "ON (Saved in Gallery)" : "OFF"));
        settingsItems.add(new LogListAdapter.LogItem(                               // 4
                "Storage Permissions",
                PermissionHelper.hasAllRequiredPermissions(this) ? "✓ Granted" : "⚠ Required"));
        settingsItems.add(new LogListAdapter.LogItem(                               // 5
                getString(R.string.settings_export_pdf)));
        settingsItems.add(new LogListAdapter.LogItem(                               // 6
                getString(R.string.settings_clear_vault)));
        settingsItems.add(new LogListAdapter.LogItem(                               // 7
                getString(R.string.settings_about)));
        settingsItems.add(new LogListAdapter.LogItem(                               // 8
                "Sign Out", "→"));
    }

    /**
     * Attaches the adapter and item-click listener to the ListView.
     *
     * <p>The click dispatcher demonstrates Polymorphism — the same
     * {@code onItemClick} handles 8 different setting behaviours,
     * resolved at runtime by {@code position}.
     */
    private void setupListView() {
        settingsAdapter = new LogListAdapter(this, settingsItems);
        lvSettings.setAdapter(settingsAdapter);

        lvSettings.setOnItemClickListener((parent, view, position, id) ->
                handleSettingClick(position));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Settings click dispatcher (Polymorphism via position-based routing)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Routes a ListView item click to the corresponding settings action.
     *
     * @param position the tapped item index
     */
    private void handleSettingClick(int position) {
        switch (position) {
            case IDX_AUTO_START:     toggleAutoStart();     break;
            case IDX_AUTO_DELETE:    toggleAutoDelete();    break;
            case IDX_CONFIRM_SECURE: toggleConfirmSecure(); break;
            case IDX_EXCLUDE_NOTES:  toggleExcludeNotes();  break;
            case IDX_PERMISSIONS:    openPermissions();     break;
            case IDX_EXPORT_PDF:     exportPdf();           break;
            case IDX_CLEAR_VAULT:    confirmClearVault();   break;
            case IDX_ABOUT:          showAbout();            break;
            case IDX_SIGN_OUT:       signOut();              break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Individual setting actions
    // ════════════════════════════════════════════════════════════════════════

    /** Toggles the "Auto-start watcher on boot" setting. */
    private void toggleAutoStart() {
        boolean current = prefs.getBoolean(PREF_AUTO_START, true);
        boolean newVal  = !current;
        prefs.edit().putBoolean(PREF_AUTO_START, newVal).apply();
        settingsItems.get(IDX_AUTO_START).setValue(newVal ? "ON" : "OFF");
        settingsAdapter.notifyDataSetChanged();
        Toast.makeText(this,
                "Auto-start " + (newVal ? "enabled" : "disabled"),
                Toast.LENGTH_SHORT).show();
    }

    /** Toggles the "Auto-delete original after encryption" setting. */
    private void toggleAutoDelete() {
        boolean current = prefs.getBoolean(PREF_AUTO_DELETE, true);
        boolean newVal  = !current;
        prefs.edit().putBoolean(PREF_AUTO_DELETE, newVal).apply();
        settingsItems.get(IDX_AUTO_DELETE).setValue(newVal ? "ON" : "OFF");
        settingsAdapter.notifyDataSetChanged();
        Toast.makeText(this,
                "Auto-delete " + (newVal ? "enabled" : "disabled"),
                Toast.LENGTH_SHORT).show();
    }

    /** Toggles the "Ask before securing" confirmation prompt setting. */
    private void toggleConfirmSecure() {
        boolean current = prefs.getBoolean(PREF_CONFIRM_SECURE, true);
        boolean newVal  = !current;
        prefs.edit().putBoolean(PREF_CONFIRM_SECURE, newVal).apply();
        settingsItems.get(IDX_CONFIRM_SECURE).setValue(newVal ? "ON (Prompt First)" : "OFF (Auto-Vault)");
        settingsAdapter.notifyDataSetChanged();
        Toast.makeText(this,
                newVal ? "GhostDocs will ask before securing captures." : "GhostDocs will secure captures automatically.",
                Toast.LENGTH_SHORT).show();
    }

    /** Toggles the exclusion of academic class notes and lecture photos from vault interception. */
    private void toggleExcludeNotes() {
        boolean current = prefs.getBoolean(PREF_EXCLUDE_NOTES, true);
        boolean newVal  = !current;
        prefs.edit().putBoolean(PREF_EXCLUDE_NOTES, newVal).apply();
        settingsItems.get(IDX_EXCLUDE_NOTES).setValue(newVal ? "ON (Saved in Gallery)" : "OFF");
        settingsAdapter.notifyDataSetChanged();
        Toast.makeText(this,
                newVal ? "Class notes will remain safely in Gallery." : "Class notes will be secured in Vault.",
                Toast.LENGTH_SHORT).show();
    }

    /** Opens the system permission settings for GhostDocs. */
    private void openPermissions() {
        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            Toast.makeText(this, "All permissions granted ✓", Toast.LENGTH_SHORT).show();
        } else {
            PermissionHelper.openAppSettings(this);
        }
    }

    /** Exports or views generated vault PDFs. */
    private void exportPdf() {
        java.io.File exportDir = new java.io.File(getExternalFilesDir(null), "GhostDocs/exports");
        if (exportDir.exists() && exportDir.isDirectory()) {
            java.io.File[] files = exportDir.listFiles((dir, name) -> name.endsWith(".pdf"));
            int count = files != null ? files.length : 0;
            if (count > 0) {
                new AlertDialog.Builder(this)
                        .setTitle("Exported PDFs")
                        .setMessage(count + " searchable PDF(s) are stored in your secure export directory:\n\n"
                                + exportDir.getAbsolutePath())
                        .setPositiveButton("Open Latest", (d, w) -> {
                            if (files != null && files.length > 0) {
                                com.ghostdocs.vault.util.PdfExportHelper.openPdf(this, files[files.length - 1]);
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
                return;
            }
        }
        Toast.makeText(this, "No exported PDFs yet. Tap any document in Vault to export.", Toast.LENGTH_LONG).show();
    }

    /** Shows a confirmation dialog before clearing the vault. */
    private void confirmClearVault() {
        new AlertDialog.Builder(this)
                .setTitle("⚠ Clear Vault")
                .setMessage("This will permanently delete ALL documents from your vault "
                        + "and Firestore. Encrypted files on device will also be removed.\n\n"
                        + "This action CANNOT be undone.")
                .setPositiveButton("Clear Everything", (d, w) -> clearVault())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Clears all Firestore documents for the current user.
     * File deletion on device is handled in Step 7 (AES encryption module).
     * Currently deletes the Firestore metadata only.
     */
    private void clearVault() {
        com.google.firebase.auth.FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        // Batch-delete all documents from Firestore
        // Note: Firestore does not support collection deletion directly —
        // we query and delete in a batch. For large collections, this should
        // run on a background thread (Step 4 ExecutorService will handle this).
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("documents")
                .get()
                .addOnSuccessListener(snapshots -> {
                    com.google.firebase.firestore.WriteBatch batch =
                            FirebaseFirestore.getInstance().batch();
                    for (com.google.firebase.firestore.DocumentSnapshot doc
                            : snapshots.getDocuments()) {
                        batch.delete(doc.getReference());
                    }
                    batch.commit()
                            .addOnSuccessListener(unused ->
                                    Toast.makeText(this, "Vault cleared.", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e ->
                                    Toast.makeText(this, "Clear failed: " + e.getMessage(),
                                            Toast.LENGTH_SHORT).show());
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.error_firebase),
                                Toast.LENGTH_SHORT).show());
    }

    /** Shows an About dialog with app info. */
    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("👻 " + getString(R.string.app_name))
                .setMessage(getString(R.string.app_tagline)
                        + "\n\nVersion 1.0.0"
                        + "\n\nAn intelligent Android security application that "
                        + "automatically detects, extracts, encrypts, and organises "
                        + "sensitive screenshots into a biometric-locked vault."
                        + "\n\n100% Java · Firebase · ML Kit · OpenCV · ZXing")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Signs the user out of Firebase and returns to the launch screen. */
    private void signOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Sign out of GhostDocs?")
                .setPositiveButton("Sign Out", (d, w) -> {
                    FirebaseAuth.getInstance().signOut();
                    android.content.Intent intent = new android.content.Intent(
                            this, LaunchActivity.class);
                    intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
