package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.util.OpenCvCropper;

import java.io.IOException;

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

    private final Uri imageUri;
    private final Bitmap sourceBitmap;

    /**
     * Constructor when processing from a content URI.
     */
    public CropTask(@NonNull Context context,
                    @NonNull Uri imageUri,
                    @Nullable TaskCallback<Bitmap> callback) {
        super(context, callback);
        this.imageUri = imageUri;
        this.sourceBitmap = null;
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
    }

    @Override
    protected Bitmap doInBackground() throws Exception {
        Log.d(TAG, "Starting OpenCV CropTask on background worker thread...");

        Bitmap inputBitmap = sourceBitmap;
        if (inputBitmap == null && imageUri != null) {
            inputBitmap = loadBitmapFromUri(imageUri);
        }

        if (inputBitmap == null) {
            throw new IOException("Failed to load source bitmap for OpenCV crop.");
        }

        // Apply OpenCV Canny + Contour + Perspective Transform
        return OpenCvCropper.cropAndFlatten(inputBitmap);
    }

    /**
     * Safely loads a mutable software Bitmap from a MediaStore content URI.
     */
    private Bitmap loadBitmapFromUri(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
            });
        } else {
            return MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
        }
    }
}

