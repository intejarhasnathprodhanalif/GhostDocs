package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.service.WatcherService;
import com.ghostdocs.vault.tasks.VaultExecutor;
import com.ghostdocs.vault.util.NetworkHelper;
import com.ghostdocs.vault.util.PdfExportHelper;

import java.io.InputStream;

/**
 * SecurePromptActivity — Interactive Confirmation Screen for Captured Media
 *
 * <p>Presented when a screenshot or camera photo is captured. Rather than securing
 * silently, GhostDocs allows the user to inspect the preview and explicitly choose
 * whether to encrypt the document into the vault or keep it in the public gallery.
 */
public class SecurePromptActivity extends AppCompatActivity {

    private static final String TAG = "GhostDocs.Prompt";

    private ImageView ivPreview;
    private TextView  tvTitle;
    private TextView  tvType;
    private TextView  tvSnippet;
    private TextView  btnSecure;
    private TextView  btnIgnore;

    private Uri    imageUri;
    private String displayName;
    private String mimeType;
    private long   dateAdded;
    private int    notificationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_secure_prompt);

        extractIntentExtras();
        bindViews();
        populateData();
        setupClickListeners();
    }

    private void extractIntentExtras() {
        Intent intent = getIntent();
        if (intent == null) return;

        String uriStr = intent.getStringExtra(WatcherService.EXTRA_IMAGE_URI);
        if (uriStr != null) {
            imageUri = Uri.parse(uriStr);
        }
        displayName    = intent.getStringExtra(WatcherService.EXTRA_DISPLAY_NAME);
        mimeType       = intent.getStringExtra(WatcherService.EXTRA_MIME_TYPE);
        dateAdded      = intent.getLongExtra(WatcherService.EXTRA_DATE_ADDED, System.currentTimeMillis());
        notificationId = intent.getIntExtra(WatcherService.EXTRA_NOTIFICATION_ID, -1);
    }

    private void bindViews() {
        ivPreview = findViewById(R.id.iv_prompt_preview);
        tvTitle   = findViewById(R.id.tv_prompt_doc_title);
        tvType    = findViewById(R.id.tv_prompt_doc_type);
        tvSnippet = findViewById(R.id.tv_prompt_snippet);
        btnSecure = findViewById(R.id.btn_prompt_secure);
        btnIgnore = findViewById(R.id.btn_prompt_ignore);
    }

    private void populateData() {
        Intent intent = getIntent();
        String smartTitle = intent != null ? intent.getStringExtra("smart_title") : null;
        String docType    = intent != null ? intent.getStringExtra("doc_type") : null;
        String snippet    = intent != null ? intent.getStringExtra("snippet") : null;

        tvTitle.setText(smartTitle != null && !smartTitle.isEmpty()
                ? smartTitle
                : (displayName != null ? displayName : "Secured Capture"));

        tvType.setText(docType != null && !docType.isEmpty() ? docType : "DOCUMENT");

        if (snippet != null && !snippet.trim().isEmpty()) {
            tvSnippet.setText(snippet);
        } else {
            tvSnippet.setText("No OCR text snippet available.");
        }

        // Asynchronously load preview bitmap from content Uri (image or PDF page 1)
        if (imageUri != null) {
            VaultExecutor.getInstance().execute(() -> {
                Bitmap bmp = null;
                if (PdfExportHelper.isPdf(mimeType, displayName)) {
                    bmp = PdfExportHelper.renderFirstPage(this, imageUri, 800);
                } else {
                    try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2; // Downsample for fast display
                        bmp = BitmapFactory.decodeStream(is, null, opts);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to load image preview: " + imageUri, e);
                    }
                }
                final Bitmap finalBmp = bmp;
                runOnUiThread(() -> {
                    if (finalBmp != null) {
                        ivPreview.setImageBitmap(finalBmp);
                    }
                });
            });
        }
    }

    private void setupClickListeners() {
        btnSecure.setOnClickListener(v -> confirmSecure());
        btnIgnore.setOnClickListener(v -> confirmIgnore());
    }

    /**
     * Dispatches secure command to WatcherService to perform encryption and delete original.
     */
    private void confirmSecure() {
        if (!NetworkHelper.isOnline(this)) {
            NetworkHelper.showNetworkInterruptionDialog(
                    this,
                    NetworkHelper.getInterruptionScenario(this),
                    "secure captured media into the vault",
                    null
            );
            return;
        }

        if (notificationId != -1) {
            NotificationManagerCompat.from(this).cancel(notificationId);
        }

        Intent serviceIntent = new Intent(this, WatcherService.class);
        serviceIntent.setAction(WatcherService.ACTION_SECURE_CAPTURE);
        if (imageUri != null) {
            serviceIntent.putExtra(WatcherService.EXTRA_IMAGE_URI, imageUri.toString());
        }
        serviceIntent.putExtra(WatcherService.EXTRA_DISPLAY_NAME, displayName);
        serviceIntent.putExtra(WatcherService.EXTRA_MIME_TYPE, mimeType);
        serviceIntent.putExtra(WatcherService.EXTRA_DATE_ADDED, dateAdded);
        serviceIntent.putExtra(WatcherService.EXTRA_NOTIFICATION_ID, notificationId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "Securing document into Vault...", Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Dismisses the prompt and leaves the file untouched in the public gallery.
     */
    private void confirmIgnore() {
        if (notificationId != -1) {
            NotificationManagerCompat.from(this).cancel(notificationId);
        }
        Toast.makeText(this, "Capture preserved in Gallery.", Toast.LENGTH_SHORT).show();
        finish();
    }
}

