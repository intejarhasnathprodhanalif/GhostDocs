package com.ghostdocs.vault.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * ThemeHelper — Application Theme Management & Mode Switching
 *
 * <p>Encapsulates the theme preferences and coordinates with {@link AppCompatDelegate}
 * to switch between Light Mode, Dark Mode, and System Default.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> Hides SharedPreferences keys, default values, and
 *       AppCompat mode constants behind clean, descriptive methods.</li>
 *   <li><b>Single Responsibility:</b> Sole authority for theme resolution and persistence.</li>
 * </ul>
 */
public final class ThemeHelper {

    private static final String PREFS_NAME = "GhostDocsPrefs";
    public static final String PREF_THEME_MODE = "app_theme_mode";

    public static final String THEME_DARK   = "dark";
    public static final String THEME_LIGHT  = "light";
    public static final String THEME_SYSTEM = "system";

    private ThemeHelper() {
        // Prevent instantiation
    }

    /**
     * Reads the stored theme setting and applies it immediately via {@link AppCompatDelegate}.
     * Must be called during {@code Application.onCreate()} or early activity initialization.
     *
     * @param context Application context.
     */
    public static void applyTheme(@NonNull Context context) {
        String mode = getThemeMode(context);
        applyThemeMode(mode);
    }

    /**
     * Updates and persists the user's theme mode selection, immediately updating the active theme.
     *
     * @param context   Host context.
     * @param themeMode One of {@link #THEME_DARK}, {@link #THEME_LIGHT}, or {@link #THEME_SYSTEM}.
     */
    public static void setThemeMode(@NonNull Context context, @NonNull String themeMode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_THEME_MODE, themeMode).apply();
        applyThemeMode(themeMode);
    }

    /**
     * Returns the currently saved theme mode key. Defaults to {@link #THEME_DARK}
     * preserving the signature GhostDocs aesthetic out-of-the-box.
     *
     * @param context Host context.
     * @return Current theme mode string.
     */
    @NonNull
    public static String getThemeMode(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_THEME_MODE, THEME_DARK);
    }

    /**
     * Returns a human-friendly display label for settings displays and dialogs.
     *
     * @param context Host context.
     * @return Localized or formatted theme name.
     */
    @NonNull
    public static String getThemeDisplayName(@NonNull Context context) {
        String mode = getThemeMode(context);
        switch (mode) {
            case THEME_LIGHT:
                return "Light Mode (Clean Vault)";
            case THEME_SYSTEM:
                return "System Default";
            case THEME_DARK:
            default:
                return "Dark Mode (Ghost Aesthetic)";
        }
    }

    /**
     * Translates the internal theme key to the corresponding {@link AppCompatDelegate} constant.
     */
    private static void applyThemeMode(@NonNull String themeMode) {
        switch (themeMode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_SYSTEM:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case THEME_DARK:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }
}
