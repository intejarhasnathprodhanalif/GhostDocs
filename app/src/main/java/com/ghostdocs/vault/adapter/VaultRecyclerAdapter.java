package com.ghostdocs.vault.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.model.DocumentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * VaultRecyclerAdapter — RecyclerView Adapter for the Secure Vault Grid
 *
 * <p>Displays a grid of {@link DocumentModel} objects synced in real-time
 * from Cloud Firestore. Each item shows the document title, type badge,
 * date, and primary tag.
 *
 * <p><b>OOP Design:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link RecyclerView.Adapter} with the
 *       inner {@link DocumentViewHolder} as the generic type parameter.</li>
 *   <li><b>Encapsulation:</b> The {@link DocumentViewHolder} inner class
 *       hides all view references; callers never touch raw Views.</li>
 *   <li><b>Abstraction:</b> The {@link OnDocumentClickListener} interface
 *       defines a contract for click events — the Activity implements it
 *       without knowing adapter internals.</li>
 *   <li><b>Polymorphism:</b> {@link #onBindViewHolder} dynamically maps
 *       different {@link DocumentModel#getDocType()} values to different
 *       badge colors at runtime.</li>
 * </ul>
 *
 * <p><b>Firestore integration:</b> When Firestore's {@code SnapshotListener}
 * delivers a new {@code QuerySnapshot}, call {@link #updateData(List)} to
 * refresh the displayed list. This is intentionally lightweight — DiffUtil
 * can be introduced in a future optimisation pass.
 */
public class VaultRecyclerAdapter
        extends RecyclerView.Adapter<VaultRecyclerAdapter.DocumentViewHolder> {

    // ── Click listener interface (Abstraction) ────────────────────────────────

    /**
     * Interface for reacting to item interactions.
     * Implemented by {@code VaultActivity}.
     */
    public interface OnDocumentClickListener {
        /**
         * Triggered on a single tap — typically opens document detail / PDF viewer.
         *
         * @param document the tapped {@link DocumentModel}
         * @param position its index in the list
         */
        void onDocumentClick(@NonNull DocumentModel document, int position);

        /**
         * Triggered on a long press — typically shows a contextual delete / share menu.
         *
         * @param document the long-pressed {@link DocumentModel}
         * @param position its index in the list
         */
        void onDocumentLongClick(@NonNull DocumentModel document, int position);
    }

    // ── Fields (Encapsulation — private) ─────────────────────────────────────
    private List<DocumentModel> documentList;
    private final Context context;
    private OnDocumentClickListener clickListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param context      the host context (used for LayoutInflater)
     * @param documentList initial (possibly empty) list of documents
     */
    public VaultRecyclerAdapter(@NonNull Context context,
                                @NonNull List<DocumentModel> documentList) {
        this.context      = context;
        this.documentList = documentList;
    }

    // ── Setter for click listener ─────────────────────────────────────────────

    public void setOnDocumentClickListener(OnDocumentClickListener listener) {
        this.clickListener = listener;
    }

    // ════════════════════════════════════════════════════════════════════════
    // RecyclerView.Adapter overrides
    // ════════════════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public DocumentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(context)
                .inflate(R.layout.item_document, parent, false);
        return new DocumentViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull DocumentViewHolder holder, int position) {
        DocumentModel doc = documentList.get(position);
        holder.bind(doc);
    }

    @Override
    public int getItemCount() {
        return documentList != null ? documentList.size() : 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Data update (called from Firestore SnapshotListener)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Replaces the current dataset with a fresh snapshot from Firestore and
     * refreshes the RecyclerView. Called on the main thread from the
     * Firestore listener in {@code VaultActivity}.
     *
     * @param newData the latest list of documents from Firestore
     */
    public void updateData(@NonNull List<DocumentModel> newData) {
        this.documentList = new ArrayList<>(newData);
        notifyDataSetChanged();
    }

    /**
     * Returns the document at the given position.
     *
     * @param position list index
     * @return the {@link DocumentModel} at that position
     */
    public DocumentModel getItem(int position) {
        return documentList.get(position);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inner ViewHolder class (Encapsulation)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * DocumentViewHolder — caches all child view references for a single
     * document card to avoid repeated {@code findViewById} calls during scroll.
     *
     * <p>Inner class follows the ViewHolder pattern mandated by RecyclerView.
     * All view references are package-private (no getters) since they are only
     * accessed inside {@link VaultRecyclerAdapter}.
     */
    public class DocumentViewHolder extends RecyclerView.ViewHolder {

        // View references
        final TextView tvDocType;
        final TextView tvTitle;
        final TextView tvDate;
        final TextView tvTag;
        final TextView tvDocIcon;
        final View     viewTypeBadge;

        public DocumentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDocType    = itemView.findViewById(R.id.tv_doc_type);
            tvTitle      = itemView.findViewById(R.id.tv_doc_title);
            tvDate       = itemView.findViewById(R.id.tv_doc_date);
            tvTag        = itemView.findViewById(R.id.tv_doc_tag);
            tvDocIcon    = itemView.findViewById(R.id.tv_doc_icon);
            viewTypeBadge = itemView.findViewById(R.id.view_type_badge);

            // Tactile touch spring scale feedback
            itemView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
                return false;
            });

            // Wire click and long-click to adapter's listener
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_ID && clickListener != null) {
                    clickListener.onDocumentClick(documentList.get(pos), pos);
                }
            });

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_ID && clickListener != null) {
                    clickListener.onDocumentLongClick(documentList.get(pos), pos);
                    return true;
                }
                return false;
            });
        }

        /**
         * Binds a {@link DocumentModel} to this ViewHolder's views.
         * Demonstrates Polymorphism — {@link #resolveBadgeColor} returns a
         * different color for each {@link DocumentModel#getDocType()} value
         * at runtime without conditionals in the caller.
         *
         * @param doc the document to display
         */
        void bind(@NonNull DocumentModel doc) {
            tvDocType.setText(doc.getDocType() != null
                    ? doc.getDocType() : DocumentModel.TYPE_OTHER);
            tvTitle.setText(doc.getTitle() != null
                    ? doc.getTitle() : "Unnamed Document");
            tvDate.setText(doc.getFormattedDate());
            tvTag.setText(doc.getPrimaryTag());

            if (tvDocIcon != null) {
                boolean isPdf = (doc.getTags() != null && doc.getTags().contains("PDF"))
                        || (doc.getTitle() != null && doc.getTitle().toLowerCase().endsWith(".pdf"));
                if (isPdf) {
                    tvDocIcon.setText("📄");
                } else if (doc.getDocType() != null) {
                    switch (doc.getDocType()) {
                        case DocumentModel.TYPE_NID:          tvDocIcon.setText("🪪"); break;
                        case DocumentModel.TYPE_RECEIPT:      tvDocIcon.setText("💳"); break;
                        case DocumentModel.TYPE_PRESCRIPTION: tvDocIcon.setText("💊"); break;
                        case DocumentModel.TYPE_PASSWORD:     tvDocIcon.setText("🔑"); break;
                        case DocumentModel.TYPE_STUDY_NOTE:   tvDocIcon.setText("📚"); break;
                        default:                              tvDocIcon.setText("🔐"); break;
                    }
                } else {
                    tvDocIcon.setText("🔐");
                }
            }

            // Set dynamic accent colours (Polymorphism at work)
            int badgeColor = resolveBadgeColor(doc.getDocType());
            viewTypeBadge.setBackgroundColor(badgeColor);
            tvDocType.setTextColor(badgeColor);

            // Dynamic soft squircle container for icon with subtle glowing border
            if (tvDocIcon != null) {
                int iconBgColor = Color.argb(30, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor));
                GradientDrawable iconDrawable = new GradientDrawable();
                iconDrawable.setShape(GradientDrawable.RECTANGLE);
                iconDrawable.setCornerRadius(24f);
                iconDrawable.setColor(iconBgColor);
                iconDrawable.setStroke(2, badgeColor);
                tvDocIcon.setBackground(iconDrawable);
            }
        }

        /**
         * Resolves a badge colour based on document type.
         * This runtime type-to-colour mapping is a form of Polymorphism —
         * the same method produces different outputs for different input types.
         *
         * @param docType one of the {@link DocumentModel} TYPE constants
         * @return a colour integer
         */
        private int resolveBadgeColor(String docType) {
            if (docType == null) return Color.parseColor("#9E9E9E");
            switch (docType) {
                case DocumentModel.TYPE_NID:          return Color.parseColor("#00D4FF"); // cyan
                case DocumentModel.TYPE_RECEIPT:      return Color.parseColor("#00E676"); // green
                case DocumentModel.TYPE_PRESCRIPTION: return Color.parseColor("#FFD600"); // amber
                case DocumentModel.TYPE_PASSWORD:     return Color.parseColor("#FF4C6A"); // red
                case DocumentModel.TYPE_STUDY_NOTE:   return Color.parseColor("#7C4DFF"); // purple
                default:                              return Color.parseColor("#9E9E9E"); // grey
            }
        }
    }
}

