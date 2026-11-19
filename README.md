# GhostDocs — The Automated Secure Vault

<p align="center">
  <img src="ghostdocs_logo.jpg" alt="GhostDocs App Logo" width="160" style="border-radius: 28px;" />
</p>
<p align="center">
  <b>Zero-Knowledge Hardware-Backed Encrypted Vault for Photos, IDs & PDFs</b>
</p>

## 📖 Project Description

**GhostDocs** is a 100% Java Android security application built around an invisible background guardian (`WatcherService`) and a biometric-locked vault dashboard. It automatically monitors newly captured photos, screenshots, and saved PDFs—or lets you batch-import existing files from your gallery and downloads—crops physical cards and documents with OpenCV, extracts multi-page text with on-device Google ML Kit OCR, classifies items into intelligent genres (Identity/IDs, Financial Receipts, Medical Prescriptions, Passwords, Study Notes, and General), seals both the processed document and its untouched original backup using Android KeyStore-backed **AES-256-GCM Dual-Vault encryption**, removes originals cleanly under Android 10–15 Scoped Storage, generates searchable PDFs and air-gapped QR verification cards, and synchronizes metadata with Firebase Cloud Firestore and Realtime Database.

---

## ✨ Key Features

* **Dual AES-256-GCM Vault Architecture**:
  * **Primary Vault (`<docId>.enc`)**: Encrypts the OpenCV perspective-flattened document/card image (or lossless native PDF bytes) using a hardware-backed Android KeyStore AES-256-GCM key with a unique 12-byte IV per file.
  * **Recovery Vault (`<docId>_orig.enc`)**: Automatically retains an encrypted copy of the untouched original camera photo whenever OpenCV crops a card or document, enabling full recovery back to your gallery or lossless export.
* **Photos & PDFs Full Parity**:
  * Supports both images (`JPEG`/`PNG`) and multi-page PDF documents across gallery batch import (`ImportPhotosActivity`), real-time `WatcherService` detection, and Vault management.
  * Multi-page PDF OCR (`OcrTask` via `PdfExportHelper`, up to 4 pages per PDF) for classification, full-text search, and smart titling.
* **Intelligent Document Classification & Organization**:
  * Rule-based [`RegexClassifierEngine`](app/src/main/java/com/ghostdocs/vault/classifier/RegexClassifierEngine.java) sorts secured files into **Financial**, **Identity (ID)** (National ID, Library Card, Student/Employee ID, Passport, Driving License, Admit Card), **Medical** (Prescriptions & clinical docs), **Passwords**, **Study Notes**, and **General**, with manual **Change Category** and **Rename** options inside the Vault.
  * Automatically excludes academic lecture/class notes from interception when enabled (`exclude_study_notes`).
* **Smart Background Guardian & Scoped Storage Cleanup**:
  * **Ask-First or Auto-Vault**: Interactive heads-up confirmation prompt (`SecurePromptActivity`) or unattended background vaulting via `WatcherService`.
  * **Reliable Gallery Removal (`MediaDeleteHelper`)**: Resolves Photo Picker (`media/picker/...`) and DocumentsProvider (`media.documents`) URIs to canonical MediaStore URIs for `MediaStore.createDeleteRequest` (Android 11+), handles Android 10 `RecoverableSecurityException` prompts, supports `DeleteConsentActivity` one-tap follow-up from background Watcher runs, and allows optional `All Files Access` (`MANAGE_EXTERNAL_STORAGE`) from Settings.
* **Export, Search, Recovery & Air-Gapped Sharing**:
  * Full-text search across titles, genres, tags, and extracted OCR text.
  * Searchable PDF export (`PdfGeneratorTask` with invisible OCR text overlay) and gallery JPG restoration (`VaultActivity` + **Restore All to Gallery** in `SettingsActivity`).
  * Offline **Air-Gapped QR Verification Cards** (`QrCodeGenerator` + ZXing) requiring zero internet to scan document verification details.
* **Adaptive Portrait & Landscape UI + Themes**:
  * Dedicated responsive portrait (`layout/`) and landscape (`layout-land/`) layouts with multi-column grid adaptation (`integers.xml` / `values-land/integers.xml`) and **Dark / Light / System** theme modes (`ThemeHelper`).
  * Home screen `VaultWidgetProvider` and resilient offline Firestore caching + real-time network interruption guards (`NetworkHelper`).

---

## 🛠️ Technology Stack & Constraints

* **Language**: 100% Java (strictly enforcing Encapsulation, Inheritance, Polymorphism, Abstraction).
* **UI Architecture**: Native Android XML (`ConstraintLayout` and `RelativeLayout`) with Material Components, portrait & landscape layouts, and dynamic state preservation.
* **Build System**: Android Studio with Gradle (AGP 8.5+, Java 17 bytecode, `minSdk 26`, `targetSdk 35`).
* **Backend & Persistence**:
  * **Firebase Authentication**: Identity gate (Email/Password + Google Sign-In) paired with Android `BiometricPrompt`.
  * **Cloud Firestore**: Per-user metadata records (`users/{userId}/documents/{docId}`) with offline persistence (`PersistentCacheSettings.CACHE_SIZE_UNLIMITED`).
  * **Firebase Realtime Database**: Live Watcher telemetry stream (`ACTIVE`, `SCANNING`, `STOPPED`, `OFFLINE`).
* **Libraries**:
  * Google ML Kit Text Recognition (`com.google.mlkit:text-recognition:16.0.1`)
  * OpenCV for Android (`org.opencv:opencv:4.10.0`)
  * ZXing Core (`com.google.zxing:core:3.5.3`)
  * Firebase Android BoM (`com.google.firebase:firebase-bom:35.1.0`) & Google Play Services Auth

---

## 🏛️ Architecture & Component Matrix

| Android Component | Implementation | Purpose |
|---|---|---|
| **Activity (Launcher)** | `LaunchActivity.java` | Entry auth gate; verifies Firebase session and invokes `BiometricPrompt`. |
| **Activity (Auth)** | `LoginActivity.java` | Email/password login/registration and Google Sign-In credential exchange. |
| **Activity (Dashboard)** | `VaultActivity.java` | Adaptive 2/3-column `RecyclerView` grid synced with Cloud Firestore, chips & search. |
| **Activity (Batch Import)** | `ImportPhotosActivity.java` | Scans device screenshots/PDFs, runs OCR suggestions, batch secures, and deletes originals. |
| **Activity (Consent UI)** | `SecurePromptActivity.java` | Interactive heads-up confirmation preview (photo & Page-1 PDF) before securing. |
| **Activity (Delete Consent)** | `DeleteConsentActivity.java` | Foreground Scoped Storage helper handling OS delete confirmation from Watcher alerts. |
| **Activity (Settings)** | `SettingsActivity.java` | Theme, Watcher, auto-delete, study-note exclusion, permissions, bulk PDF/gallery restore. |
| **Activity (Profile)** | `ProfileActivity.java` | Manages display name, account details, and password updates. |
| **Foreground Service** | `WatcherService.java` | The "Invisible Watcher" keeping real-time protection alive (`START_STICKY`). |
| **ContentObserver** | `MediaContentObserver.java` | Monitors `MediaStore.Images.Media` and `MediaStore.Files` (new PDFs) with debouncing. |
| **BroadcastReceiver** | `BootReceiver.java` | Restarts `WatcherService` on `BOOT_COMPLETED` (respecting user preferences). |
| **AppWidgetProvider** | `VaultWidgetProvider.java` | Home screen widget showing live secured count, Watcher state, and quick open. |
| **ContentProvider** | `androidx.core.content.FileProvider` | Secure sandboxed URI sharing for PDFs, JPGs, and exports (`file_provider_paths.xml`). |
| **Multithreading** | `VaultExecutor.java` & `BaseVaultTask.java` | Fixed 4-thread daemon pool executing CV, OCR, AES encryption, and PDF tasks off UI thread. |

---

## 🧩 Core OOP Principles Enforced

1. **Encapsulation**:
   * `DocumentModel` & `GalleryPhotoItem`: Private state with controlled accessors and JSON/Firestore mapping.
   * `AesEncryptionHelper`: Android KeyStore AES-256-GCM key management, 12-byte IV prepending, and dual-vault encryption/decryption streams.
   * `OpenCvCropper`: Native OpenCV `Mat` lifecycle, multi-strategy edge/Otsu contour detection (including rounded-corner ID cards), and explicit matrix release.
   * `MediaDeleteHelper`: Encapsulates Photo Picker, `media.documents`, and `MediaStore` canonical URI resolution & deletion fallback.
2. **Inheritance**:
   * `BaseVaultTask<T>` extended by `CropTask`, `OcrTask`, `EncryptTask`, and `PdfGeneratorTask`.
   * `VaultRecyclerAdapter` (`RecyclerView.Adapter`), `GalleryPhotoAdapter` (`RecyclerView.Adapter`), and `LogListAdapter` (`ArrayAdapter`).
3. **Polymorphism**:
   * `DocumentRule` interface implemented by `ExplicitIdentityRule`, `TransactionRule`, `PasswordRule`, `PrescriptionRule`, `IdentityFallbackRule`, and `StudyNoteRule`.
   * `BaseVaultTask.doInBackground()` overridden per pipeline stage, plus runtime badge/filter dispatch in `VaultRecyclerAdapter`.
4. **Abstraction**:
   * Callback interfaces: `BiometricCallback`, `OnImageCapturedListener`, `TaskCallback<T>`, `PdfPageProcessor`, `OnDocumentClickListener`, and `OnSelectionChangedListener`.

---

## ⚡ Pipeline Flow

```
[ New Photo / Screenshot / PDF captured or batch-selected ]
                          ↓
     MediaContentObserver / ImportPhotosActivity
                          ↓
            VaultExecutor (Worker Pool)
                          ↓
  ┌─ If Photo: OpenCvCropper (CropTask) ──────────────────────┐
  │    • 3-pass contour detection + rounded-card minAreaRect  │
  │    • 4-point perspective warp (retaining uncropped copy)  │
  └─ If PDF: PdfExportHelper.processPdfPages ─────────────────┘
                          ↓
  2. ML Kit Text Recognition (OcrTask)
     • Multi-page text extraction (up to 4 pages for PDFs)
     • Bounding box coordinates for searchable PDF overlay
                          ↓
  3. RegexClassifierEngine
     • Classifies ID / Receipt / Prescription / Password / Study Note / Other
     • Generates smart contextual title & structured tags
                          ↓
  4. Dual AES-256-GCM Encryption (EncryptTask / AesEncryptionHelper)
     • <docId>.enc      → Processed document or lossless native PDF
     • <docId>_orig.enc → Untouched original photo for recovery
     • Scoped Storage deletion (MediaDeleteHelper / createDeleteRequest)
                          ↓
  5. Searchable PDF & Export (PdfGeneratorTask / PdfExportHelper)
     • Draws image bitmap + invisible OCR text layer (or exports native PDF)
                          ↓
  6. Cloud Sync, Widget & Alerts
     • Commit metadata to Cloud Firestore (users/{uid}/documents/{docId})
     • Stream live status to Firebase Realtime Database
     • Refresh VaultWidgetProvider & fire local notifications
```

---

## 🚀 Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/intejarhasnathprodhanalif/GhostDocs.git
   ```
2. Open the project in **Android Studio** (AGP 8.5+, Java 17 toolchain).
3. Place your `google-services.json` inside `app/`:
   * Enable **Authentication** (Email/Password & Google Sign-In).
   * Enable **Cloud Firestore**.
   * Enable **Realtime Database**.
4. Build and run on an Android device (API 26–35+).
5. *(Recommended for background auto-delete)* Grant storage & notification permissions, and optionally enable **All Files Access** under **Settings → Storage Permissions** so `WatcherService` can erase secured originals from the gallery automatically.
