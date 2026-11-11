package com.ghostdocs.vault.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.classifier.RegexClassifierEngine;
import com.ghostdocs.vault.model.DocumentModel;
import com.ghostdocs.vault.observer.MediaContentObserver;
import com.ghostdocs.vault.security.AesEncryptionHelper;
import com.ghostdocs.vault.tasks.EncryptTask;
import com.ghostdocs.vault.tasks.OcrTask;
import com.ghostdocs.vault.tasks.PdfGeneratorTask;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.ImageDecodeHelper;
import com.ghostdocs.vault.util.MediaDeleteHelper;
import com.ghostdocs.vault.util.NotificationHelper;
import com.ghostdocs.vault.util.PdfExportHelper;

import java.io.File;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WatcherService — The Invisible Background Guardian
 *
 * <p>
 * Runs continuously as a Foreground Service to protect sensitive media in
 * real-time.
 * Monitors Android's {@link MediaStore} via {@link MediaContentObserver},
 * dispatches heavy
 * image analysis tasks to {@link VaultExecutor}, executes ML Kit OCR & Regex
 * parsing,
 * and streams live telemetry to Firebase Realtime Database and Cloud Firestore.
 *
 * <p>
 * <b>OOP Principles:</b>
 * <ul>
 * <li><b>Inheritance:</b> Extends {@link Service}.</li>
 * <li><b>Encapsulation:</b> Observer lifecycle, Realtime Database sync handles,
 * and
 * pipeline dispatching are private.</li>
 * <li><b>Polymorphism:</b> Implements
 * {@link MediaContentObserver.OnImageCapturedListener}
 * to receive new media callbacks polymorphically.</li>
 * </ul>
 */
public class WatcherService extends Service implements MediaContentObserver.OnImageCapturedListener {

    private static final String TAG = "GhostDocs.Watcher";

    // ── Status Constants for Realtime Database ───────────────────────────────
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SCANNING = "SCANNING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_STOPPED = "STOPPED";

    // ── Explicit Actions for User Confirmation ───────────────────────────────
    public static final String ACTION_SECURE_CAPTURE = "com.ghostdocs.vault.ACTION_SECURE_CAPTURE";
    public static final String ACTION_IGNORE_CAPTURE = "com.ghostdocs.vault.ACTION_IGNORE_CAPTURE";

    public static final String EXTRA_IMAGE_URI = "extra_image_uri";
    public static final String EXTRA_DISPLAY_NAME = "extra_display_name";
    public static final String EXTRA_MIME_TYPE = "extra_mime_type";
    public static final String EXTRA_DATE_ADDED = "extra_date_added";
    public static final String EXTRA_NOTIFICATION_ID = "extra_notification_id";

    // ── Components ───────────────────────────────────────────────────────────
    private MediaContentObserver mediaObserver;
    private DatabaseReference watcherDbRef;
    private String currentUserId;
    private boolean isObserving = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WatcherService onCreate: starting foreground guardian");

        // 1. Immediately promote to Foreground Service with persistent notification
        Notification notification = NotificationHelper.buildWatcherNotification(
                this,
                getString(R.string.watcher_notification_text));
        startForeground(NotificationHelper.WATCHER_NOTIFICATION_ID, notification);

        // 2. Initialize Realtime Database Sync
        initRealtimeDatabaseSync();

        // 3. Register MediaStore ContentObserver
        registerMediaObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_SECURE_CAPTURE.equals(action)) {
                handleSecureCaptureAction(intent);
                return START_STICKY;
            } else if (ACTION_IGNORE_CAPTURE.equals(action)) {
                handleIgnoreCaptureAction(intent);
                return START_STICKY;
            }
        }

        Log.d(TAG, "WatcherService onStartCommand called.");
        updateWatcherStatus(STATUS_ACTIVE, "Guardian actively monitoring MediaStore");
        return START_STICKY; // System restarts service if killed
    }

    private void handleSecureCaptureAction(Intent intent) {
        int notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (notifId != -1) {
            NotificationManagerCompat.from(this).cancel(notifId);
        }

        String uriStr = intent.getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null)
            return;
        Uri imageUri = Uri.parse(uriStr);
        String displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        String mimeType = intent.getStringExtra(EXTRA_MIME_TYPE);
        long dateAdded = intent.getLongExtra(EXTRA_DATE_ADDED, System.currentTimeMillis());

        updateForegroundNotification("Securing confirmed capture: " + displayName);
        VaultExecutor.getInstance().execute(() -> {
            processIncomingMedia(imageUri, displayName, mimeType, dateAdded);
        });
    }

    private void handleIgnoreCaptureAction(Intent intent) {
        int notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (notifId != -1) {
            NotificationManagerCompat.from(this).cancel(notifId);
        }
        updateForegroundNotification(getString(R.string.watcher_notification_text));
        Log.i(TAG, "Capture ignored by user — original preserved safely in gallery.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "WatcherService onDestroy: stopping watcher");

        // Unregister observer
        unregisterMediaObserver();

        // Mark status as STOPPED in Realtime Database
        updateWatcherStatus(STATUS_STOPPED, "Watcher manually or system terminated");

        // Stop foreground
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bound interactions not needed
    }

    // ════════════════════════════════════════════════════════════════════════
    // MediaStore ContentObserver Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    private void registerMediaObserver() {
        if (isObserving)
            return;

        try {
            Handler handler = new Handler(Looper.getMainLooper());
            mediaObserver = new MediaContentObserver(handler, this, this);

            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mediaObserver);
            getContentResolver().registerContentObserver(
                    MediaStore.Files.getContentUri("external"),
                    true,
                    mediaObserver);

            isObserving = true;
            Log.i(TAG,
                    "MediaContentObserver registered successfully on Images and Files MediaStore");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register MediaContentObserver", e);
        }
    }

    private void unregisterMediaObserver() {
        if (!isObserving || mediaObserver == null)
            return;
        try {
            getContentResolver().unregisterContentObserver(mediaObserver);
            isObserving = false;
            Log.d(TAG, "MediaContentObserver unregistered.");
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering MediaContentObserver", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MediaContentObserver.OnImageCapturedListener (Polymorphism)
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onImageCaptured(@NonNull Uri imageUri, @NonNull String displayName,
            @NonNull String mimeType, long dateAdded) {
        Log.i(TAG, "Captured image event received in WatcherService: " + displayName);

        SharedPreferences prefs = getSharedPreferences("GhostDocsPrefs", MODE_PRIVATE);
        boolean confirmBeforeSecure = prefs.getBoolean("confirm_before_secure", true);

        if (!confirmBeforeSecure) {
            // Unattended automatic mode: process immediately
            updateWatcherStatus(STATUS_SCANNING, "Analyzing capture: " + displayName);
            updateForegroundNotification("Analyzing capture: " + displayName);
            VaultExecutor.getInstance().execute(() -> {
                processIncomingMedia(imageUri, displayName, mimeType, dateAdded);
            });
            return;
        }

        // Ask-First mode (Default): run OCR analysis and ask user before encrypting or
        // deleting
        updateWatcherStatus(STATUS_SCANNING, "Analyzing capture: " + displayName);
        updateForegroundNotification("Analyzing capture: " + displayName);
        VaultExecutor.getInstance().execute(() -> {
            promptUserForCapture(imageUri, displayName, mimeType, dateAdded);
        });
    }

    /**
     * Pre-analyzes captured media and posts an interactive notification asking the
     * user
     * whether to encrypt the document into the vault or keep it in the gallery.
     */
    private void promptUserForCapture(Uri imageUri, String displayName, String mimeType, long dateAdded) {
        try {
            SharedPreferences prefs = getSharedPreferences("GhostDocsPrefs", MODE_PRIVATE);
            boolean excludeStudyNotes = prefs.getBoolean("exclude_study_notes", true);

            boolean isPdf = PdfExportHelper.isPdf(mimeType, displayName);
            OcrTask ocrTask;
            Bitmap processedBitmap = null;

            if (isPdf) {
                // Multi-page PDF OCR
                ocrTask = new OcrTask(this, imageUri, displayName, true, null);
            } else {
                // Step 1: Decode full captured photo without cropping
                processedBitmap = ImageDecodeHelper.loadBitmapFromUri(this, imageUri);

                // Step 2: ML Kit OCR
                ocrTask = new OcrTask(this, processedBitmap, displayName, null);
            }

            OcrTask.OcrResult ocrResult = ocrTask.executeDirectly();

            RegexClassifierEngine.ClassificationResult classification = ocrResult.getClassification();
            String docType = classification.getDocType();
            String smartTitle = classification.getTitle();
            String fullOcrText = ocrResult.getFullText();

            // Recycle bitmap to conserve RAM
            if (processedBitmap != null && !processedBitmap.isRecycled()) {
                processedBitmap.recycle();
            }

            // Exclude study notes if configured
            if (excludeStudyNotes && (DocumentModel.TYPE_STUDY_NOTE.equals(docType)
                    || RegexClassifierEngine.isAcademicOrStudyNote(fullOcrText))) {
                Log.i(TAG, "Class notes detected: " + displayName + ". Preserving in Gallery without prompt.");
                updateForegroundNotification("Class notes detected — preserved in Gallery.");
                return;
            }

            // Prompt user via interactive heads-up notification with action buttons
            int notifId = (int) (System.currentTimeMillis() % 10000) + 3000;
            String snippet = fullOcrText.length() > 100 ? fullOcrText.substring(0, 100) + "..." : fullOcrText;

            NotificationHelper.showSecurityPromptNotification(
                    this,
                    notifId,
                    imageUri,
                    displayName,
                    mimeType,
                    dateAdded,
                    smartTitle,
                    docType,
                    snippet);

            updateForegroundNotification("Capture detected: Waiting for your confirmation.");
            updateWatcherStatus(STATUS_ACTIVE, "Waiting for confirmation: " + smartTitle);

        } catch (Exception e) {
            Log.e(TAG, "Error in promptUserForCapture", e);
            updateForegroundNotification(getString(R.string.watcher_notification_text));
        }
    }

    /**
     * Pipeline processing method executed entirely on a worker thread.
     * Integrates ML Kit OCR, Regex keyword extraction, JSONObject generation,
     * and Firestore persistence.
     */
    private void processIncomingMedia(Uri imageUri, String displayName, String mimeType, long dateAdded) {
        try {
            Log.d(TAG, "Background processing started for: " + displayName);

            boolean isPdf = PdfExportHelper.isPdf(mimeType, displayName);
            Bitmap processedBitmap = null;
            OcrTask.OcrResult ocrResult;

            if (isPdf) {
                updateForegroundNotification("Reading multi-page PDF text...");
                OcrTask ocrTask = new OcrTask(this, imageUri, displayName, true, null);
                ocrResult = ocrTask.executeDirectly();
            } else {
                // Step 1: Decode full captured photo without cropping
                updateForegroundNotification("Analyzing captured document...");
                processedBitmap = ImageDecodeHelper.loadBitmapFromUri(this, imageUri);

                // Step 2: Execute on-device ML Kit OCR Task
                updateForegroundNotification("Reading document text...");
                OcrTask ocrTask = new OcrTask(this, processedBitmap, displayName, null);
                ocrResult = ocrTask.executeDirectly();
            }

            RegexClassifierEngine.ClassificationResult classification = ocrResult.getClassification();
            String docType = classification.getDocType();
            String title = classification.getTitle();
            List<String> tags = new ArrayList<>(classification.getTags());
            if (isPdf && !tags.contains("PDF")) {
                tags.add("PDF");
            }
            String fullOcrText = ocrResult.getFullText();

            Log.i(TAG, "Pipeline OCR complete -> Category: " + docType + ", Title: " + title);

            // Step 2b: Exclude class lecture & notebook study notes from vault security if
            // configured
            android.content.SharedPreferences prefs = getSharedPreferences("GhostDocsPrefs", MODE_PRIVATE);
            boolean excludeStudyNotes = prefs.getBoolean("exclude_study_notes", true);

            if (excludeStudyNotes && (DocumentModel.TYPE_STUDY_NOTE.equals(docType)
                    || RegexClassifierEngine.isAcademicOrStudyNote(fullOcrText))) {
                Log.i(TAG, "Excluding class lecture / study note from vault security: " + displayName);
                updateForegroundNotification("Class notes detected — preserved safely.");
                if (processedBitmap != null && !processedBitmap.isRecycled()) {
                    processedBitmap.recycle();
                }
                return;
            }

            // Step 3: Hardware AES-256-GCM Encryption & Export Generation
            updateForegroundNotification("Encrypting and securing document...");
            String docId = UUID.randomUUID().toString();
            boolean autoDeleteOriginal = prefs.getBoolean("auto_delete_original", true);

            String encryptedPath;
            String pdfPath = "";
            boolean originalDeleted = !autoDeleteOriginal;
            IntentSender recoverableSender = null;

            if (isPdf) {
                byte[] pdfBytes = PdfExportHelper.readUriBytes(this, imageUri);
                File encFile = AesEncryptionHelper.getInstance().encryptBytesToFile(this, pdfBytes, docId);
                encryptedPath = encFile.getAbsolutePath();
                pdfPath = PdfExportHelper.saveExportedPdf(this, pdfBytes, docId, title);

                if (autoDeleteOriginal) {
                    MediaDeleteHelper.DeleteAttemptResult delResult =
                            MediaDeleteHelper.tryDirectDelete(this, imageUri, displayName, 0);
                    originalDeleted = delResult.isDeleted();
                    recoverableSender = delResult.getRecoverableIntentSender();
                }
                Log.i(TAG, "PDF Encryption complete: " + encryptedPath + " | Original deleted: " + originalDeleted);
            } else {
                EncryptTask encryptTask = new EncryptTask(this, processedBitmap, imageUri, docId,
                        autoDeleteOriginal, null);
                EncryptTask.EncryptResult encryptResult = encryptTask.executeDirectly();
                encryptedPath = encryptResult.getEncryptedPath();
                originalDeleted = encryptResult.isOriginalDeleted();
                recoverableSender = encryptResult.getRecoverableIntentSender();

                Log.i(TAG, "Encryption complete: " + encryptedPath
                        + " | Original deleted: " + originalDeleted);

                // Step 4: Generate Searchable PDF with transparent OCR text overlay
                updateForegroundNotification("Generating searchable PDF...");
                try {
                    PdfGeneratorTask pdfTask = new PdfGeneratorTask(
                            this,
                            processedBitmap,
                            ocrResult.getBoundingBoxes(),
                            docId,
                            title,
                            null);
                    File pdfFile = pdfTask.executeDirectly();
                    if (pdfFile != null && pdfFile.exists()) {
                        pdfPath = pdfFile.getAbsolutePath();
                        Log.i(TAG, "Searchable PDF created in pipeline: " + pdfPath);
                    }
                } catch (Exception pdfEx) {
                    Log.w(TAG, "Could not generate PDF during auto-pipeline", pdfEx);
                }

                if (processedBitmap != null && !processedBitmap.isRecycled()) {
                    processedBitmap.recycle();
                }
            }

            // Step 5: Push structured metadata to Cloud Firestore if user is authenticated
            final boolean needDeleteConsent = autoDeleteOriginal && !originalDeleted;
            final IntentSender consentSender = recoverableSender;
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                long timestamp = System.currentTimeMillis();

                DocumentModel docModel = new DocumentModel(
                        docId,
                        title,
                        fullOcrText,
                        encryptedPath, // Verified AES encrypted path on device
                        imageUri.toString(), // Reference for thumbnail
                        pdfPath, // Verified searchable/native PDF path
                        timestamp,
                        tags,
                        docType,
                        currentUser.getUid());

                FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUser.getUid())
                        .collection("documents")
                        .document(docId)
                        .set(docModel)
                        .addOnSuccessListener(unused -> {
                            Log.d(TAG, "Metadata pushed to Firestore: " + docId);
                            if (needDeleteConsent) {
                                NotificationHelper.showDeleteConsentNotification(
                                        WatcherService.this, imageUri, consentSender, title);
                            }
                        })
                        .addOnFailureListener(e -> Log.e(TAG, "Failed pushing metadata to Firestore", e));

                // Step 6: Trigger local notification
                NotificationHelper.showDocumentSecuredNotification(this, title, docType);
            }

            // Step 4: Update Realtime Database status
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("displayName", displayName);
            logEntry.put("docType", docType);
            logEntry.put("title", title);
            logEntry.put("tags", tags);
            logEntry.put("detectedAt", ServerValue.TIMESTAMP);

            if (watcherDbRef != null) {
                watcherDbRef.child("recent_captures").push().setValue(logEntry);
            }

            updateWatcherStatus(STATUS_ACTIVE, "Secured: " + title);
            updateForegroundNotification(getString(R.string.watcher_notification_text));

        } catch (Exception e) {
            Log.e(TAG, "Error in processIncomingMedia pipeline", e);
            updateWatcherStatus(STATUS_ACTIVE, "Idle after processing error");
            updateForegroundNotification(getString(R.string.watcher_notification_text));
        }
    }

    // ── Firebase Realtime Database Telemetry Endpoint ───────────────────────
    public static final String RTDB_URL = "https://ghostdocs-57c78-default-rtdb.asia-southeast1.firebasedatabase.app";

    // ════════════════════════════════════════════════════════════════════════
    // Firebase Realtime Database Sync
    // ════════════════════════════════════════════════════════════════════════

    private void initRealtimeDatabaseSync() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null && currentUser.getUid() != null) {
                currentUserId = currentUser.getUid();
            } else {
                currentUserId = "device_" + android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            }

            FirebaseDatabase database;
            try {
                database = FirebaseDatabase.getInstance(RTDB_URL);
            } catch (Exception e) {
                database = FirebaseDatabase.getInstance();
            }

            watcherDbRef = database.getReference("watcher_status").child(currentUserId);

            // Configure onDisconnect handler to mark offline automatically
            Map<String, Object> offlineMap = new HashMap<>();
            offlineMap.put("status", STATUS_OFFLINE);
            offlineMap.put("details", "Device disconnected or service killed");
            offlineMap.put("lastSeen", ServerValue.TIMESTAMP);
            watcherDbRef.onDisconnect().updateChildren(offlineMap);

            Log.i(TAG, "Firebase Realtime Database watcher sync initialized for user/device: " + currentUserId);
        } catch (Exception e) {
            Log.e(TAG, "Firebase Realtime DB init failed: " + e.getMessage(), e);
        }
    }

    private void updateWatcherStatus(String status, String details) {
        try {
            if (watcherDbRef == null) {
                initRealtimeDatabaseSync();
            }

            if (watcherDbRef != null) {
                Map<String, Object> update = new HashMap<>();
                update.put("status", status);
                update.put("details", details);
                update.put("device",
                        Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
                update.put("updatedAt", ServerValue.TIMESTAMP);

                watcherDbRef.updateChildren(update)
                        .addOnSuccessListener(
                                unused -> Log.d(TAG, "Realtime DB status updated to: " + status + " (" + details + ")"))
                        .addOnFailureListener(
                                e -> Log.e(TAG, "Realtime DB status update FAILED: " + e.getMessage(), e));
            } else {
                Log.w(TAG, "watcherDbRef is null, cannot update status to: " + status);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Realtime Database status", e);
        }
    }

    private void updateForegroundNotification(String statusText) {
        Notification notification = NotificationHelper.buildWatcherNotification(this, statusText);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NotificationHelper.WATCHER_NOTIFICATION_ID, notification);
        }
    }
}
