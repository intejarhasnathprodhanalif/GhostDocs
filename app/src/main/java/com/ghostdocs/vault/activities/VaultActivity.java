package com.ghostdocs.vault.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.adapter.CategoryChipAdapter;
import com.ghostdocs.vault.adapter.VaultRecyclerAdapter;
import com.ghostdocs.vault.model.CategoryChipModel;
import com.ghostdocs.vault.model.DocumentModel;
import com.ghostdocs.vault.security.AesEncryptionHelper;
import com.ghostdocs.vault.service.WatcherService;
import com.ghostdocs.vault.tasks.BaseVaultTask;
import com.ghostdocs.vault.tasks.PdfGeneratorTask;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.PdfExportHelper;
import com.ghostdocs.vault.util.PermissionHelper;
import com.ghostdocs.vault.util.QrCodeGenerator;
import com.ghostdocs.vault.widget.VaultWidgetProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private RecyclerView              rvCategoryChips;
    private View                      layoutEmptyState;
    private TextView                  tvDocCount;
    private TextView                  tvWatcherStatus;
    private FloatingActionButton      fabWatcherToggle;
    private EditText                  etSearchVault;

    // ── Adapter & data ───────────────────────────────────────────────────────
    private VaultRecyclerAdapter      vaultAdapter;
    private CategoryChipAdapter       categoryChipAdapter;
    private final List<DocumentModel> masterDocumentList   = new ArrayList<>();
    private final List<DocumentModel> filteredDocumentList = new ArrayList<>();
    private String                    currentSearchQuery   = "";
    private String                    selectedCategoryId   = CategoryChipModel.CATEGORY_ALL;

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
        setupCategoryChips();
        setupSearchFilter();
        setClickListeners();

        // Request runtime permissions on first launch
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            PermissionHelper.requestAllPermissions(this, permissionLauncher);
        }

        // Restore watcher state from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isWatcherRunning = prefs.getBoolean(PREF_WATCHER_ON, false);
        updateWatcherUI();
        VaultWidgetProvider.updateAllWidgets(this, masterDocumentList.size(), isWatcherRunning);
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
        rvCategoryChips   = findViewById(R.id.rv_category_chips);
        layoutEmptyState  = findViewById(R.id.layout_empty_state);
        tvDocCount        = findViewById(R.id.tv_doc_count);
        tvWatcherStatus   = findViewById(R.id.tv_watcher_status);
        fabWatcherToggle  = findViewById(R.id.fab_watcher_toggle);
        etSearchVault     = findViewById(R.id.et_search_vault);
    }

    private void setupCategoryChips() {
        if (rvCategoryChips != null) {
            categoryChipAdapter = new CategoryChipAdapter(this, categoryId -> {
                selectedCategoryId = categoryId;
                applyFilters();
            });
            LinearLayoutManager layoutManager =
                    new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            rvCategoryChips.setLayoutManager(layoutManager);
            rvCategoryChips.setAdapter(categoryChipAdapter);
        }
    }

    /**
     * Configures the RecyclerView with a 2-column GridLayoutManager and
     * the VaultRecyclerAdapter backed by an initially empty list.
     * The adapter is seeded with live data once the Firestore listener fires.
     */
    private void setupRecyclerView() {
        vaultAdapter = new VaultRecyclerAdapter(this, filteredDocumentList);
        vaultAdapter.setOnDocumentClickListener(this);

        GridLayoutManager layoutManager =
                new GridLayoutManager(this, GRID_SPAN_COUNT);

        rvVault.setLayoutManager(layoutManager);
        rvVault.setAdapter(vaultAdapter);
        rvVault.setHasFixedSize(false);
    }

    private void setupSearchFilter() {
        if (etSearchVault != null) {
            etSearchVault.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s != null ? s.toString() : "";
                    applyFilters();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    /**
     * Filters documents across selected category and real-time search query.
     */
    private void applyFilters() {
        String cleanQuery = currentSearchQuery != null ? currentSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        filteredDocumentList.clear();

        for (DocumentModel doc : masterDocumentList) {
            // 1. Category check
            boolean matchesCategory = CategoryChipModel.CATEGORY_ALL.equalsIgnoreCase(selectedCategoryId);
            if (!matchesCategory && doc.getDocType() != null) {
                matchesCategory = doc.getDocType().equalsIgnoreCase(selectedCategoryId);
            }
            if (!matchesCategory) {
                continue;
            }

            // 2. Search query check
            if (cleanQuery.isEmpty()) {
                filteredDocumentList.add(doc);
            } else {
                boolean matchesTitle = doc.getTitle() != null
                        && doc.getTitle().toLowerCase(Locale.ROOT).contains(cleanQuery);
                boolean matchesType = doc.getDocType() != null
                        && doc.getDocType().toLowerCase(Locale.ROOT).contains(cleanQuery);
                boolean matchesTags = false;
                if (doc.getTags() != null) {
                    for (String tag : doc.getTags()) {
                        if (tag != null && tag.toLowerCase(Locale.ROOT).contains(cleanQuery)) {
                            matchesTags = true;
                            break;
                        }
                    }
                }
                boolean matchesOcr = doc.getOcrText() != null
                        && doc.getOcrText().toLowerCase(Locale.ROOT).contains(cleanQuery);

                if (matchesTitle || matchesType || matchesTags || matchesOcr) {
                    filteredDocumentList.add(doc);
                }
            }
        }

        vaultAdapter.updateData(filteredDocumentList);
        updateEmptyState(filteredDocumentList.isEmpty());
        if (cleanQuery.isEmpty() && CategoryChipModel.CATEGORY_ALL.equalsIgnoreCase(selectedCategoryId)) {
            updateDocCount(masterDocumentList.size());
        } else {
            tvDocCount.setText(filteredDocumentList.size() + " of " + masterDocumentList.size() + " found");
        }
    }

    private void updateCategoryCounts() {
        if (categoryChipAdapter == null) return;
        Map<String, Integer> counts = new HashMap<>();
        for (DocumentModel doc : masterDocumentList) {
            if (doc.getDocType() != null) {
                String type = doc.getDocType().toUpperCase(Locale.ROOT);
                counts.put(type, counts.getOrDefault(type, 0) + 1);
            }
        }
        categoryChipAdapter.updateCounts(counts, masterDocumentList.size());
    }

    private void setClickListeners() {
        // Toolbar — import old photos
        View btnImportPhotos = findViewById(R.id.btn_import_photos);
        if (btnImportPhotos != null) {
            btnImportPhotos.setOnClickListener(v ->
                    startActivity(new Intent(this, ImportPhotosActivity.class)));
        }

        // Empty state — quick import button
        View btnEmptyImport = findViewById(R.id.btn_empty_import);
        if (btnEmptyImport != null) {
            btnEmptyImport.setOnClickListener(v ->
                    startActivity(new Intent(this, ImportPhotosActivity.class)));
        }

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
                        List<DocumentModel> freshList =
                                snapshots.toObjects(DocumentModel.class);

                        for (int i = 0; i < freshList.size(); i++) {
                            freshList.get(i).setDocId(
                                    snapshots.getDocuments().get(i).getId());
                        }

                        masterDocumentList.clear();
                        masterDocumentList.addAll(freshList);
                        updateCategoryCounts();
                        applyFilters();

                        VaultWidgetProvider.updateAllWidgets(this, freshList.size(), isWatcherRunning);
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

        boolean hasValidPdf = document.getPdfPath() != null
                && !document.getPdfPath().isEmpty()
                && new File(document.getPdfPath()).exists();

        boolean hasValidEncrypted = document.getEncryptedPath() != null
                && !document.getEncryptedPath().isEmpty()
                && new File(document.getEncryptedPath()).exists();

        // If no encrypted data or PDF exists on disk, auto-remove this orphaned document
        if (!hasValidPdf && !hasValidEncrypted) {
            handleMissingDocumentData(document);
            return;
        }

        // If searchable PDF exists, open it directly in system viewer
        if (hasValidPdf) {
            File pdfFile = new File(document.getPdfPath());
            PdfExportHelper.openPdf(this, pdfFile);
            return;
        }

        // Otherwise generate on-demand or show info
        exportOrSharePdf(document);
    }

    /**
     * Long press — show contextual menu (rename, share QR, export JPG, export PDF, delete).
     */
    @Override
    public void onDocumentLongClick(DocumentModel document, int position) {
        Log.d(TAG, "Document long-pressed: " + document.getTitle());
        new AlertDialog.Builder(this)
                .setTitle(document.getTitle())
                .setItems(new String[]{
                        "✏️ Rename Document",
                        "📱 Share QR (Air-Gapped)",
                        "🖼️ Export / Share JPG",
                        "📄 Open / Export PDF",
                        "🗑 Delete"
                }, (dialog, which) -> {
                    switch (which) {
                        case 0: promptRenameDocument(document); break;
                        case 1: showQrShareDialog(document); break;
                        case 2: exportOrShareJpg(document); break;
                        case 3: exportOrSharePdf(document); break;
                        case 4: confirmDeleteDocument(document); break;
                    }
                })
                .show();
    }

    /**
     * Shows a dialog allowing the user to rename the document.
     */
    private void promptRenameDocument(@NonNull DocumentModel document) {
        final EditText input = new EditText(this);
        input.setText(document.getTitle());
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setTextColor(getResources().getColor(R.color.ghostOnSurface, getTheme()));
        input.setHintTextColor(getResources().getColor(R.color.ghostOnSurfaceMuted, getTheme()));

        RelativeLayout container = new RelativeLayout(this);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        int marginPx = (int) (20 * getResources().getDisplayMetrics().density);
        params.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Rename Document")
                .setMessage("Enter a new name for this secured document:")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newTitle = input.getText().toString().trim();
                    if (newTitle.isEmpty()) {
                        Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    updateDocumentTitle(document, newTitle);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Updates the document's title in Cloud Firestore and syncs local state.
     */
    private void updateDocumentTitle(@NonNull DocumentModel document, @NonNull String newTitle) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null || document.getDocId() == null) {
            Toast.makeText(this, "Unable to rename: document not found", Toast.LENGTH_SHORT).show();
            return;
        }

        firestore.collection("users")
                .document(user.getUid())
                .collection("documents")
                .document(document.getDocId())
                .update(DocumentModel.FIELD_TITLE, newTitle)
                .addOnSuccessListener(aVoid -> {
                    document.setTitle(newTitle);
                    vaultAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Renamed to: " + newTitle, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Document " + document.getDocId() + " renamed to " + newTitle);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to rename document", e);
                    Toast.makeText(this, "Failed to rename: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Displays a QR code modal allowing cloud picture viewing in a web browser or air-gapped verification.
     */
    private void showQrShareDialog(DocumentModel document) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_qr_share, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_qr_title);
        TextView tvType = dialogView.findViewById(R.id.tv_qr_type);
        TextView btnModeCloud = dialogView.findViewById(R.id.btn_mode_cloud);
        TextView btnModeAirgap = dialogView.findViewById(R.id.btn_mode_airgap);
        ImageView ivQr = dialogView.findViewById(R.id.iv_qr_code);
        ProgressBar pbQr = dialogView.findViewById(R.id.pb_qr_loading);
        TextView tvInstructions = dialogView.findViewById(R.id.tv_qr_instructions);
        TextView btnCopyLink = dialogView.findViewById(R.id.btn_qr_copy_link);
        TextView btnClose = dialogView.findViewById(R.id.btn_qr_close);

        tvTitle.setText(document.getTitle());
        tvType.setText(document.getDocType());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnCopyLink.setOnClickListener(v -> {
            if (document.getShareUrl() != null && !document.getShareUrl().isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("GhostDocs Picture Link", document.getShareUrl());
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Picture link copied to clipboard!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Runnable for Air-Gapped mode
        Runnable showAirGappedQr = () -> {
            btnModeAirgap.setTextColor(getResources().getColor(R.color.ghostPrimary, getTheme()));
            btnModeCloud.setTextColor(getResources().getColor(R.color.ghostOnSurfaceMuted, getTheme()));
            btnCopyLink.setVisibility(View.GONE);
            pbQr.setVisibility(View.VISIBLE);

            VaultExecutor.getInstance().execute(() -> {
                Bitmap qrBitmap = QrCodeGenerator.generateVerificationCardQr(document, 512);
                runOnUiThread(() -> {
                    pbQr.setVisibility(View.GONE);
                    if (qrBitmap != null) {
                        ivQr.setImageBitmap(qrBitmap);
                    }
                    tvInstructions.setText("📱 Air-Gapped Card: Scan to verify document details without internet.");
                });
            });
        };

        // Runnable for Cloud Browser mode
        Runnable showCloudQr = () -> {
            btnModeCloud.setTextColor(getResources().getColor(R.color.ghostPrimary, getTheme()));
            btnModeAirgap.setTextColor(getResources().getColor(R.color.ghostOnSurfaceMuted, getTheme()));
            pbQr.setVisibility(View.VISIBLE);

            if (document.getShareUrl() != null && !document.getShareUrl().isEmpty()) {
                btnCopyLink.setVisibility(View.VISIBLE);
                VaultExecutor.getInstance().execute(() -> {
                    Bitmap qrBitmap = QrCodeGenerator.generateCloudUrlQr(document.getShareUrl(), 512);
                    runOnUiThread(() -> {
                        pbQr.setVisibility(View.GONE);
                        if (qrBitmap != null) {
                            ivQr.setImageBitmap(qrBitmap);
                        }
                        tvInstructions.setText("🌐 Cloud Picture: Scan with any camera to view the picture in their browser!");
                    });
                });
            } else {
                tvInstructions.setText("Preparing cloud link for browser viewing...");
                btnCopyLink.setVisibility(View.GONE);
                uploadAndGenerateCloudQr(document, ivQr, pbQr, tvInstructions, btnCopyLink, showAirGappedQr);
            }
        };

        btnModeCloud.setOnClickListener(v -> showCloudQr.run());
        btnModeAirgap.setOnClickListener(v -> showAirGappedQr.run());

        // Default: Open in Air-Gapped Verification Card mode (100% offline, free, zero credit card)
        showAirGappedQr.run();

        dialog.show();
    }

    /**
     * Decrypts the document image, uploads it to Firebase Storage, and renders a QR code
     * containing the signed download URL.
     */
    private void uploadAndGenerateCloudQr(DocumentModel document,
                                          ImageView ivQr,
                                          ProgressBar pbQr,
                                          TextView tvInstructions,
                                          TextView btnCopyLink,
                                          Runnable fallbackToAirGapped) {
        VaultExecutor.getInstance().execute(() -> {
            try {
                if (document.getEncryptedPath() == null || document.getEncryptedPath().isEmpty()) {
                    throw new IllegalStateException("No encrypted file path available.");
                }

                File encFile = new File(document.getEncryptedPath());
                if (!encFile.exists()) {
                    throw new IllegalStateException("Encrypted file not found on disk.");
                }

                // 1. Decrypt AES-256-GCM ciphertext back into Bitmap
                Bitmap bitmap = AesEncryptionHelper.getInstance().decryptFileToBitmap(encFile);
                if (bitmap == null) {
                    throw new IllegalStateException("Decryption failed to produce bitmap.");
                }

                // 2. Compress to JPEG
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                byte[] jpegBytes = baos.toByteArray();

                // 3. Upload to Firebase Storage
                FirebaseUser user = firebaseAuth.getCurrentUser();
                String uid = user != null ? user.getUid() : "anonymous";

                // Explicitly target the configured storage bucket (e.g. ghostdocs-57c78.firebasestorage.app)
                String bucket = FirebaseApp.getInstance().getOptions().getStorageBucket();
                FirebaseStorage storage;
                if (bucket != null && !bucket.isEmpty()) {
                    String gsUrl = bucket.startsWith("gs://") ? bucket : "gs://" + bucket;
                    storage = FirebaseStorage.getInstance(gsUrl);
                } else {
                    storage = FirebaseStorage.getInstance();
                }

                StorageReference storageRef = storage
                        .getReference()
                        .child("shares")
                        .child(uid)
                        .child(document.getDocId() + ".jpg");

                runOnUiThread(() -> {
                    storageRef.putBytes(jpegBytes)
                            .continueWithTask(task -> {
                                if (!task.isSuccessful() && task.getException() != null) {
                                    throw task.getException();
                                }
                                return storageRef.getDownloadUrl();
                            })
                            .addOnSuccessListener(downloadUri -> {
                                String url = downloadUri.toString();
                                document.setShareUrl(url);

                                // Persist shareUrl in Firestore
                                if (user != null && document.getDocId() != null) {
                                    firestore.collection("users")
                                            .document(uid)
                                            .collection("documents")
                                            .document(document.getDocId())
                                            .update(DocumentModel.FIELD_SHARE_URL, url);
                                }

                                // Generate QR code for the download URL
                                VaultExecutor.getInstance().execute(() -> {
                                    Bitmap qrBitmap = QrCodeGenerator.generateCloudUrlQr(url, 512);
                                    runOnUiThread(() -> {
                                        pbQr.setVisibility(View.GONE);
                                        if (qrBitmap != null) {
                                            ivQr.setImageBitmap(qrBitmap);
                                        }
                                        tvInstructions.setText("🌐 Cloud Picture: Scan with any camera to view the picture in their browser!");
                                        btnCopyLink.setVisibility(View.VISIBLE);
                                    });
                                });
                            })
                            .addOnFailureListener(e -> {
                                String errorMsg = e != null && e.getMessage() != null ? e.getMessage() : "Unknown storage error";
                                Log.w(TAG, "Firebase Storage upload failed: " + errorMsg, e);
                                Toast.makeText(VaultActivity.this,
                                        "Storage error: " + errorMsg + "\nCheck Firebase Console Storage setup.",
                                        Toast.LENGTH_LONG).show();
                                fallbackToAirGapped.run();
                            });
                });

            } catch (Exception ex) {
                String errorMsg = ex != null && ex.getMessage() != null ? ex.getMessage() : "Internal error";
                Log.e(TAG, "Error preparing image for cloud QR share: " + errorMsg, ex);
                runOnUiThread(() -> {
                    Toast.makeText(VaultActivity.this,
                            "Cloud share error: " + errorMsg,
                            Toast.LENGTH_LONG).show();
                    fallbackToAirGapped.run();
                });
            }
        });
    }

    /**
     * Exports or shares the searchable PDF.
     */
    private void exportOrSharePdf(DocumentModel document) {
        // If file already exists on disk
        if (document.getPdfPath() != null && !document.getPdfPath().isEmpty()) {
            File existingPdf = new File(document.getPdfPath());
            if (existingPdf.exists()) {
                showPdfActionDialog(existingPdf, document.getTitle());
                return;
            }
        }

        // If PDF not generated yet, generate on-demand from encrypted file
        if (document.getEncryptedPath() != null && !document.getEncryptedPath().isEmpty()) {
            File encFile = new File(document.getEncryptedPath());
            if (encFile.exists()) {
                Toast.makeText(this, "Generating searchable PDF...", Toast.LENGTH_SHORT).show();

                PdfGeneratorTask task = new PdfGeneratorTask(
                        this,
                        encFile,
                        Collections.emptyList(),
                        document.getDocId(),
                        document.getTitle(),
                        new BaseVaultTask.TaskCallback<File>() {
                            @Override
                            public void onTaskSuccess(File pdfResult) {
                                document.setPdfPath(pdfResult.getAbsolutePath());
                                showPdfActionDialog(pdfResult, document.getTitle());
                            }

                            @Override
                            public void onTaskError(Throwable error) {
                                Toast.makeText(VaultActivity.this,
                                        "PDF generation failed: " + error.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                );
                VaultExecutor.getInstance().execute(task);
                return;
            }
        }

        // No encrypted data or PDF found on disk -> auto-remove orphaned document
        handleMissingDocumentData(document);
    }

    private void showPdfActionDialog(File pdfFile, String title) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Searchable PDF ready. What would you like to do?")
                .setPositiveButton("Open PDF", (d, w) -> PdfExportHelper.openPdf(this, pdfFile))
                .setNeutralButton("Share", (d, w) -> PdfExportHelper.sharePdf(this, pdfFile, title))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ════════════════════════════════════════════════════════════════════════
    // JPG Export & Gallery Save
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Exports the document as a high-resolution JPG image, allowing the user to share it
     * via Quick Share/WhatsApp/Bluetooth or save it directly to the device Gallery.
     */
    private void exportOrShareJpg(DocumentModel document) {
        boolean hasValidEncrypted = document.getEncryptedPath() != null
                && !document.getEncryptedPath().isEmpty()
                && new File(document.getEncryptedPath()).exists();

        if (!hasValidEncrypted) {
            handleMissingDocumentData(document);
            return;
        }

        File encFile = new File(document.getEncryptedPath());

        new AlertDialog.Builder(this)
                .setTitle("Export as JPG Image")
                .setMessage("Export \"" + document.getTitle() + "\" as a JPG image:")
                .setPositiveButton("Share JPG", (dialog, which) -> shareJpgImage(document, encFile))
                .setNeutralButton("Save to Gallery", (dialog, which) -> saveJpgToGallery(document, encFile))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Automatically removes a document entry from the vault when its underlying
     * encrypted data is missing from disk, rather than displaying an error message.
     */
    private void handleMissingDocumentData(@NonNull DocumentModel document) {
        Log.w(TAG, "No encrypted data found on device for document: " + document.getTitle()
                + " (ID: " + document.getDocId() + "). Automatically removing from vault.");

        // 1. Remove immediately from local memory and refresh UI
        for (int i = masterDocumentList.size() - 1; i >= 0; i--) {
            DocumentModel d = masterDocumentList.get(i);
            if (document.getDocId() != null && document.getDocId().equals(d.getDocId())) {
                masterDocumentList.remove(i);
                break;
            }
        }
        applyFilters();
        updateCategoryCounts();

        // 2. Clean up any lingering files if paths were partially present
        if (document.getEncryptedPath() != null && !document.getEncryptedPath().isEmpty()) {
            File encFile = new File(document.getEncryptedPath());
            if (encFile.exists()) {
                encFile.delete();
            }
        }
        if (document.getPdfPath() != null && !document.getPdfPath().isEmpty()) {
            File pdfFile = new File(document.getPdfPath());
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
        }

        // 3. Delete metadata record from Cloud Firestore
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user != null && document.getDocId() != null) {
            firestore.collection("users")
                    .document(user.getUid())
                    .collection("documents")
                    .document(document.getDocId())
                    .delete()
                    .addOnSuccessListener(unused ->
                            Log.d(TAG, "Orphaned document metadata deleted from Firestore: " + document.getDocId()))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Failed to delete orphaned document metadata from Firestore", e));
        }

        Toast.makeText(this,
                "No encrypted data found on device. Document removed from vault.",
                Toast.LENGTH_SHORT).show();
    }

    private void shareJpgImage(DocumentModel document, File encFile) {
        Toast.makeText(this, "Preparing JPG image...", Toast.LENGTH_SHORT).show();
        VaultExecutor.getInstance().execute(() -> {
            try {
                Bitmap bmp = AesEncryptionHelper.getInstance().decryptFileToBitmap(encFile);
                if (bmp == null) throw new IOException("Decryption failed.");

                File exportDir = new File(getCacheDir(), "shared_jpg");
                exportDir.mkdirs();
                String safeName = sanitizeFilename(document.getTitle()) + ".jpg";
                File jpgFile = new File(exportDir, safeName);

                try (FileOutputStream fos = new FileOutputStream(jpgFile)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    fos.flush();
                }

                runOnUiThread(() -> {
                    try {
                        Uri contentUri = PdfExportHelper.getSecureUriForFile(this, jpgFile);
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("image/jpeg");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, document.getTitle());
                        shareIntent.putExtra(Intent.EXTRA_TEXT, "Secured with GhostDocs: " + document.getTitle());
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Share Image (Quick Share / Apps)"));
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to share JPG", ex);
                        Toast.makeText(VaultActivity.this, "Could not share JPG: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error decrypting JPG for share", e);
                runOnUiThread(() -> Toast.makeText(VaultActivity.this,
                        "Failed to export JPG: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveJpgToGallery(DocumentModel document, File encFile) {
        Toast.makeText(this, "Saving JPG to Gallery...", Toast.LENGTH_SHORT).show();
        VaultExecutor.getInstance().execute(() -> {
            try {
                Bitmap bmp = AesEncryptionHelper.getInstance().decryptFileToBitmap(encFile);
                if (bmp == null) throw new IOException("Decryption failed.");

                String safeName = sanitizeFilename(document.getTitle()) + "_" + System.currentTimeMillis() + ".jpg";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GhostDocs");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
                        }
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                } else {
                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    File ghostDir = new File(picturesDir, "GhostDocs");
                    ghostDir.mkdirs();
                    File outFile = new File(ghostDir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                        fos.flush();
                    }
                }

                runOnUiThread(() -> Toast.makeText(VaultActivity.this,
                        "Saved to Gallery in Pictures/GhostDocs!", Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                Log.e(TAG, "Error saving JPG to Gallery", e);
                runOnUiThread(() -> Toast.makeText(VaultActivity.this,
                        "Failed to save to Gallery: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) return "GhostDocs_Document";
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
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
        VaultWidgetProvider.updateAllWidgets(this, masterDocumentList.size(), isWatcherRunning);
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

        // Clean up PDF on device
        if (document.getPdfPath() != null && !document.getPdfPath().isEmpty()) {
            java.io.File pdfFile = new java.io.File(document.getPdfPath());
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
        }

        for (int i = masterDocumentList.size() - 1; i >= 0; i--) {
            DocumentModel d = masterDocumentList.get(i);
            if (document.getDocId() != null && document.getDocId().equals(d.getDocId())) {
                masterDocumentList.remove(i);
                break;
            }
        }
        applyFilters();
        updateCategoryCounts();

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
