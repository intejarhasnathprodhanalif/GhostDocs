package com.ghostdocs.vault.activities;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;

/**
 * SettingsActivity — Application Configuration Screen
 *
 * <p><b>Current behavior (Step 1 stub):</b> Displays a placeholder settings screen.
 *
 * <p><b>Full implementation (Step 3):</b> Will include:
 * <ul>
 *   <li>ListView-based settings menu (auto-start, delete original, etc.)</li>
 *   <li>Custom {@link android.widget.ArrayAdapter} for list items</li>
 *   <li>SharedPreferences persistence for user settings</li>
 * </ul>
 *
 * <p>OOP Note: Extends {@link AppCompatActivity} (Inheritance).
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toast.makeText(this,
                "Settings — coming in Step 3",
                Toast.LENGTH_SHORT).show();
    }
}
