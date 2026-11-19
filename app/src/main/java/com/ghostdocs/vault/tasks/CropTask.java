package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.util.OpenCvCropper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * CropTask — Background Task for OpenCV Document Cropping & De-skewing
 *
 * <p>Executes OpenCV Canny edge detection, contour approximation, and perspective
 * warping on a background worker thread via {@link VaultExecutor}.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link BaseVaultTask}.</li>
 *   <li><b>Polymorphism:</b> Overrides {@link #doInBackground()} with OpenCV computer vision operations.</li>
 *   <li><b>Encapsulation:</b> Hides image decoding, resource resolution, and native OpenCV interaction.</li>
 * </ul>
 */
public class CropTask extends BaseVaultTask<Bitmap> {

    private static final String TAG = "GhostDocs.CropTask";
    private static final int MAX_CAPTURE_DIMENSION = 2400;

    /**
     * Encapsulates both the cropped/flattened bitmap and the uncropped original bitmap
     * when OpenCV cropping was applied.
     */
    public static class CropResult {
        private final Bitmap processedBitmap;
        @Nullable private final Bitmap originalBitmap;
        private final boolean wasCropped;

        public CropResult(@NonNull Bitmap processedBitmap,
                          @Nullable Bitmap originalBitmap,
                          boolean wasCropped) {
            this.processedBitmap = processedBitmap;
            this.originalBitmap = originalBitmap;
            this.wasCropped = wasCropped;
        }

        @NonNull public Bitmap getProcessedBitmap() { return processedBitmap; }
        @Nullable public Bitmap getOriginalBitmap() { return originalBitmap; }
        public boolean wasCropped() { return wasCropped; }
    }

    private final Uri imageUri;
    private final Bitmap sourceBitmap;
    private final String displayName;

    /**
     * Constructor when processing from a content URI.
     */
    public CropTask(@NonNull Context context,
                    @NonNull Uri imageUri,
                    @Nullable TaskCallback<Bitmap> callback) {
        this(context, imageUri, null, callback);
    }

    /**
     * Constructor when processing from a content URI with a known display name.
     * Digital screenshots bypass perspective cropping so UI cards/banners are not cut.
     */
    public CropTask(@NonNull Context context,
                    @NonNull Uri imageUri,
                    @Nullable String displayName,
                    @Nullable TaskCallback<Bitmap> callback) {
        super(context, callback);
        this.imageUri = imageUri;
        this.sourceBitmap = null;
        this.displayName = displayName;
    }

    /**
     * Constructor when processing an in-memory Bitmap.
     */
    public CropTask(@NonNull Context context,
                    @NonNull Bitmap bitmap,
                    @Nullable TaskCallback<Bitmap> callback) {
        super(context, callback);
        this.imageUri = null;
        this.sourceBitmap = bitmap;
        this.displayName = null;
    }

    @Override
    public Bitmap doInBackground() throws Exception {
        return executeWithOriginal().getProcessedBitmap();
    }

    /**
     * Executes smart cropping on the current worker thread and returns both the
     * processed bitmap and the uncropped original bitmap (when cropping occurred).
     */
    @NonNull
    public CropResult executeWithOriginal() throws Exception {
        Log.d(TAG, "Starting OpenCV CropTask on background worker thread...");

        Bitmap inputBitmap = sourceBitmap;
        if (inputBitmap == null && imageUri != null) {
            inputBitmap = loadBitmapFromUri(imageUri);
        }

        if (inputBitmap == null) {
            throw new IOException("Failed to load source bitmap for OpenCV crop.");
        }

        // Digital phone screenshots are already flat 2D captures — do not crop UI banners/cards
        if (isScreenCapture(displayName, imageUri)) {
            Log.d(TAG, "Screen capture detected (" + displayName + "). Preserving full uncropped image.");
            return new CropResult(inputBitmap, null, false);
        }

        // Apply OpenCV Canny + Contour + Perspective Transform for physical document photos
        Bitmap outputBitmap = OpenCvCropper.cropAndFlatten(inputBitmap);
        boolean wasCropped = (outputBitmap != inputBitmap);
        return new CropResult(outputBitmap, wasCropped ? inputBitmap : null, wasCropped);
    }

    private static boolean isScreenCapture(@Nullable String displayName, @Nullable Uri uri) {
        if (displayName != null) {
            String lower = displayName.toLowerCase(Locale.ROOT);
            if (lower.startsWith("screenshot") || lower.startsWith("screen_") || lower.startsWith("screencap")) {
                return true;
            }
        }
        if (uri != null) {
            String path = uri.toString().toLowerCase(Locale.ROOT);
            if (path.contains("screenshots")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Safely loads a mutable software Bitmap from a MediaStore content URI,
     * capping huge 12MP-64MP camera captures to 2400px max dimension to prevent OOM during OpenCV warping.
     */
    private Bitmap loadBitmapFromUri(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);

                Size size = info.getSize();
                int w = size.getWidth();
                int h = size.getHeight();
                int maxSide = Math.max(w, h);
                if (maxSide > MAX_CAPTURE_DIMENSION && w > 0 && h > 0) {
                    float ratio = (float) MAX_CAPTURE_DIMENSION / maxSide;
                    decoder.setTargetSize(
                            Math.max(1, Math.round(w * ratio)),
                            Math.max(1, Math.round(h * ratio))
                    );
                }
            });
        } else {
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, boundsOpts);
            }
            int sampleSize = 1;
            int maxSide = Math.max(boundsOpts.outWidth, boundsOpts.outHeight);
            while (maxSide / sampleSize > MAX_CAPTURE_DIMENSION) {
                sampleSize *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            decodeOpts.inMutable = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, decodeOpts);
            }
        }
    }
}

