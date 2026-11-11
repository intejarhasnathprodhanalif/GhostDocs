package com.ghostdocs.vault.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * ImageDecodeHelper — Safe Full-Frame Bitmap Loader
 *
 * <p>Decodes captured photos and screenshots cleanly into memory without altering
 * framing or cropping borders, while capping multi-megapixel camera photos at
 * {@link #MAX_CAPTURE_DIMENSION} to keep OCR, encryption, and PDF generation OOM-safe.
 */
public final class ImageDecodeHelper {

    private static final int MAX_CAPTURE_DIMENSION = 2400;

    private ImageDecodeHelper() {
        throw new UnsupportedOperationException("ImageDecodeHelper is a utility class.");
    }

    /**
     * Loads a mutable software {@link Bitmap} from a MediaStore or Photo Picker content URI,
     * preserving full capture framing while scaling huge camera photos down to at most 2400px.
     */
    @NonNull
    public static Bitmap loadBitmapFromUri(@NonNull Context context, @NonNull Uri uri) throws IOException {
        Bitmap decoded;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            decoded = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
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
                if (is == null) {
                    throw new IOException("Unable to open image stream: " + uri);
                }
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
                if (is == null) {
                    throw new IOException("Unable to open image stream: " + uri);
                }
                decoded = BitmapFactory.decodeStream(is, null, decodeOpts);
            }
        }

        if (decoded == null) {
            throw new IOException("Failed to decode bitmap from URI: " + uri);
        }
        return decoded;
    }
}
