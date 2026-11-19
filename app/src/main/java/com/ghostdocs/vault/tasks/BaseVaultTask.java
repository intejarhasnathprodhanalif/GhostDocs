package com.ghostdocs.vault.tasks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * BaseVaultTask — Abstract Base Class for Heavy Background Work
 *
 * <p>Enforces strict Object-Oriented Programming (OOP) principles across the
 * entire document processing pipeline:
 * <ul>
 *   <li><b>Inheritance:</b> Serves as the abstract base class for {@code OcrTask},
 *       {@code CropTask}, {@code EncryptTask}, and {@code PdfTask}.</li>
 *   <li><b>Polymorphism:</b> Subclasses override {@link #doInBackground()} with their
 *       own specialized algorithms (ML Kit OCR, OpenCV Mat manipulation, AES crypto,
 *       or PdfDocument generation). The execution flow treats all tasks polymorphically.</li>
 *   <li><b>Abstraction:</b> The {@link TaskCallback} generic interface decouples background
 *       execution from UI thread results. Callers depend purely on this contract.</li>
 *   <li><b>Encapsulation:</b> Internal thread dispatch, state management, and error handling
 *       are completely contained within this class.</li>
 * </ul>
 *
 * @param <T> The output type produced by the background computation.
 */
public abstract class BaseVaultTask<T> implements Runnable {

    private static final String TAG = "GhostDocs.Task";

    // ── Abstraction: Generic Callback Interface ──────────────────────────────
    public interface TaskCallback<R> {
        /**
         * Invoked on the UI/Main Thread when the task completes successfully.
         *
         * @param result Result produced by {@link #doInBackground()}.
         */
        void onTaskSuccess(R result);

        /**
         * Invoked on the UI/Main Thread if an unhandled exception occurs.
         *
         * @param error The thrown exception.
         */
        void onTaskError(@NonNull Throwable error);
    }

    // ── Encapsulation: Private & Protected Fields ────────────────────────────
    protected final Context context;
    @Nullable
    protected final TaskCallback<T> callback;
    private final Handler mainHandler;

    /**
     * Protected constructor for subclasses.
     *
     * @param context  Application or service context.
     * @param callback Optional result callback (executed on the Main Thread).
     */
    protected BaseVaultTask(@NonNull Context context, @Nullable TaskCallback<T> callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ── Template Method Pattern ──────────────────────────────────────────────
    @Override
    public final void run() {
        try {
            Log.d(TAG, "Starting task: " + getClass().getSimpleName());
            final T result = doInBackground();
            dispatchSuccess(result);
        } catch (Throwable throwable) {
            Log.e(TAG, "Task failed: " + getClass().getSimpleName(), throwable);
            dispatchError(throwable);
        }
    }

    /**
     * Executes the task directly and returns the result.
     * Safe to invoke when already executing on a background worker thread.
     *
     * @return Output of type {@code T}.
     * @throws Exception If computation fails.
     */
    public final T executeDirectly() throws Exception {
        return doInBackground();
    }

    /**
     * Heavy background computation. Must be implemented by concrete subclasses.
     * Executes on a worker thread provided by {@link VaultExecutor}.
     *
     * @return Output of type {@code T}.
     * @throws Exception If any step in processing fails.
     */
    public abstract T doInBackground() throws Exception;

    // ── Safe Main-Thread Callback Dispatch ───────────────────────────────────
    private void dispatchSuccess(final T result) {
        if (callback == null) return;
        mainHandler.post(() -> {
            try {
                callback.onTaskSuccess(result);
            } catch (Exception e) {
                Log.e(TAG, "Error in onTaskSuccess callback", e);
            }
        });
    }

    private void dispatchError(final Throwable throwable) {
        if (callback == null) return;
        mainHandler.post(() -> {
            try {
                callback.onTaskError(throwable);
            } catch (Exception e) {
                Log.e(TAG, "Error in onTaskError callback", e);
            }
        });
    }
}

