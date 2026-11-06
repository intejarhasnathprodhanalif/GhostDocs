package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.adapter.VaultRecyclerAdapter;
import com.ghostdocs.vault.model.DocumentModel;
import com.ghostdocs.vault.service.WatcherService;
import com.ghostdocs.vault.util.PermissionHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VaultActivity — Main Secure Vault Dashboard
 *
 * <p>The primary screen of GhostDocs. Reached only after passing Firebase Auth
 * + BiometricPrompt in {@link LaunchActivity}. Displays all encrypted documents
 * in a 2-column RecyclerView grid, synchronised in real-time with Cloud Firestore.
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 *   <li><b>Encapsulation:</b> Firestore listener registration, adapter state,
 *       and watcher service state are all private — externally inaccessible.</li>
 *   <li><b>Polymorphism:</b> Implements {@link VaultRecyclerAdapter.OnDocumentClickListener}
 *       — the two interface methods are resolved at runtime for click vs. long-click.</li>
 * </ul>
 *
 * <p><b>Firestore Collection Path:</b>
 * {@code users/{uid}/documents} — ordered by {@code timestamp} descending.
 * Offline persistence is enabled in {@link com.ghostdocs.vault.GhostDocsApp}.
 */
public class VaultActivity extends AppCompatActivity
        implements VaultRecyclerAdapter.OnDocumentClickListener {

    private static final String TAG = "GhostDocs.Vault";
    private static final String PREFS_NAME      = "GhostDocsPrefs";
    private static final String PREF_WATCHER_ON = "watcher_enabled";
    private static final int    GRID_SPAN_COUNT = 2;

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView              rvVault;
    private View                      layoutEmptyState;
    private TextView                  tvDocCount;
    private TextView                  tvWatcherStatus;
    private FloatingActionButton      fabWatcherToggle;

    // ── Adapter & data ───────────────────────────────────────────────────────
    private VaultRecyclerAdapter      vaultAdapter;
    private final List<DocumentModel> documentList = new ArrayList<>();

    // ── Firebase ─────────────────────────────────────────────────────────────
    private FirebaseAuth              firebaseAuth;
    private FirebaseFirestore         firestore;
    private ListenerRegistration      firestoreListener;

    // ── Watcher state ────────────────────────────────────────────────────────
    private boolean isWatcherRunning = false;

    // ── Permission launcher ──────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::handlePermissionResults
            );

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault);

        firebaseAuth = FirebaseAuth.getInstance();
        firestore    = FirebaseFirestore.getInstance();

        bindViews();
        setupRecyclerView();
        setClickListeners();

        // Request runtime permissions on first launch
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            PermissionHelper.requestAllPermissions(this, permissionLauncher);
        }

        // Restore watcher state from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isWatcherRunning = prefs.getBoolean(PREF_WATCHER_ON, false);
        updateWatcherUI();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Guard: if somehow user reaches this without auth, bounce back to launch
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            redirectToLaunch();
            return;
        }
        setupFirestoreListener(user.getUid());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // IMPORTANT: Remove Firestore listener to prevent memory leaks and
        // unnecessary billing when the activity is not visible
        if (firestoreListener != null) {
            firestoreListener.remove();
            firestoreListener = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Re-query if launched from widget or notification (singleTop)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup methods
    // ════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        rvVault           = findViewById(R.id.rv_vault);
        layoutEmptyState  = findViewById(R.id.layout_empty_state);
        tvDocCount        = findViewById(R.id.tv_doc_count);
        tvWatcherStatus   = findViewById(R.id.tv_watcher_status);
        fabWatcherToggle  = findViewById(R.id.fab_watcher_toggle);
    }

    /**
     * Configures the RecyclerView with a 2-column GridLayoutManager and
     * the VaultRecyclerAdapter backed by an initially empty list.
     * The adapter is seeded with live data once the Firestore listener fires.
     */
    private void setupRecyclerView() {
        vaultAdapter = new VaultRecyclerAdapter(this, documentList);
        vaultAdapter.setOnDocumentClickListener(this);

        GridLayoutManager layoutManager =
                new GridLayoutManager(this, GRID_SPAN_COUNT);

        rvVault.setLayoutManager(layoutManager);
        rvVault.setAdapter(vaultAdapter);
        rvVault.setHasFixedSize(false);
    }

    private void setClickListeners() {
        // Toolbar — settings
        findViewById(R.id.btn_open_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Toolbar — sign out
        findViewById(R.id.btn_sign_out).setOnClickListener(v -> confirmSignOut());

        // FAB — toggle watcher service
        fabWatcherToggle.setOnClickListener(v -> toggleWatcherService());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Firestore real-time sync
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Attaches a real-time Firestore {@link com.google.firebase.firestore.SnapshotListener}
     * to the authenticated user's document collection.
     *
     * <p>The listener fires immediately with cached data (thanks to offline
     * persistence enabled in {@link com.ghostdocs.vault.GhostDocsApp}), then
     * again whenever the server data changes — keeping the grid always in sync.
     *
     * <p>Collection path: {@code users/{uid}/documents}
     * Ordered: {@code timestamp DESC} (newest documents first).
     *
     * @param userId the Firebase Auth UID of the current user
     */
    private void setupFirestoreListener(String userId) {
        firestoreListener = firestore
                .collection("users")
                .document(userId)
                .collection("documents")
                .orderBy(DocumentModel.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, error) -> {

                    if (error != null) {
                        Log.e(TAG, "Firestore listener error.", error);
                        Toast.makeText(this,
                                getString(R.string.error_firebase),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (snapshots != null) {
                        // Firestore auto-maps documents to DocumentModel via reflection
                        // (requires the no-arg constructor in DocumentModel)
                        List<DocumentModel> freshList =
                                snapshots.toObjects(DocumentModel.class);

                        // Inject docId from document reference (Firestore doesn't
                        // include the ID in the document fields by default)
                        for (int i = 0; i < freshList.size(); i++) {
                            freshList.get(i).setDocId(
                                    snapshots.getDocuments().get(i).getId());
                        }

                        vaultAdapter.updateData(freshList);
                        updateEmptyState(freshList.isEmpty());
                        updateDocCount(freshList.size());
                        Log.d(TAG, "Vault synced: " + freshList.size() + " documents.");
                    }
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // VaultRecyclerAdapter.OnDocumentClickListener (Polymorphism)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Single tap — open document detail / PDF viewer.
     * Full implementation in Step 8 (PDF generation).
     */
    @Override
    public void onDocumentClick(DocumentModel document, int position) {
        Log.d(TAG, "Document tapped: " + document.getTitle());
        // TODO (Step 8): Open PDF viewer / document detail screen
        Toast.makeText(this,
                "📄 " + document.getTitle() + "\n" + document.getFormattedDate(),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Long press — show contextual menu (share QR, delete, export PDF).
     * Full implementation in Steps 7–8.
     */
    @Override
    public void onDocumentLongClick(DocumentModel document, int position) {
        Log.d(TAG, "Document long-pressed: " + document.getTitle());
        new AlertDialog.Builder(this)
                .setTitle(document.getTitle())
                .setItems(new String[]{"📱 Share QR", "📄 Export PDF", "🗑 Delete"},
                        (dialog, which) -> {
                            switch (which) {
                                case 0: Toast.makeText(this, "QR — coming in Step 9", Toast.LENGTH_SHORT).show(); break;
                                case 1: Toast.makeText(this, "PDF — coming in Step 8", Toast.LENGTH_SHORT).show(); break;
                                case 2: confirmDeleteDocument(document); break;
                            }
                        })
                .show();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Watcher Service control
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Toggles the {@link WatcherService} on or off and persists the new state
     * to {@link SharedPreferences}.
     */
    private void toggleWatcherService() {
        isWatcherRunning = !isWatcherRunning;
        Intent serviceIntent = new Intent(this, WatcherService.class);

        if (isWatcherRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
        }

        // Persist state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_WATCHER_ON, isWatcherRunning)
                .apply();

        updateWatcherUI();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Document deletion
    // ════════════════════════════════════════════════════════════════════════

    private void confirmDeleteDocument(DocumentModel document) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Document")
                .setMessage("Permanently delete \"" + document.getTitle()
                        + "\" from your vault? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) ->
                        deleteDocument(document))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Deletes the Firestore metadata document for the given entry.
     * The encrypted file on disk is left in place here — full cleanup
     * (including encrypted file deletion) is handled in Step 7.
     */
    private void deleteDocument(DocumentModel document) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null || document.getDocId() == null) return;

        // Clean up encrypted file on device
        if (document.getEncryptedPath() != null && !document.getEncryptedPath().isEmpty()) {
            java.io.File encFile = new java.io.File(document.getEncryptedPath());
            if (encFile.exists()) {
                boolean deleted = encFile.delete();
                Log.d(TAG, "Local encrypted file deleted from disk: " + deleted);
            }
        }

        firestore.collection("users")
                .document(user.getUid())
                .collection("documents")
                .document(document.getDocId())
                .delete()
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "Document deleted from Firestore: " + document.getDocId());
                    Toast.makeText(this, "Document and encrypted file deleted.", Toast.LENGTH_SHORT).show();
                    // The SnapshotListener will automatically refresh the RecyclerView
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete document.", e);
                    Toast.makeText(this, "Delete failed. Try again.", Toast.LENGTH_SHORT).show();
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sign-out
    // ════════════════════════════════════════════════════════════════════════

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out of GhostDocs?")
                .setPositiveButton("Sign Out", (dialog, which) -> signOut())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void signOut() {
        // Stop the watcher before signing out
        if (isWatcherRunning) {
            stopService(new Intent(this, WatcherService.class));
        }
        firebaseAuth.signOut();
        redirectToLaunch();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Permission handling
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Processes the results from the multi-permission launcher.
     *
     * @param results a map of permission name → granted boolean
     */
    private void handlePermissionResults(Map<String, Boolean> results) {
        boolean allGranted = true;
        for (Boolean granted : results.values()) {
            if (!granted) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            Log.w(TAG, "Some permissions denied.");
            Toast.makeText(this,
                    getString(R.string.error_permission_denied),
                    Toast.LENGTH_LONG).show();
        }

        // Check MANAGE_EXTERNAL_STORAGE separately (requires Settings screen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !PermissionHelper.hasManageStoragePermission(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("GhostDocs needs 'All Files Access' to protect your documents. "
                            + "Please enable it in Settings.")
                    .setPositiveButton("Open Settings",
                            (d, w) -> PermissionHelper.openManageStorageSettings(this))
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI helpers
    // ════════════════════════════════════════════════════════════════════════

    private void updateEmptyState(boolean isEmpty) {
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvVault.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void updateDocCount(int count) {
        tvDocCount.setText(getString(R.string.vault_doc_count, count));
    }

    private void updateWatcherUI() {
        if (isWatcherRunning) {
            tvWatcherStatus.setText(getString(R.string.widget_watcher_on));
            tvWatcherStatus.setTextColor(getResources().getColor(R.color.ghostSuccess, getTheme()));
            fabWatcherToggle.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            tvWatcherStatus.setText(getString(R.string.widget_watcher_off));
            tvWatcherStatus.setTextColor(getResources().getColor(R.color.ghostOnSurfaceMuted, getTheme()));
            fabWatcherToggle.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void redirectToLaunch() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
