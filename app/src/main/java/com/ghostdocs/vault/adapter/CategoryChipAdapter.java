package com.ghostdocs.vault.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ghostdocs.vault.R;
import com.ghostdocs.vault.model.CategoryChipModel;
import com.ghostdocs.vault.model.DocumentModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CategoryChipAdapter — Recycler adapter for horizontal category filter pills.
 *
 * <p>Supports real-time count updates, single-selection highlighting, and polymorphic
 * filtering dispatch to {@link com.ghostdocs.vault.activities.VaultActivity}.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Inheritance:</b> Extends {@link RecyclerView.Adapter}.</li>
 *   <li><b>Encapsulation:</b> Selection state and chip binding are internally managed.</li>
 *   <li><b>Abstraction:</b> Dispatches clicks through {@link OnCategorySelectedListener}.</li>
 * </ul>
 */
public class CategoryChipAdapter extends RecyclerView.Adapter<CategoryChipAdapter.ChipViewHolder> {

    public interface OnCategorySelectedListener {
        void onCategorySelected(@NonNull String categoryId);
    }

    private final Context context;
    private final List<CategoryChipModel> chipList;
    private final OnCategorySelectedListener listener;
    private String selectedCategoryId = CategoryChipModel.CATEGORY_ALL;

    public CategoryChipAdapter(@NonNull Context context,
                               @NonNull OnCategorySelectedListener listener) {
        this.context = context;
        this.listener = listener;
        this.chipList = initializeDefaultChips();
    }

    private List<CategoryChipModel> initializeDefaultChips() {
        List<CategoryChipModel> list = new ArrayList<>();
        list.add(new CategoryChipModel(CategoryChipModel.CATEGORY_ALL, "All", "📁", 0, true));
        list.add(new CategoryChipModel(DocumentModel.TYPE_RECEIPT, "Financial", "💳", 0, false));
        list.add(new CategoryChipModel(DocumentModel.TYPE_NID, "Identity", "🆔", 0, false));
        list.add(new CategoryChipModel(DocumentModel.TYPE_PRESCRIPTION, "Medical", "💊", 0, false));
        list.add(new CategoryChipModel(DocumentModel.TYPE_PASSWORD, "Passwords", "🔑", 0, false));
        list.add(new CategoryChipModel(DocumentModel.TYPE_STUDY_NOTE, "Notes", "📚", 0, false));
        list.add(new CategoryChipModel(DocumentModel.TYPE_OTHER, "General", "📄", 0, false));
        return list;
    }

    @NonNull
    @Override
    public ChipViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_category_chip, parent, false);
        return new ChipViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChipViewHolder holder, int position) {
        CategoryChipModel chip = chipList.get(position);

        holder.tvLabel.setText(chip.getDisplayLabel());

        boolean isSelected = chip.getId().equalsIgnoreCase(selectedCategoryId);
        chip.setSelected(isSelected);

        if (isSelected) {
            holder.layoutRoot.setBackgroundResource(R.drawable.bg_chip_selected);
            holder.tvLabel.setTextColor(ContextCompat.getColor(context, R.color.ghostOnPrimary));
        } else {
            holder.layoutRoot.setBackgroundResource(R.drawable.bg_chip_unselected);
            holder.tvLabel.setTextColor(ContextCompat.getColor(context, R.color.ghostOnSurfaceMuted));
        }

        holder.layoutRoot.setOnClickListener(v -> {
            if (!chip.getId().equalsIgnoreCase(selectedCategoryId)) {
                selectedCategoryId = chip.getId();
                notifyDataSetChanged();
                if (listener != null) {
                    listener.onCategorySelected(selectedCategoryId);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return chipList.size();
    }

    /**
     * Updates document count for all categories and refreshes the pills.
     */
    public void updateCounts(@NonNull Map<String, Integer> categoryCounts, int totalCount) {
        for (CategoryChipModel chip : chipList) {
            if (CategoryChipModel.CATEGORY_ALL.equalsIgnoreCase(chip.getId())) {
                chip.setCount(totalCount);
            } else {
                Integer count = categoryCounts.get(chip.getId().toUpperCase());
                chip.setCount(count != null ? count : 0);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    public String getSelectedCategoryId() {
        return selectedCategoryId;
    }

    public void setSelectedCategoryId(@NonNull String categoryId) {
        this.selectedCategoryId = categoryId;
        notifyDataSetChanged();
    }

    static class ChipViewHolder extends RecyclerView.ViewHolder {
        final RelativeLayout layoutRoot;
        final TextView tvLabel;

        ChipViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutRoot = itemView.findViewById(R.id.layout_chip_root);
            tvLabel = itemView.findViewById(R.id.tv_chip_label);
        }
    }
}
