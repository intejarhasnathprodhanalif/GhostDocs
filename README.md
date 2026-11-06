# 👻 GhostDocs (The Automated Secure Vault)

An intelligent Android security application that acts as an invisible background guardian, automatically detecting, extracting (OCR), encrypting, and organizing sensitive screenshots/photos into a biometric-locked PDF/QR wallet, with secure cloud synchronization.

---

## 🛠️ Technology Stack & Constraints

* **Language**: 100% Java (strictly enforcing Core OOP principles: Encapsulation, Inheritance, Polymorphism, Abstraction).
* **UI Architecture**: Native Android XML (Strictly `ConstraintLayout` and `RelativeLayout`).
* **Build System**: Android Studio with Gradle (AGP 8.5+, Gradle 8.7, Java 17).
* **Backend & Persistence**:
  * **Firebase Authentication**: Identity verification (Email/Password + Google Sign-In).
  * **Cloud Firestore**: Structured document metadata, OCR tags, and settings with offline persistence (`PersistentCacheSettings.CACHE_SIZE_UNLIMITED`).
  * **Firebase Realtime Database**: Low-latency background watcher status telemetry (`ACTIVE`, `SCANNING`, `STOPPED`, `OFFLINE` hooks).
* **Third-Party Libraries**: Strictly restricted to:
  * Google ML Kit Text Recognition (`com.google.mlkit:text-recognition:16.0.1`)
  * OpenCV for Android (`org.opencv:opencv:4.10.0`)
  * ZXing Core (`com.google.zxing:core:3.5.3`)
  * Official Firebase Android SDKs (`firebase-bom`)
  * *Native Android APIs for everything else.*

---

## 🏛️ Architecture & Component Matrix

| Android Component | Implementation | Purpose |
|---|---|---|
| **Activity (Launcher)** | `LaunchActivity.java` | Auth gate; evaluates Firebase session and invokes `BiometricPrompt`. |
| **Activity (Auth)** | `LoginActivity.java` | Email/password registration and Google Sign-In credential exchange. |
| **Activity (Dashboard)** | `VaultActivity.java` | 2-column `RecyclerView` grid real-time synced with Cloud Firestore. |
| **Activity (Settings)** | `SettingsActivity.java` | `ListView` with custom `LogListAdapter` for user preferences and export management. |
| **Foreground Service** | `WatcherService.java` | The "Invisible Watcher" keeping the background pipeline alive (`START_STICKY`). |
| **ContentObserver** | `MediaContentObserver.java` | Monitors `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with debouncing and duplicate filtering. |
| **BroadcastReceiver** | `BootReceiver.java` | Restarts `WatcherService` on `BOOT_COMPLETED` (respecting user preference). |
| **AppWidgetProvider** | `VaultWidgetProvider.java` | Home screen widget displaying live doc counts, watcher status, and quick launch. |
| **ContentProvider** | `androidx.core.content.FileProvider` | Sandboxed URI generation for PDF export and file sharing. |
| **Multithreading** | `VaultExecutor.java` & `BaseVaultTask.java` | 4-thread daemon thread pool running all heavy CV, OCR, crypto, and PDF jobs off the Main Thread. |

---

## 🧩 Core OOP Principles Enforced

1. **Encapsulation**:
   * `DocumentModel`: Private fields with public getters/setters, native `org.json.JSONObject` serialization.
   * `AesEncryptionHelper`: Android KeyStore key management, 12-byte IV nonce prepending, and AES-256-GCM cipher streams.
   * `OpenCvCropper`: Native OpenCV `Mat` allocation, edge thresholding, and matrix de-allocation (`mat.release()`).
2. **Inheritance**:
   * `BaseVaultTask<T>` extended by `CropTask`, `OcrTask`, `EncryptTask`, and `PdfGeneratorTask`.
   * `VaultRecyclerAdapter` extends `RecyclerView.Adapter<DocumentViewHolder>`.
   * `LogListAdapter` extends `ArrayAdapter<LogItem>`.
3. **Polymorphism**:
   * `DocumentRule` interface implemented by `NidRule`, `TransactionRule`, `PasswordRule`, and `PrescriptionRule`.
   * `BaseVaultTask` overrides `doInBackground()` across different algorithms.
   * Dynamic color badging in `VaultRecyclerAdapter.resolveBadgeColor()`.
4. **Abstraction**:
   * `BiometricCallback` contract for fingerprint/device PIN authentication.
   * `OnImageCapturedListener` for media sensor events.
   * `TaskCallback<T>` generic interface for asynchronous background computation.

---

## ⚡ Pipeline Flow

```
[ User captures sensitive photo or screenshot ]
                      ↓
           MediaContentObserver
                      ↓
          VaultExecutor Thread Pool
                      ↓
        1. OpenCvCropper (CropTask)
           • Canny edge detection
           • 4-point perspective warp
                      ↓
        2. ML Kit OCR (OcrTask)
           • Text recognition
           • Bounding box coordinate extraction
                      ↓
        3. RegexClassifierEngine
           • Rule matching: NID, TrxID, Passwords, Prescriptions
           • native org.json.JSONObject construction
                      ↓
        4. AesEncryptionHelper (EncryptTask)
           • Hardware AES-256-GCM encryption
           • Erase original media via ContentResolver.delete()
                      ↓
        5. PdfGeneratorTask (android.graphics.pdf.PdfDocument)
           • Draw image bitmap
           • Draw transparent OCR text layer over bounding boxes
                      ↓
        6. Firebase Sync & Local Notification
           • Push DocumentModel to Cloud Firestore (users/{uid}/documents)
           • Stream watcher status to Firebase Realtime Database
           • Update home screen VaultWidgetProvider
           • Fire local notification alert
```

---

## 🚀 Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/intejarhasnathprodhanalif/GhostDocs.git
   ```
2. Open the project in **Android Studio** (Koala / Ladybug / Iguana or newer).
3. Place your `google-services.json` inside the `app/` directory:
   * Enable **Authentication** (Email/Password & Google).
   * Enable **Cloud Firestore**.
   * Enable **Realtime Database**.
4. Build and deploy to an Android device (API 26 to 35+ supported).
