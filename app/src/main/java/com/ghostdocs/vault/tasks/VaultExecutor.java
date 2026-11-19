package com.ghostdocs.vault.tasks;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VaultExecutor — Thread Pool Management for Heavy Vault Operations
 *
 * <p>Encapsulates an {@link ExecutorService} with a fixed pool of daemon worker
 * threads. Strictly prevents any blocking operations (OCR, OpenCV Mat operations,
 * AES-256 cryptographic transformations, and PDF rendering) from stalling
 * the Android Main/UI thread.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> The thread pool lifecycle, thread factory, and queue
 *       mechanisms are hidden behind simple execution methods.</li>
 *   <li><b>Singleton Pattern:</b> Provides a centralized shared executor instance for
 *       the entire application lifetime while also allowing localized teardown.</li>
 * </ul>
 */
public final class VaultExecutor {

    private static final String TAG = "GhostDocs.Executor";
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;

    private static volatile VaultExecutor instance;
    private final ExecutorService executorService;

    // ── Custom Named ThreadFactory for Debugging & Profiling ─────────────────
    private static class VaultThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "GhostDocs-Worker-";

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            Thread thread = new Thread(runnable, namePrefix + threadNumber.getAndIncrement());
            thread.setPriority(Thread.NORM_PRIORITY - 1); // Yield priority slightly to UI
            thread.setDaemon(false);
            return thread;
        }
    }

    private VaultExecutor() {
        this.executorService = Executors.newFixedThreadPool(
                DEFAULT_THREAD_POOL_SIZE,
                new VaultThreadFactory()
        );
        Log.d(TAG, "VaultExecutor initialized with " + DEFAULT_THREAD_POOL_SIZE + " worker threads.");
    }

    /**
     * Retrieves the global singleton instance of VaultExecutor.
     * Uses double-checked locking for thread-safety.
     */
    public static VaultExecutor getInstance() {
        if (instance == null) {
            synchronized (VaultExecutor.class) {
                if (instance == null) {
                    instance = new VaultExecutor();
                }
            }
        }
        return instance;
    }

    /**
     * Submits a {@link BaseVaultTask} or general {@link Runnable} to the background queue.
     *
     * @param task Runnable or BaseVaultTask to execute.
     * @return Future representing pending completion.
     */
    public Future<?> execute(@NonNull Runnable task) {
        return executorService.submit(task);
    }

    /**
     * Safely shuts down the background executor service.
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down VaultExecutor...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

