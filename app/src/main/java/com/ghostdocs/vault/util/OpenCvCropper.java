package com.ghostdocs.vault.util;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * OpenCvCropper — Computer Vision Document Cropping & De-skewing Engine
 *
 * <p>Encapsulates OpenCV Mat operations, edge detection, contour analysis, and
 * 4-point perspective transformations to straighten, flatten, and crop skewed
 * document photos.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Encapsulation:</b> All native Mat allocation, memory release, OpenCV point
 *       ordering, and matrix mathematics are hidden behind a single public method
 *       {@link #cropAndFlatten(Bitmap)}.</li>
 * </ul>
 */
public final class OpenCvCropper {

    private static final String TAG = "GhostDocs.OpenCv";
    private static final double TARGET_PROCESSING_HEIGHT = 800.0;
    private static final double MIN_DOCUMENT_AREA_RATIO = 0.12; // Must be at least 12% of total image

    private OpenCvCropper() {
        throw new UnsupportedOperationException("OpenCvCropper is a utility class.");
    }

    /**
     * Detects document boundaries in the provided Bitmap, applies a 4-point perspective
     * transform to de-skew and flatten it, and returns the cropped Bitmap.
     *
     * <p>If no distinct 4-corner document contour is found (e.g., standard flat screenshot),
     * it safely returns the original Bitmap without modification.
     *
     * @param sourceBitmap The input image bitmap.
     * @return The cropped/flattened Bitmap, or the original Bitmap if no document quad was found.
     */
    @NonNull
    public static Bitmap cropAndFlatten(@NonNull Bitmap sourceBitmap) {
        Mat srcMat = null;
        Mat resizedMat = null;
        Mat grayMat = null;
        Mat blurMat = null;
        Mat edgeMat = null;
        Mat dilatedMat = null;
        Mat warpedMat = null;

        try {
            // 1. Convert source Bitmap to OpenCV Mat (RGBA)
            srcMat = new Mat();
            Utils.bitmapToMat(sourceBitmap, srcMat);

            int origWidth = srcMat.cols();
            int origHeight = srcMat.rows();

            if (origWidth <= 0 || origHeight <= 0) {
                return sourceBitmap;
            }

            // 2. Downscale image for fast, consistent edge detection
            double scale = origHeight / TARGET_PROCESSING_HEIGHT;
            int resizedWidth = (int) (origWidth / scale);
            int resizedHeight = (int) TARGET_PROCESSING_HEIGHT;

            resizedMat = new Mat();
            Imgproc.resize(srcMat, resizedMat, new Size(resizedWidth, resizedHeight));

            // 3. Grayscale conversion
            grayMat = new Mat();
            Imgproc.cvtColor(resizedMat, grayMat, Imgproc.COLOR_RGBA2GRAY);

            // 4. Gaussian Blur to eliminate high-frequency noise
            blurMat = new Mat();
            Imgproc.GaussianBlur(grayMat, blurMat, new Size(5, 5), 0);

            // 5. Canny Edge Detection
            edgeMat = new Mat();
            Imgproc.Canny(blurMat, edgeMat, 50, 150);

            // 6. Morphological dilation to close gaps in contour borders
            dilatedMat = new Mat();
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edgeMat, dilatedMat, kernel);
            kernel.release();

            // 7. Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(dilatedMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();

            // Sort contours by area descending
            Collections.sort(contours, (c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));

            double totalArea = resizedWidth * resizedHeight;
            Point[] documentCorners = null;

            // 8. Find largest 4-corner contour
            for (MatOfPoint contour : contours) {
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double perimeter = Imgproc.arcLength(contour2f, true);

                MatOfPoint2f approx2f = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx2f, 0.02 * perimeter, true);

                double area = Imgproc.contourArea(approx2f);

                if (approx2f.total() == 4 && Imgproc.isContourConvex(new MatOfPoint(approx2f.toArray()))) {
                    if (area >= (totalArea * MIN_DOCUMENT_AREA_RATIO)) {
                        documentCorners = approx2f.toArray();
                        approx2f.release();
                        contour2f.release();
                        break;
                    }
                }
                approx2f.release();
                contour2f.release();
            }

            // Clean up contour list
            for (MatOfPoint c : contours) {
                c.release();
            }

            // 9. If no quadrilateral found, return original bitmap safely
            if (documentCorners == null || documentCorners.length != 4) {
                Log.d(TAG, "No distinct document quadrilateral found. Keeping original image.");
                return sourceBitmap;
            }

            // 10. Scale corner coordinates back to original full-resolution dimensions
            Point[] scaledCorners = new Point[4];
            for (int i = 0; i < 4; i++) {
                scaledCorners[i] = new Point(documentCorners[i].x * scale, documentCorners[i].y * scale);
            }

            // 11. Apply 4-point perspective warp
            warpedMat = applyPerspectiveTransform(srcMat, scaledCorners);

            if (warpedMat != null && warpedMat.cols() > 0 && warpedMat.rows() > 0) {
                Bitmap outputBitmap = Bitmap.createBitmap(
                        warpedMat.cols(),
                        warpedMat.rows(),
                        Bitmap.Config.ARGB_8888
                );
                Utils.matToBitmap(warpedMat, outputBitmap);
                Log.i(TAG, "Smart crop successful: " + outputBitmap.getWidth() + "x" + outputBitmap.getHeight());
                return outputBitmap;
            }

        } catch (Throwable t) {
            Log.e(TAG, "OpenCV smart crop failed. Falling back to original image.", t);
        } finally {
            // Strict native Mat memory cleanup
            if (srcMat != null) srcMat.release();
            if (resizedMat != null) resizedMat.release();
            if (grayMat != null) grayMat.release();
            if (blurMat != null) blurMat.release();
            if (edgeMat != null) edgeMat.release();
            if (dilatedMat != null) dilatedMat.release();
            if (warpedMat != null) warpedMat.release();
        }

        return sourceBitmap;
    }

    /**
     * Orders 4 points clockwise: [0] Top-Left, [1] Top-Right, [2] Bottom-Right, [3] Bottom-Left.
     */
    @NonNull
    private static Point[] orderPoints(@NonNull Point[] pts) {
        Point[] ordered = new Point[4];

        // Sum (x + y): Top-Left has smallest sum, Bottom-Right has largest sum
        // Diff (y - x): Top-Right has smallest diff, Bottom-Left has largest diff
        double minSum = Double.MAX_VALUE;
        double maxSum = -Double.MAX_VALUE;
        double minDiff = Double.MAX_VALUE;
        double maxDiff = -Double.MAX_VALUE;

        for (Point pt : pts) {
            double sum = pt.x + pt.y;
            double diff = pt.y - pt.x;

            if (sum < minSum) {
                minSum = sum;
                ordered[0] = pt; // Top-Left
            }
            if (sum > maxSum) {
                maxSum = sum;
                ordered[2] = pt; // Bottom-Right
            }
            if (diff < minDiff) {
                minDiff = diff;
                ordered[1] = pt; // Top-Right
            }
            if (diff > maxDiff) {
                maxDiff = diff;
                ordered[3] = pt; // Bottom-Left
            }
        }

        return ordered;
    }

    /**
     * Warps the source Mat based on the 4 corner points into a flat rectangle.
     */
    @Nullable
    private static Mat applyPerspectiveTransform(@NonNull Mat src, @NonNull Point[] corners) {
        Point[] ordered = orderPoints(corners);
        Point tl = ordered[0];
        Point tr = ordered[1];
        Point br = ordered[2];
        Point bl = ordered[3];

        // Compute destination width
        double widthA = Math.hypot(br.x - bl.x, br.y - bl.y);
        double widthB = Math.hypot(tr.x - tl.x, tr.y - tl.y);
        int maxWidth = (int) Math.max(widthA, widthB);

        // Compute destination height
        double heightA = Math.hypot(tr.x - br.x, tr.y - br.y);
        double heightB = Math.hypot(tl.x - bl.x, tl.y - bl.y);
        int maxHeight = (int) Math.max(heightA, heightB);

        if (maxWidth <= 0 || maxHeight <= 0) {
            return null;
        }

        MatOfPoint2f srcPoints = new MatOfPoint2f(tl, tr, br, bl);
        MatOfPoint2f dstPoints = new MatOfPoint2f(
                new Point(0, 0),
                new Point(maxWidth - 1, 0),
                new Point(maxWidth - 1, maxHeight - 1),
                new Point(0, maxHeight - 1)
        );

        Mat transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
        Mat destination = new Mat();
        Imgproc.warpPerspective(src, destination, transformMatrix, new Size(maxWidth, maxHeight));

        srcPoints.release();
        dstPoints.release();
        transformMatrix.release();

        return destination;
    }
}

