package com.ghostdocs.vault.model;

import androidx.annotation.NonNull;

/**
 * CategoryChipModel — Data model for category filter pills in the Vault dashboard.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> Private fields with public getters/setters.</li>
 *   <li><b>Immutability:</b> Category identifiers are immutable.</li>
 * </ul>
 */
public class CategoryChipModel {

    public static final String CATEGORY_ALL = "ALL";

    private final String id;
    private final String name;
    private final String emoji;
    private int count;
    private boolean isSelected;

    public CategoryChipModel(@NonNull String id,
                             @NonNull String name,
                             @NonNull String emoji,
                             int count,
                             boolean isSelected) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.count = count;
        this.isSelected = isSelected;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public String getEmoji() {
        return emoji;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    @NonNull
    public String getDisplayLabel() {
        if ("ALL".equals(id)) {
            return name + (count > 0 ? " (" + count + ")" : "");
        }
        return name + " (" + count + ")";
    }
}

