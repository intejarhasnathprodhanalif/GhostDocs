# ============================================================
# GhostDocs — ProGuard / R8 Rules
# ============================================================

# ── Keep all GhostDocs domain models (Firestore needs reflection) ──────────
-keep class com.ghostdocs.vault.model.** { *; }

# ── Firebase ───────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── ML Kit ────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── OpenCV ────────────────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── ZXing (QR code generation) ────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── AndroidX Biometric ────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }

# ── Suppress misc Kotlin metadata warnings (Firebase pulls Kotlin runtime) ─
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ── Keep line number info for crash reporting ──────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

