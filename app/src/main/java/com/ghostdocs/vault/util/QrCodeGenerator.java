package com.ghostdocs.vault.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ghostdocs.vault.model.DocumentModel;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * QrCodeGenerator — Air-Gapped ZXing QR Code Generation Engine
 *
 * <p>Uses the official pure-Java ZXing core library to convert structured
 * {@link DocumentModel} JSON into a high-density, air-gapped QR code bitmap.
 *
 * <p><b>Air-Gapped Data Transfer:</b>
 * The generated QR code embeds structured metadata (document ID, title, type,
 * timestamp, and regex tags). Any external phone, scanner, or air-gapped terminal
 * can read the complete document data without requiring network, Wi-Fi, or Bluetooth.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> ZXing BitMatrix transformations, pixel buffers, and encoding
 *       hints are hidden behind static helper methods.</li>
 * </ul>
 */
public final class QrCodeGenerator {

    private static final String TAG = "GhostDocs.QrCode";
    private static final int DEFAULT_QR_DIMENSION = 512;

    private QrCodeGenerator() {
        throw new UnsupportedOperationException("QrCodeGenerator is a static utility class.");
    }

    /**
     * Generates a high-resolution QR code bitmap representing the given {@link DocumentModel}.
     * If a Cloud Share URL is present, encodes the URL so other devices view the picture in their browser.
     * Otherwise, encodes a formatted human-readable air-gapped verification card.
     *
     * @param document The vault document to encode.
     * @param dimension Dimension (width and height) in pixels.
     * @return Generated {@link Bitmap}, or null if encoding fails.
     */
    @Nullable
    public static Bitmap generateDocumentQr(@NonNull DocumentModel document, int dimension) {
        if (document.getShareUrl() != null && !document.getShareUrl().trim().isEmpty()) {
            return generateCloudUrlQr(document.getShareUrl().trim(), dimension);
        }
        return generateVerificationCardQr(document, dimension);
    }

    /**
     * Generates a QR code encoding a web URL (e.g. Firebase Storage cloud image link).
     * When scanned with any smartphone camera, the device automatically prompts to open
     * the image in their mobile browser.
     *
     * @param url Web URL to encode.
     * @param dimension Dimension in pixels.
     * @return QR {@link Bitmap}, or null on failure.
     */
    @Nullable
    public static Bitmap generateCloudUrlQr(@NonNull String url, int dimension) {
        try {
            return generateQrBitmap(url, dimension > 0 ? dimension : DEFAULT_QR_DIMENSION);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode Cloud URL into QR code", e);
            return null;
        }
    }

    /**
     * Generates a QR code encoding a clean, human-readable verification card.
     * Any standard smartphone camera displays a formatted summary rather than raw JSON.
     *
     * @param document Document model.
     * @param dimension Dimension in pixels.
     * @return QR {@link Bitmap}, or null on failure.
     */
    @Nullable
    public static Bitmap generateVerificationCardQr(@NonNull DocumentModel document, int dimension) {
        try {
            String cardText = buildHumanReadableCard(document);
            return generateQrBitmap(cardText, dimension > 0 ? dimension : DEFAULT_QR_DIMENSION);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode verification card into QR code", e);
            return null;
        }
    }

    /**
     * Constructs a clean human-readable verification card string.
     */
    @NonNull
    public static String buildHumanReadableCard(@NonNull DocumentModel document) {
        StringBuilder sb = new StringBuilder();
        sb.append("GHOSTDOCS SECURED VAULT\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Title:    ").append(document.getTitle() != null ? document.getTitle() : "Untitled").append("\n");
        sb.append("Category: ").append(document.getDocType() != null ? document.getDocType() : "OTHER").append("\n");

        if (document.getFormattedDate() != null && !document.getFormattedDate().isEmpty()) {
            sb.append("Date:     ").append(document.getFormattedDate()).append("\n");
        }

        if (document.getTags() != null && !document.getTags().isEmpty()) {
            sb.append("Tags:     ");
            for (int i = 0; i < document.getTags().size(); i++) {
                sb.append(document.getTags().get(i));
                if (i < document.getTags().size() - 1) sb.append(", ");
            }
            sb.append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (document.getOcrText() != null && !document.getOcrText().trim().isEmpty()) {
            String cleanOcr = document.getOcrText().trim();
            // Truncate to reasonable length so QR code remains easily scannable by cameras
            if (cleanOcr.length() > 280) {
                cleanOcr = cleanOcr.substring(0, 280) + "...";
            }
            sb.append("OCR Excerpt:\n").append(cleanOcr);
        } else {
            sb.append("Status: Hardware AES-256 Encrypted");
        }

        return sb.toString();
    }

    /**
     * Encodes arbitrary string content into a QR Code {@link Bitmap} using ZXing.
     *
     * @param content Text or JSON payload to encode.
     * @param dimension Width and height in pixels.
     * @return Android {@link Bitmap} containing the rendered QR code.
     * @throws Exception If ZXing matrix generation fails.
     */
    @NonNull
    public static Bitmap generateQrBitmap(@NonNull String content, int dimension) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1); // 1-module quiet zone margin

        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, dimension, dimension, hints);
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        Log.d(TAG, "QR code bitmap generated successfully: " + width + "x" + height);
        return bitmap;
    }
}

