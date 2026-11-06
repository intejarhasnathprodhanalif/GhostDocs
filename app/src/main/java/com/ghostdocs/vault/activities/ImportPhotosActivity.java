package com.ghostdocs.vault.activities;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.adapter.GalleryPhotoAdapter;
import com.ghostdocs.vault.model.DocumentModel;
import com.ghostdocs.vault.model.GalleryPhotoItem;
import com.ghostdocs.vault.tasks.CropTask;
import com.ghostdocs.vault.tasks.EncryptTask;
import com.ghostdocs.vault.tasks.OcrTask;
import com.ghostdocs.vault.tasks.PdfGeneratorTask;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.NotificationHelper;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ImportPhotosActivity — Scans, imports, and vaults pre-existing device screenshots & photos.
 *
 * <p>Enables users to secure photos taken before installing GhostDocs:
 * <ul>
 *   <li><b>Deep MediaStore Audit:</b> Automatically queries device screenshots.</li>
 *   <li><b>System Photo Picker:</b> Browse full device gallery / albums to import any image.</li>
 *   <li><b>Batch Vaulting Pipeline:</b> Full OpenCV crop, ML Kit OCR, AES-256 KeyStore encryption,
 *       searchable PDF creation, and Firestore sync on worker threads.</li>
 *   <li><b>Scoped Storage Compliant Erasure:</b> Android 10-14+ compliant original cleanup via
 *       {@link MediaStore#createDeleteRequest}.</li>
 * </ul>
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link AppCompatActivity}.</li>
 *   <li><b>Encapsulation:</b> MediaStore cursor querying, pipeline execution, and state management
 *       are strictly private.</li>
 *   <li><b>Polymorphism:</b> Implements {@link GalleryPhotoAdapter.OnSelectionChangedListener}.</li>
 * </ul>
 */
public class ImportPhotosActivity extends AppCompatActivity
        implements GalleryPhotoAdapter.OnSelectionChangedListener {

    private static final String TAG = "GhostDocs.ImportPhotos";
    private static final int GRID_SPAN = 3;

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView         rvGalleryGrid;
    private View                 layoutEmptyState;
    private View                 layoutProgressOverlay;
    private CheckBox             cbSelectAll;
    private CheckBox             cbDeleteOriginals;
    private TextView             tvSelectionCount;
    private MaterialButton       btnSecureSelected;
    private TextView             tvProgressHeadline;
    private TextView             tvProgressStep;
    private TextView             tvProgressFilename;
    private ProgressBar          pbDeterminateBar;

    // ── Adapter & Data ───────────────────────────────────────────────────────
    private GalleryPhotoAdapter        galleryAdapter;
    private final List<GalleryPhotoItem> displayedPhotoList = new ArrayList<>();
    private final Set<String>          vaultedUriStrings    = new HashSet<>();
    private boolean                    isSecuringInProgress = false;

    // ── Android 11+ Batch Deletion Launcher ──────────────────────────────────
    private final ActivityResultLauncher<IntentSenderRequest> deleteIntentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "Original photos deleted from gallery.", Toast.LENGTH_SHORT).show();
                        }
                        scanGalleryScreenshots();
                    }
            );

    // ── System Gallery Multi-Picker Launcher ─────────────────────────────────
    private final ActivityResultLauncher<String> galleryPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetMultipleContents(),
                    this::handleManualGallerySelection
            );

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_photos);

        bindViews();
        setupRecyclerView();
        setClickListeners();

        // Load existing vault state to identify already-secured photos, then scan MediaStore
        fetchVaultedDocumentsAndScan();
    }

    @Override
    public void onBackPressed() {
        if (isSecuringInProgress) {
            Toast.makeText(this, "Please wait while documents are being secured...", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    // ════════════════════════════════════════════════════════════════════════
    // View Setup & Listeners
    // ════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        rvGalleryGrid         = findViewById(R.id.rv_gallery_grid);
        layoutEmptyState      = findViewById(R.id.layout_empty_state);
        layoutProgressOverlay = findViewById(R.id.layout_progress_overlay);
        cbSelectAll           = findViewById(R.id.cb_select_all);
        cbDeleteOriginals     = findViewById(R.id.cb_delete_originals);
        tvSelectionCount      = findViewById(R.id.tv_selection_count);
        btnSecureSelected     = findViewById(R.id.btn_secure_selected);
        tvProgressHeadline    = findViewById(R.id.tv_progress_headline);
        tvProgressStep        = findViewById(R.id.tv_progress_step);
        tvProgressFilename    = findViewById(R.id.tv_progress_filename);
        pbDeterminateBar      = findViewById(R.id.pb_determinate_bar);

        // Pre-fill delete preference
        SharedPreferences prefs = getSharedPreferences("GhostDocsPrefs", MODE_PRIVATE);
        boolean autoDelete = prefs.getBoolean("auto_delete_original", true);
        cbDeleteOriginals.setChecked(autoDelete);
    }

    private void setupRecyclerView() {
        galleryAdapter = new GalleryPhotoAdapter(this, displayedPhotoList, this);
        rvGalleryGrid.setLayoutManager(new GridLayoutManager(this, GRID_SPAN));
        rvGalleryGrid.setAdapter(galleryAdapter);
        rvGalleryGrid.setHasFixedSize(true);
    }

    private void setClickListeners() {
        // Back button
        findViewById(R.id.btn_back_import).setOnClickListener(v -> onBackPressed());

        // Browse Gallery button
        findViewById(R.id.btn_browse_gallery).setOnClickListener(v -> {
            galleryPickerLauncher.launch("image/*");
        });

        // Rescan button
        findViewById(R.id.btn_rescan_device).setOnClickListener(v -> scanGalleryScreenshots());

        // Select All toggle
        cbSelectAll.setOnClickListener(v -> {
            if (cbSelectAll.isChecked()) {
                galleryAdapter.selectAll();
            } else {
                galleryAdapter.deselectAll();
            }
        });

        // Secure Selected Photos button
        btnSecureSelected.setOnClickListener(v -> startSecuringSelectedPhotos());
    }

    // ════════════════════════════════════════════════════════════════════════
    // GalleryPhotoAdapter.OnSelectionChangedListener Implementation
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onSelectionChanged(int selectedCount, int totalCount) {
        tvSelectionCount.setText(selectedCount + " of " + totalCount + " selected");
        btnSecureSelected.setEnabled(selectedCount > 0);
        btnSecureSelected.setText("🔒 Secure Selected Photos (" + selectedCount + ")");

        if (totalCount > 0 && selectedCount == totalCount) {
            cbSelectAll.setChecked(true);
        } else if (selectedCount == 0) {
            cbSelectAll.setChecked(false);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MediaStore Audit & Vault Detection
    // ════════════════════════════════════════════════════════════════════════

    private void fetchVaultedDocumentsAndScan() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            scanGalleryScreenshots();
            return;
        }

        // Retrieve existing document original URIs to mark already secured photos
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("documents")
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (DocumentSnapshot doc : task.getResult()) {
                            String origUri = doc.getString("originalUri");
                            if (origUri != null) {
                                vaultedUriStrings.add(origUri);
                            }
                        }
                    }
                    scanGalleryScreenshots();
                });
    }

    /**
     * Queries MediaStore on a background worker thread for existing device screenshots.
     */
    private void scanGalleryScreenshots() {
        VaultExecutor.getInstance().execute(() -> {
            List<GalleryPhotoItem> scannedItems = new ArrayList<>();
            ContentResolver resolver = getContentResolver();

            Uri collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE
            };

            // Look for screenshots across filenames and folders
            StringBuilder selection = new StringBuilder();
            selection.append("(")
                    .append(MediaStore.Images.Media.DISPLAY_NAME).append(" LIKE '%screenshot%' OR ")
                    .append(MediaStore.Images.Media.DISPLAY_NAME).append(" LIKE '%Screenshot%' OR ")
                    .append(MediaStore.Images.Media.DISPLAY_NAME).append(" LIKE '%screen%'");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection.append(" OR ").append(MediaStore.Images.Media.RELATIVE_PATH).append(" LIKE '%Screenshots%'");
            }
            selection.append(") AND ").append(MediaStore.Images.Media.MIME_TYPE).append(" LIKE 'image/%'");

            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

            try (Cursor cursor = resolver.query(collectionUri, projection, selection.toString(), null, sortOrder)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                    int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
                    int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String displayName = cursor.getString(nameCol);
                        String mimeType = cursor.getString(mimeCol);
                        long dateAdded = cursor.getLong(dateCol);
                        long size = cursor.getLong(sizeCol);

                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        boolean isAlreadyVaulted = vaultedUriStrings.contains(contentUri.toString());

                        GalleryPhotoItem item = new GalleryPhotoItem(
                                contentUri,
                                displayName != null ? displayName : "Screenshot_" + id + ".jpg",
                                mimeType,
                                dateAdded,
                                size,
                                isAlreadyVaulted
                        );
                        scannedItems.add(item);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying MediaStore screenshots", e);
            }

            runOnUiThread(() -> {
                displayedPhotoList.clear();
                displayedPhotoList.addAll(scannedItems);
                galleryAdapter.updateData(displayedPhotoList);

                boolean isEmpty = displayedPhotoList.isEmpty();
                layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                rvGalleryGrid.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            });
        });
    }

    /**
     * Handles images chosen through the system gallery picker.
     */
    private void handleManualGallerySelection(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }

        VaultExecutor.getInstance().execute(() -> {
            List<GalleryPhotoItem> newItems = new ArrayList<>();
            ContentResolver resolver = getContentResolver();

            for (Uri uri : uris) {
                String displayName = "Imported_" + System.currentTimeMillis() + ".jpg";
                String mimeType = resolver.getType(uri);
                long size = 0;

                try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex);
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex);
                    }
                } catch (Exception ignored) {}

                boolean isAlreadyVaulted = vaultedUriStrings.contains(uri.toString());
                GalleryPhotoItem item = new GalleryPhotoItem(
                        uri,
                        displayName,
                        mimeType,
                        System.currentTimeMillis() / 1000L,
                        size,
                        isAlreadyVaulted
                );
                // Pre-select images manually chosen by user
                if (!isAlreadyVaulted) {
                    item.setSelected(true);
                }
                newItems.add(item);
            }

            runOnUiThread(() -> {
                // Prepend picked photos to the list
                for (int i = newItems.size() - 1; i >= 0; i--) {
                    GalleryPhotoItem picked = newItems.get(i);
                    displayedPhotoList.remove(picked);
                    displayedPhotoList.add(0, picked);
                }
                galleryAdapter.updateData(displayedPhotoList);
                layoutEmptyState.setVisibility(View.GONE);
                rvGalleryGrid.setVisibility(View.VISIBLE);
                rvGalleryGrid.scrollToPosition(0);
                Toast.makeText(this, newItems.size() + " photos added from gallery. Ready to secure!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Pipeline Securing Execution (Worker Thread)
    // ════════════════════════════════════════════════════════════════════════

    private void startSecuringSelectedPhotos() {
        List<GalleryPhotoItem> selectedItems = galleryAdapter.getSelectedItems();
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "No photos selected to secure.", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "Please sign in to secure documents.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean deleteOriginals = cbDeleteOriginals.isChecked();
        isSecuringInProgress = true;
        layoutProgressOverlay.setVisibility(View.VISIBLE);
        pbDeterminateBar.setProgress(0);
        pbDeterminateBar.setMax(selectedItems.size());

        VaultExecutor.getInstance().execute(() -> {
            int total = selectedItems.size();
            int successCount = 0;
            List<Uri> pendingDeleteUris = new ArrayList<>();

            for (int i = 0; i < total; i++) {
                GalleryPhotoItem item = selectedItems.get(i);
                int currentIndex = i + 1;

                runOnUiThread(() -> {
                    tvProgressStep.setText("Securing " + currentIndex + " of " + total + "...");
                    tvProgressFilename.setText(item.getDisplayName());
                    pbDeterminateBar.setProgress(currentIndex);
                });

                try {
                    // Step 1: Smart Crop & Flatten
                    CropTask cropTask = new CropTask(this, item.getUri(), null);
                    Bitmap processedBitmap = cropTask.executeDirectly();

                    // Step 2: ML Kit OCR & Classifier Engine
                    OcrTask ocrTask = new OcrTask(this, processedBitmap, item.getDisplayName(), null);
                    OcrTask.OcrResult ocrResult = ocrTask.executeDirectly();

                    String docType = ocrResult.getClassification().getDocType();
                    String title = ocrResult.getClassification().getTitle();
                    List<String> tags = ocrResult.getClassification().getTags();
                    String fullOcrText = ocrResult.getFullText();

                    // Step 3: Hardware AES-256-GCM Encryption
                    String docId = UUID.randomUUID().toString();
                    EncryptTask encryptTask = new EncryptTask(this, processedBitmap, item.getUri(), docId, deleteOriginals, null);
                    EncryptTask.EncryptResult encryptResult = encryptTask.executeDirectly();

                    // If deletion was requested but blocked by Android 11+ Scoped Storage, queue URI for batch prompt
                    if (deleteOriginals && !encryptResult.isOriginalDeleted()) {
                        pendingDeleteUris.add(item.getUri());
                    }

                    // Step 4: Searchable PDF Generation
                    String pdfPath = "";
                    try {
                        PdfGeneratorTask pdfTask = new PdfGeneratorTask(
                                this,
                                processedBitmap,
                                ocrResult.getBoundingBoxes(),
                                docId,
                                title,
                                null
                        );
                        File pdfFile = pdfTask.executeDirectly();
                        if (pdfFile != null && pdfFile.exists()) {
                            pdfPath = pdfFile.getAbsolutePath();
                        }
                    } catch (Exception pdfEx) {
                        Log.w(TAG, "Searchable PDF skipped for " + item.getDisplayName(), pdfEx);
                    }

                    // Step 5: Push metadata to Cloud Firestore
                    long timestamp = System.currentTimeMillis();
                    DocumentModel docModel = new DocumentModel(
                            docId,
                            title,
                            fullOcrText,
                            encryptResult.getEncryptedPath(),
                            item.getUri().toString(),
                            pdfPath,
                            timestamp,
                            tags,
                            docType,
                            currentUser.getUid()
                    );

                    Tasks.await(FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(currentUser.getUid())
                            .collection("documents")
                            .document(docId)
                            .set(docModel));

                    vaultedUriStrings.add(item.getUri().toString());
                    successCount++;

                    // Recycle bitmap
                    if (processedBitmap != null && !processedBitmap.isRecycled()) {
                        processedBitmap.recycle();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Failed securing old photo: " + item.getDisplayName(), e);
                }
            }

            final int finalSuccess = successCount;
            runOnUiThread(() -> {
                isSecuringInProgress = false;
                layoutProgressOverlay.setVisibility(View.GONE);

                Toast.makeText(ImportPhotosActivity.this,
                        "🎉 " + finalSuccess + " photos successfully secured into vault!",
                        Toast.LENGTH_LONG).show();

                if (finalSuccess > 0) {
                    NotificationHelper.showDocumentSecuredNotification(
                            ImportPhotosActivity.this,
                            finalSuccess + " Old Gallery Photos",
                            "BATCH VAULT"
                    );
                }

                // If Scoped Storage requires user consent to delete originals on Android 11+
                if (deleteOriginals && !pendingDeleteUris.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), pendingDeleteUris);
                        IntentSenderRequest request = new IntentSenderRequest.Builder(pi.getIntentSender()).build();
                        deleteIntentLauncher.launch(request);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch Scoped Storage batch delete intent", e);
                    }
                }

                // Refresh scan
                scanGalleryScreenshots();
            });
        });
    }
}
