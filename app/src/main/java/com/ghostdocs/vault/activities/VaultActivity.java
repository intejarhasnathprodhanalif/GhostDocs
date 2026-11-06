package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;

/**
 * VaultActivity — Main Secure Vault Dashboard
 *
 * <p><b>Current behavior (Step 1 stub):</b> Displays a placeholder vault screen.
 *
 * <p><b>Full implementation (Step 3):</b> Will include:
 * <ul>
 *   <li>RecyclerView populated from Cloud Firestore via FirestoreRecyclerAdapter</li>
 *   <li>Start/stop WatcherService controls</li>
 *   <li>Navigation to SettingsActivity</li>
 *   <li>Firebase sign-out</li>
 * </ul>
 *
 * <p>OOP Note: Extends {@link AppCompatActivity} (Inheritance).
 * Uses {@code launchMode="singleTop"} to prevent multiple instances.
 */
public class VaultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault);

        // Stub: inform the developer this screen is not yet implemented
        Toast.makeText(this,
                "Vault — coming in Step 3",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Handles re-launch from widget or notification (singleTop mode)
        setIntent(intent);
    }
}

