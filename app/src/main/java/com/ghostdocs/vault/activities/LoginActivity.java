package com.ghostdocs.vault.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ghostdocs.vault.R;
import com.google.firebase.auth.FirebaseAuth;

/**
 * LoginActivity — Firebase Authentication Screen
 *
 * <p><b>Current behavior (Step 1 stub):</b> Displays a placeholder login screen.
 * No actual sign-in functionality yet.
 *
 * <p><b>Full implementation (Step 2):</b> Will include:
 * <ul>
 *   <li>Email/password sign-in via {@link FirebaseAuth}</li>
 *   <li>Google Sign-In via GoogleSignInClient</li>
 *   <li>Account creation flow</li>
 *   <li>Input validation and error display</li>
 * </ul>
 *
 * <p>OOP Note: Extends {@link AppCompatActivity} (Inheritance). Firebase Auth
 * interactions will be encapsulated behind an AuthRepository class in Step 2.
 */
public class LoginActivity extends AppCompatActivity {

    // TODO (Step 2): Replace stub with full Firebase Auth + Google Sign-In
    private FirebaseAuth firebaseAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        firebaseAuth = FirebaseAuth.getInstance();

        // Stub: inform the developer this screen is not yet implemented
        Toast.makeText(this,
                "Login screen — coming in Step 2",
                Toast.LENGTH_LONG).show();
    }
}
