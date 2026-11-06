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
     *
     * @param document The vault document to encode.
     * @param dimension Dimension (width and height) in pixels.
     * @return Generated {@link Bitmap}, or null if encoding fails.
     */
    @Nullable
    public static Bitmap generateDocumentQr(@NonNull DocumentModel document, int dimension) {
        try {
            JSONObject json = document.toJson();
            return generateQrBitmap(json.toString(), dimension > 0 ? dimension : DEFAULT_QR_DIMENSION);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode DocumentModel into QR code", e);
            return null;
        }
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
