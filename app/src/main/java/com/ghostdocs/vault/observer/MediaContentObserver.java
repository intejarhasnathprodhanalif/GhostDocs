package com.ghostdocs.vault.observer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MediaContentObserver — The Invisible Watcher's Media Sensor
 *
 * <p>Listens for additions and updates on {@link MediaStore.Images.Media#EXTERNAL_CONTENT_URI}.
 * When a user takes a screenshot or saves a new photo, this observer is triggered by the
 * Android framework.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link ContentObserver}.</li>
 *   <li><b>Encapsulation:</b> Internal ContentResolver queries, debouncing timers, projection
 *       arrays, and duplicate-filtering are hidden.</li>
 *   <li><b>Abstraction:</b> Reports newly captured image metadata via the
 *       {@link OnImageCapturedListener} interface.</li>
 * </ul>
 */
public class MediaContentObserver extends ContentObserver {

    private static final String TAG = "GhostDocs.Watcher";
    private static final long DEBOUNCE_DELAY_MS = 600; // Filter multiple rapid MediaStore events

    /**
     * Interface contract for newly detected media events.
     */
    public interface OnImageCapturedListener {
        /**
         * Called when a new image has been verified and extracted from MediaStore.
         *
         * @param imageUri    Direct content URI to the image (e.g. content://media/external/images/media/1234).
         * @param displayName Image filename (e.g. Screenshot_20261106_120000.png).
         * @param mimeType    MIME type (e.g. image/png, image/jpeg).
         * @param dateAdded   Timestamp (epoch seconds) added to MediaStore.
         */
        void onImageCaptured(@NonNull Uri imageUri, @NonNull String displayName,
                             @NonNull String mimeType, long dateAdded);
    }

    private final Context context;
    private final OnImageCapturedListener listener;
    private final Handler debounceHandler;
    private final AtomicLong lastProcessedImageId = new AtomicLong(-1);
    private Runnable pendingQueryRunnable;

    /**
     * Constructs the observer.
     *
     * @param handler  Handler to receive onChange callbacks.
     * @param context  Context used for ContentResolver queries.
     * @param listener Callback interface for image events.
     */
    public MediaContentObserver(Handler handler, @NonNull Context context,
                                @NonNull OnImageCapturedListener listener) {
        super(handler);
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.debounceHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onChange(boolean selfChange, @Nullable Uri uri) {
        super.onChange(selfChange, uri);
        Log.d(TAG, "MediaStore onChange triggered: " + uri);

        // Cancel previous pending check if a rapid succession of events occurred
        if (pendingQueryRunnable != null) {
            debounceHandler.removeCallbacks(pendingQueryRunnable);
        }

        pendingQueryRunnable = this::queryLatestImage;
        debounceHandler.postDelayed(pendingQueryRunnable, DEBOUNCE_DELAY_MS);
    }

    /**
     * Queries MediaStore for the newest image inserted into external storage.
     */
    private void queryLatestImage() {
        final ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return;

        final String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED
        };

        final String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder)) {

            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);

                long imageId = cursor.getLong(idColumn);
                String displayName = cursor.getString(nameColumn);
                String mimeType = cursor.getString(mimeColumn);
                long dateAdded = cursor.getLong(dateColumn);

                // Prevent processing the exact same image twice
                if (imageId == lastProcessedImageId.get()) {
                    Log.d(TAG, "Duplicate image ID ignored: " + imageId);
                    return;
                }

                lastProcessedImageId.set(imageId);

                Uri contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                );

                Log.i(TAG, "New image detected: " + displayName + " (ID: " + imageId + ", URI: " + contentUri + ")");
                listener.onImageCaptured(
                        contentUri,
                        displayName != null ? displayName : "Unknown_Image",
                        mimeType != null ? mimeType : "image/jpeg",
                        dateAdded
                );
            }
        } catch (SecurityException se) {
            Log.e(TAG, "Permission missing while reading MediaStore in ContentObserver", se);
        } catch (Exception e) {
            Log.e(TAG, "Failed to query MediaStore for latest image", e);
        }
    }
}

