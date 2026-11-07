package com.ghostdocs.vault.adapter;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.model.GalleryPhotoItem;
import com.ghostdocs.vault.tasks.VaultExecutor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GalleryPhotoAdapter — Recycler adapter for pre-existing device photo & screenshot grid.
 *
 * <p>Supports multi-selection, real-time count updating, and asynchronous memory-efficient
 * thumbnail generation using {@link ContentResolver#loadThumbnail} and {@link VaultExecutor}.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link RecyclerView.Adapter}.</li>
 *   <li><b>Encapsulation:</b> Thumbnail decoding, view binding, and cache access are encapsulated.</li>
 *   <li><b>Polymorphism:</b> Notifies external caller via {@link OnSelectionChangedListener}.</li>
 * </ul>
 */
public class GalleryPhotoAdapter extends RecyclerView.Adapter<GalleryPhotoAdapter.PhotoViewHolder> {

    private static final String TAG = "GhostDocs.GalleryAdapter";
    private static final int THUMB_SIZE = 220; // 220x220 dp downsampled thumbnail

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final Context context;
    private final List<GalleryPhotoItem> photoList;
    private final OnSelectionChangedListener selectionListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Uri, Bitmap> thumbnailCache = new ConcurrentHashMap<>();

    public GalleryPhotoAdapter(@NonNull Context context,
                               @NonNull List<GalleryPhotoItem> photoList,
                               @NonNull OnSelectionChangedListener selectionListener) {
        this.context = context;
        this.photoList = new ArrayList<>(photoList);
        this.selectionListener = selectionListener;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_gallery_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        GalleryPhotoItem item = photoList.get(position);

        holder.tvName.setText(item.getDisplayName());

        // Format date and size
        long timestampMs = item.getDateAdded() > 100000000000L ? item.getDateAdded() : item.getDateAdded() * 1000L;
        String dateStr = DateFormat.format("MMM d, yyyy", timestampMs).toString();
        String sizeStr = Formatter.formatFileSize(context, item.getSize());
        holder.tvDate.setText(dateStr + " • " + sizeStr);

        // Vaulted & Selection state
        if (item.isAlreadyVaulted()) {
            holder.tvAlreadyVaulted.setVisibility(View.VISIBLE);
            holder.dimOverlay.setVisibility(View.VISIBLE);
            holder.cbSelected.setVisibility(View.GONE);
            holder.itemView.setEnabled(false);
        } else {
            holder.tvAlreadyVaulted.setVisibility(View.GONE);
            holder.dimOverlay.setVisibility(View.GONE);
            holder.cbSelected.setVisibility(View.VISIBLE);
            holder.cbSelected.setChecked(item.isSelected());
            holder.itemView.setEnabled(true);

            holder.itemView.setOnClickListener(v -> {
                item.setSelected(!item.isSelected());
                holder.cbSelected.setChecked(item.isSelected());
                notifySelectionChange();
            });
        }

        // Load asynchronous thumbnail
        loadThumbnailAsync(item.getUri(), holder.ivThumbnail);
    }

    @Override
    public int getItemCount() {
        return photoList.size();
    }

    public void updateData(@NonNull List<GalleryPhotoItem> newItems) {
        photoList.clear();
        photoList.addAll(newItems);
        notifyDataSetChanged();
        notifySelectionChange();
    }

    public void selectAll() {
        for (GalleryPhotoItem item : photoList) {
            if (!item.isAlreadyVaulted()) {
                item.setSelected(true);
            }
        }
        notifyDataSetChanged();
        notifySelectionChange();
    }

    public void deselectAll() {
        for (GalleryPhotoItem item : photoList) {
            item.setSelected(false);
        }
        notifyDataSetChanged();
        notifySelectionChange();
    }

    @NonNull
    public List<GalleryPhotoItem> getSelectedItems() {
        List<GalleryPhotoItem> selected = new ArrayList<>();
        for (GalleryPhotoItem item : photoList) {
            if (item.isSelected() && !item.isAlreadyVaulted()) {
                selected.add(item);
            }
        }
        return selected;
    }

    public int getSelectedCount() {
        int count = 0;
        for (GalleryPhotoItem item : photoList) {
            if (item.isSelected() && !item.isAlreadyVaulted()) {
                count++;
            }
        }
        return count;
    }

    private void notifySelectionChange() {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(getSelectedCount(), photoList.size());
        }
    }

    /**
     * Efficiently loads and caches downscaled thumbnails without blocking UI.
     */
    private void loadThumbnailAsync(Uri uri, ImageView imageView) {
        imageView.setTag(uri);
        Bitmap cached = thumbnailCache.get(uri);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // Placeholder while loading
        imageView.setImageResource(android.R.drawable.ic_menu_gallery);

        VaultExecutor.getInstance().execute(() -> {
            try {
                Bitmap thumb = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thumb = context.getContentResolver().loadThumbnail(
                            uri,
                            new Size(THUMB_SIZE, THUMB_SIZE),
                            null
                    );
                } else {
                    thumb = decodeSampledBitmapFromUri(context.getContentResolver(), uri, THUMB_SIZE, THUMB_SIZE);
                }

                if (thumb != null) {
                    thumbnailCache.put(uri, thumb);
                    Bitmap finalThumb = thumb;
                    mainHandler.post(() -> {
                        if (uri.equals(imageView.getTag())) {
                            imageView.setImageBitmap(finalThumb);
                        }
                    });
                }
            } catch (Exception e) {
                Log.d(TAG, "Thumbnail decode skipped for " + uri + ": " + e.getMessage());
            }
        });
    }

    private Bitmap decodeSampledBitmapFromUri(ContentResolver resolver, Uri uri, int reqWidth, int reqHeight) {
        try {
            InputStream is = resolver.openInputStream(uri);
            if (is == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);
            is.close();

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565; // save 50% RAM

            is = resolver.openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            if (is != null) is.close();
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // ── ViewHolder Pattern ───────────────────────────────────────────────────
    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivThumbnail;
        final CheckBox cbSelected;
        final TextView tvAlreadyVaulted;
        final TextView tvName;
        final TextView tvDate;
        final View dimOverlay;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.iv_gallery_thumbnail);
            cbSelected = itemView.findViewById(R.id.cb_photo_selected);
            tvAlreadyVaulted = itemView.findViewById(R.id.tv_already_vaulted_badge);
            tvName = itemView.findViewById(R.id.tv_photo_name);
            tvDate = itemView.findViewById(R.id.tv_photo_date);
            dimOverlay = itemView.findViewById(R.id.view_dim_disabled);
        }
    }
}

