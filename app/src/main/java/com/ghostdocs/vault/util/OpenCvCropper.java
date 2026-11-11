package com.ghostdocs.vault.util;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OpenCvCropper — Computer Vision Document Cropping & De-skewing Engine
 *
 * <p>Uses multi-strategy segmentation specifically tuned for physical documents and ID cards:
 * grayscale morphological closing to erase interior printed text/photos, multi-threshold Canny,
 * global Otsu (light &amp; dark foregrounds), and Adaptive Gaussian thresholding for uneven lighting,
 * followed by convex-hull quad approximation / rotated-rectangle fallback and 4-point perspective warp.
 */
public final class OpenCvCropper {

    private static final String TAG = "GhostDocs.OpenCv";
    private static final double TARGET_PROCESSING_MAX_DIM = 640.0;
    private static final double MIN_DOCUMENT_AREA_RATIO = 0.08;
    private static final double MAX_DOCUMENT_AREA_RATIO = 0.95;
    private static final double MIN_DIMENSION_RATIO = 0.20;
    private static final double MIN_RECTANGULARITY = 0.68;      // Handles angled perspective trapezoids
    private static final double MIN_FALLBACK_RECTANGULARITY = 0.75;
    private static final double[] APPROX_EPSILONS = { 0.015, 0.025, 0.035, 0.05, 0.07 };

    private static volatile boolean openCvReady = false;

    private OpenCvCropper() {
        throw new UnsupportedOperationException("OpenCvCropper is a utility class.");
    }

    private static boolean ensureOpenCvReady() {
        if (openCvReady) return true;
        try {
            openCvReady = OpenCVLoader.initLocal() || OpenCVLoader.initDebug();
            if (!openCvReady) {
                Log.e(TAG, "OpenCV native library failed to initialize.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV initialization error", t);
        }
        return openCvReady;
    }

    /**
     * Detects document boundaries in the provided Bitmap, applies a 4-point perspective
     * transform to de-skew and flatten it, and returns the cropped Bitmap.
     *
     * <p>If no distinct rectangular document contour is found, returns {@code sourceBitmap} unchanged.
     */
    @NonNull
    public static Bitmap cropAndFlatten(@NonNull Bitmap sourceBitmap) {
        if (!ensureOpenCvReady()) {
            return sourceBitmap;
        }

        Bitmap workingBitmap = sourceBitmap;
        boolean copiedForConfig = false;
        if (sourceBitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            workingBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (workingBitmap == null) {
                return sourceBitmap;
            }
            copiedForConfig = true;
        }

        Mat srcMat = null;
        Mat resizedMat = null;
        Mat grayMat = null;
        Mat warpedMat = null;

        try {
            srcMat = new Mat();
            Utils.bitmapToMat(workingBitmap, srcMat);

            int origWidth = srcMat.cols();
            int origHeight = srcMat.rows();
            if (origWidth <= 0 || origHeight <= 0) {
                return sourceBitmap;
            }

            double scale = Math.max(origWidth, origHeight) / TARGET_PROCESSING_MAX_DIM;
            if (scale < 1.0) scale = 1.0;

            int resizedWidth = Math.max(1, (int) Math.round(origWidth / scale));
            int resizedHeight = Math.max(1, (int) Math.round(origHeight / scale));

            resizedMat = new Mat();
            Imgproc.resize(srcMat, resizedMat, new Size(resizedWidth, resizedHeight));

            grayMat = new Mat();
            Imgproc.cvtColor(resizedMat, grayMat, Imgproc.COLOR_RGBA2GRAY);

            Point[] documentCorners = detectDocumentQuad(grayMat, resizedWidth, resizedHeight);
            if (documentCorners == null) {
                Log.d(TAG, "No distinct document boundary found (" + origWidth + "x" + origHeight + "). Keeping original.");
                return sourceBitmap;
            }

            Point[] scaledCorners = new Point[4];
            for (int i = 0; i < 4; i++) {
                double sx = Math.max(0, Math.min(origWidth - 1.0, documentCorners[i].x * scale));
                double sy = Math.max(0, Math.min(origHeight - 1.0, documentCorners[i].y * scale));
                scaledCorners[i] = new Point(sx, sy);
            }

            warpedMat = applyPerspectiveTransform(srcMat, scaledCorners);
            if (warpedMat != null && warpedMat.cols() > 16 && warpedMat.rows() > 16) {
                Bitmap outputBitmap = Bitmap.createBitmap(
                        warpedMat.cols(),
                        warpedMat.rows(),
                        Bitmap.Config.ARGB_8888
                );
                Utils.matToBitmap(warpedMat, outputBitmap);
                Log.i(TAG, "Smart crop successful: " + origWidth + "x" + origHeight
                        + " -> " + outputBitmap.getWidth() + "x" + outputBitmap.getHeight());
                return outputBitmap;
            }
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV smart crop failed. Falling back to original image.", t);
        } finally {
            releaseQuietly(srcMat);
            releaseQuietly(resizedMat);
            releaseQuietly(grayMat);
            releaseQuietly(warpedMat);
            if (copiedForConfig && !workingBitmap.isRecycled()) {
                workingBitmap.recycle();
            }
        }

        return sourceBitmap;
    }

    /**
     * Multi-strategy segmentation pipeline:
     * 1. Pre-closed + median-blurred Canny (erases text/logos inside white ID cards/pages)
     * 2. Standard Gaussian + Canny (sharp desk/document borders)
     * 3. Pre-closed Otsu binary segmentation (light cards on dark surfaces & vice versa)
     * 4. Adaptive Gaussian threshold (handles shadows, glare, or patterned desks)
     */
    @Nullable
    private static Point[] detectDocumentQuad(@NonNull Mat grayMat, int width, int height) {
        Mat morphKernel = null;
        Mat closedGray = null;
        Mat openKernel = null;
        Mat openedGray = null;

        try {
            morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(13, 13));
            closedGray = new Mat();
            Imgproc.morphologyEx(grayMat, closedGray, Imgproc.MORPH_CLOSE, morphKernel);

            // 1. Text-suppressed pre-closed Canny (strong for light cards/documents)
            Point[] quad = findQuadViaCanny(closedGray, width, height, true, 25, 85);
            if (quad != null) return quad;

            quad = findQuadViaCanny(closedGray, width, height, true, 50, 150);
            if (quad != null) return quad;

            // 2. Dark-card-suppressed pre-opened Canny (in case card is darker than bright surface)
            openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(13, 13));
            openedGray = new Mat();
            Imgproc.morphologyEx(grayMat, openedGray, Imgproc.MORPH_OPEN, openKernel);

            quad = findQuadViaCanny(openedGray, width, height, true, 30, 100);
            if (quad != null) return quad;

            // 3. Raw gray Canny
            quad = findQuadViaCanny(grayMat, width, height, false, 30, 100);
            if (quad != null) return quad;

            // 4. Otsu on text-suppressed foreground (light-on-dark and dark-on-light)
            quad = findQuadViaOtsu(closedGray, width, height, false);
            if (quad != null) return quad;

            quad = findQuadViaOtsu(openedGray, width, height, true);
            if (quad != null) return quad;

            // 5. Adaptive Gaussian Thresholding for shadows & textured desks
            quad = findQuadViaAdaptive(closedGray, width, height, false);
            if (quad != null) return quad;

            return findQuadViaAdaptive(openedGray, width, height, true);
        } finally {
            releaseQuietly(morphKernel);
            releaseQuietly(closedGray);
            releaseQuietly(openKernel);
            releaseQuietly(openedGray);
        }
    }

    @Nullable
    private static Point[] findQuadViaCanny(@NonNull Mat grayMat,
                                            int width,
                                            int height,
                                            boolean useMedian,
                                            double lowThresh,
                                            double highThresh) {
        Mat smoothMat = null;
        Mat edgeMat = null;
        Mat closedMat = null;
        Mat closeKernel = null;
        Mat dilateKernel = null;

        try {
            smoothMat = new Mat();
            if (useMedian) {
                Imgproc.medianBlur(grayMat, smoothMat, 5);
            } else {
                Imgproc.GaussianBlur(grayMat, smoothMat, new Size(5, 5), 0);
            }

            edgeMat = new Mat();
            Imgproc.Canny(smoothMat, edgeMat, lowThresh, highThresh);

            closedMat = new Mat();
            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(edgeMat, closedMat, Imgproc.MORPH_CLOSE, closeKernel);

            dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(closedMat, closedMat, dilateKernel);

            return extractBestQuad(closedMat, width, height, Imgproc.RETR_LIST);
        } finally {
            releaseQuietly(smoothMat);
            releaseQuietly(edgeMat);
            releaseQuietly(closedMat);
            releaseQuietly(closeKernel);
            releaseQuietly(dilateKernel);
        }
    }

    @Nullable
    private static Point[] findQuadViaOtsu(@NonNull Mat grayMat,
                                           int width,
                                           int height,
                                           boolean invert) {
        Mat blurMat = null;
        Mat binMat = null;
        Mat closeKernel = null;
        Mat openKernel = null;

        try {
            blurMat = new Mat();
            Imgproc.GaussianBlur(grayMat, blurMat, new Size(7, 7), 0);

            binMat = new Mat();
            int type = (invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY) | Imgproc.THRESH_OTSU;
            Imgproc.threshold(blurMat, binMat, 0, 255, type);

            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(11, 11));
            Imgproc.morphologyEx(binMat, binMat, Imgproc.MORPH_CLOSE, closeKernel);

            openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.morphologyEx(binMat, binMat, Imgproc.MORPH_OPEN, openKernel);

            return extractBestQuad(binMat, width, height, Imgproc.RETR_EXTERNAL);
        } finally {
            releaseQuietly(blurMat);
            releaseQuietly(binMat);
            releaseQuietly(closeKernel);
            releaseQuietly(openKernel);
        }
    }

    @Nullable
    private static Point[] findQuadViaAdaptive(@NonNull Mat grayMat,
                                               int width,
                                               int height,
                                               boolean invert) {
        Mat blurMat = null;
        Mat binMat = null;
        Mat closeKernel = null;

        try {
            blurMat = new Mat();
            Imgproc.GaussianBlur(grayMat, blurMat, new Size(5, 5), 0);

            binMat = new Mat();
            int threshType = invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY;
            Imgproc.adaptiveThreshold(blurMat, binMat, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, threshType, 51, invert ? 7 : -7);

            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(binMat, binMat, Imgproc.MORPH_CLOSE, closeKernel);

            return extractBestQuad(binMat, width, height, Imgproc.RETR_EXTERNAL);
        } finally {
            releaseQuietly(blurMat);
            releaseQuietly(binMat);
            releaseQuietly(closeKernel);
        }
    }

    @Nullable
    private static Point[] extractBestQuad(@NonNull Mat binaryMat,
                                           int width,
                                           int height,
                                           int retrievalMode) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        try {
            Imgproc.findContours(binaryMat, contours, hierarchy, retrievalMode, Imgproc.CHAIN_APPROX_SIMPLE);
            Collections.sort(contours, (c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));

            double totalArea = (double) width * height;
            int limit = Math.min(contours.size(), 15);

            for (int i = 0; i < limit; i++) {
                Point[] candidate = evaluateContourAsQuad(contours.get(i), totalArea, width, height);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        } finally {
            hierarchy.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    @Nullable
    private static Point[] evaluateContourAsQuad(@NonNull MatOfPoint contour,
                                                 double totalArea,
                                                 int width,
                                                 int height) {
        MatOfInt hullIndices = new MatOfInt();
        MatOfPoint hullPoints = null;
        MatOfPoint2f hull2f = null;
        MatOfPoint2f approx2f = null;

        try {
            Imgproc.convexHull(contour, hullIndices);
            int[] idxArr = hullIndices.toArray();
            Point[] rawPts = contour.toArray();
            if (rawPts.length < 4 || idxArr.length < 4) {
                return null;
            }
            Point[] hullPts = new Point[idxArr.length];
            for (int i = 0; i < idxArr.length; i++) {
                hullPts[i] = rawPts[idxArr[i]];
            }

            hullPoints = new MatOfPoint(hullPts);
            double hullArea = Imgproc.contourArea(hullPoints);
            double areaRatio = hullArea / totalArea;
            if (areaRatio < MIN_DOCUMENT_AREA_RATIO || areaRatio > MAX_DOCUMENT_AREA_RATIO) {
                return null;
            }

            hull2f = new MatOfPoint2f(hullPts);
            RotatedRect minRect = Imgproc.minAreaRect(hull2f);
            double rectArea = minRect.size.width * minRect.size.height;
            if (rectArea <= 0) {
                return null;
            }

            double rectangularity = hullArea / rectArea;
            if (rectangularity < MIN_RECTANGULARITY) {
                return null;
            }

            double perimeter = Imgproc.arcLength(hull2f, true);
            approx2f = new MatOfPoint2f();

            for (double eps : APPROX_EPSILONS) {
                Imgproc.approxPolyDP(hull2f, approx2f, eps * perimeter, true);
                if (approx2f.total() == 4) {
                    Point[] pts = approx2f.toArray();
                    if (isValidQuadSpan(pts, width, height) && !isFrameBorderQuad(pts, width, height, areaRatio)) {
                        return clampPoints(pts, width, height);
                    }
                }
            }

            // Rounded-corner plastic ID card / library card fallback using rotated bounding box
            if (approx2f.total() >= 4 && approx2f.total() <= 14 && rectangularity >= MIN_FALLBACK_RECTANGULARITY) {
                Point[] rectPts = new Point[4];
                minRect.points(rectPts);
                if (isValidQuadSpan(rectPts, width, height) && !isFrameBorderQuad(rectPts, width, height, areaRatio)) {
                    return clampPoints(rectPts, width, height);
                }
            }

            return null;
        } finally {
            hullIndices.release();
            releaseQuietly(hullPoints);
            releaseQuietly(hull2f);
            releaseQuietly(approx2f);
        }
    }

    private static boolean isValidQuadSpan(@NonNull Point[] pts, int width, int height) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point p : pts) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        double clampedSpanX = Math.min(width - 1.0, maxX) - Math.max(0.0, minX);
        double clampedSpanY = Math.min(height - 1.0, maxY) - Math.max(0.0, minY);
        return (clampedSpanX / width) >= MIN_DIMENSION_RATIO
                && (clampedSpanY / height) >= MIN_DIMENSION_RATIO;
    }

    /**
     * Ignores full-frame border contours that simply trace the outer edge of the photo.
     */
    private static boolean isFrameBorderQuad(@NonNull Point[] pts, int width, int height, double areaRatio) {
        if (areaRatio < 0.88) return false;
        double marginX = width * 0.025;
        double marginY = height * 0.025;
        int nearEdgeCount = 0;
        for (Point p : pts) {
            boolean nearX = p.x <= marginX || p.x >= (width - 1.0 - marginX);
            boolean nearY = p.y <= marginY || p.y >= (height - 1.0 - marginY);
            if (nearX && nearY) {
                nearEdgeCount++;
            }
        }
        return nearEdgeCount >= 4;
    }

    @NonNull
    private static Point[] clampPoints(@NonNull Point[] pts, int width, int height) {
        Point[] clamped = new Point[pts.length];
        for (int i = 0; i < pts.length; i++) {
            clamped[i] = new Point(
                    Math.max(0.0, Math.min(width - 1.0, pts[i].x)),
                    Math.max(0.0, Math.min(height - 1.0, pts[i].y))
            );
        }
        return clamped;
    }

    private static void releaseQuietly(@Nullable Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    /**
     * Orders 4 distinct corners reliably clockwise starting from Top-Left:
     * [0] Top-Left, [1] Top-Right, [2] Bottom-Right, [3] Bottom-Left.
     */
    @NonNull
    private static Point[] orderPoints(@NonNull Point[] pts) {
        double cx = 0;
        double cy = 0;
        for (Point pt : pts) {
            cx += pt.x;
            cy += pt.y;
        }
        final double centerX = cx / pts.length;
        final double centerY = cy / pts.length;

        Point[] sorted = Arrays.copyOf(pts, pts.length);
        Arrays.sort(sorted, (a, b) -> {
            double angA = Math.atan2(a.y - centerY, a.x - centerX);
            double angB = Math.atan2(b.y - centerY, b.x - centerX);
            return Double.compare(angA, angB);
        });

        // Rotate clockwise cycle so Top-Left (smallest x + y) is index 0
        int tlIdx = 0;
        double minSum = Double.MAX_VALUE;
        for (int i = 0; i < sorted.length; i++) {
            double sum = sorted[i].x + sorted[i].y;
            if (sum < minSum) {
                minSum = sum;
                tlIdx = i;
            }
        }

        Point[] ordered = new Point[4];
        for (int i = 0; i < 4; i++) {
            ordered[i] = sorted[(tlIdx + i) % 4];
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

        double widthA = Math.hypot(br.x - bl.x, br.y - bl.y);
        double widthB = Math.hypot(tr.x - tl.x, tr.y - tl.y);
        int maxWidth = (int) Math.max(widthA, widthB);

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
