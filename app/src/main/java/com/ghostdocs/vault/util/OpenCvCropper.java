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
import java.util.Collections;
import java.util.List;

/**
 * OpenCvCropper — Computer Vision Document Cropping & De-skewing Engine
 *
 * <p>Detects physical document and ID card boundaries (including rounded-corner cards
 * on textured or low-contrast surfaces) using multi-strategy edge & Otsu contour
 * analysis, then applies a 4-point perspective transform to flatten and crop the capture.
 */
public final class OpenCvCropper {

    private static final String TAG = "GhostDocs.OpenCv";
    private static final double TARGET_PROCESSING_MAX_DIM = 900.0;
    private static final double MIN_DOCUMENT_AREA_RATIO = 0.12; // Supports wallet ID cards framed on a desk
    private static final double MAX_DOCUMENT_AREA_RATIO = 0.96; // Ignore full-frame border noise
    private static final double MIN_DIMENSION_RATIO = 0.22;     // Require meaningful span on both axes
    private static final double MIN_RECTANGULARITY = 0.78;      // contourArea / minAreaRectArea threshold
    private static final double[] APPROX_EPSILONS = { 0.02, 0.035, 0.05 };

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
     * <p>If no distinct rectangular document contour is found, it safely returns
     * {@code sourceBitmap} unchanged.
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
            if (warpedMat != null && warpedMat.cols() > 0 && warpedMat.rows() > 0) {
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
     * Tries multiple complementary segmentation strategies:
     * 1. Smoothed Canny edges + morphological closing (sharp desk contrast & documents)
     * 2. Light-foreground Otsu mask + closing (white/light ID cards on darker tables)
     * 3. Dark-foreground inverted Otsu mask + closing (dark cards on light surfaces)
     */
    @Nullable
    private static Point[] detectDocumentQuad(@NonNull Mat grayMat, int width, int height) {
        Point[] quad = findQuadViaCanny(grayMat, width, height);
        if (quad != null) return quad;

        quad = findQuadViaOtsu(grayMat, width, height, false);
        if (quad != null) return quad;

        return findQuadViaOtsu(grayMat, width, height, true);
    }

    @Nullable
    private static Point[] findQuadViaCanny(@NonNull Mat grayMat, int width, int height) {
        Mat blurMat = null;
        Mat edgeMat = null;
        Mat closedMat = null;
        Mat closeKernel = null;
        Mat dilateKernel = null;

        try {
            blurMat = new Mat();
            Imgproc.GaussianBlur(grayMat, blurMat, new Size(5, 5), 0);

            edgeMat = new Mat();
            Imgproc.Canny(blurMat, edgeMat, 30, 100);

            closedMat = new Mat();
            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7));
            Imgproc.morphologyEx(edgeMat, closedMat, Imgproc.MORPH_CLOSE, closeKernel);

            dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(closedMat, closedMat, dilateKernel);

            return extractBestQuad(closedMat, width, height, Imgproc.RETR_LIST);
        } finally {
            releaseQuietly(blurMat);
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
        Mat openKernel = null;
        Mat closeKernel = null;

        try {
            blurMat = new Mat();
            Imgproc.GaussianBlur(grayMat, blurMat, new Size(7, 7), 0);

            binMat = new Mat();
            int type = (invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY) | Imgproc.THRESH_OTSU;
            Imgproc.threshold(blurMat, binMat, 0, 255, type);

            openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.morphologyEx(binMat, binMat, Imgproc.MORPH_OPEN, openKernel);

            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(11, 11));
            Imgproc.morphologyEx(binMat, binMat, Imgproc.MORPH_CLOSE, closeKernel);

            return extractBestQuad(binMat, width, height, Imgproc.RETR_EXTERNAL);
        } finally {
            releaseQuietly(blurMat);
            releaseQuietly(binMat);
            releaseQuietly(openKernel);
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
            int limit = Math.min(contours.size(), 12);

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
                    if (isValidQuadSpan(pts, width, height)) {
                        return clampPoints(pts, width, height);
                    }
                }
            }

            // Rounded-corner plastic ID card / library card fallback using rotated bounding box
            if (approx2f.total() >= 4 && approx2f.total() <= 10 && rectangularity >= 0.82) {
                Point[] rectPts = new Point[4];
                minRect.points(rectPts);
                if (isValidQuadSpan(rectPts, width, height)) {
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
     * Orders 4 points clockwise: [0] Top-Left, [1] Top-Right, [2] Bottom-Right, [3] Bottom-Left.
     */
    @NonNull
    private static Point[] orderPoints(@NonNull Point[] pts) {
        Point[] ordered = new Point[4];

        double minSum = Double.MAX_VALUE;
        double maxSum = -Double.MAX_VALUE;
        double minDiff = Double.MAX_VALUE;
        double maxDiff = -Double.MAX_VALUE;

        for (Point pt : pts) {
            double sum = pt.x + pt.y;
            double diff = pt.y - pt.x;

            if (sum < minSum) {
                minSum = sum;
                ordered[0] = pt;
            }
            if (sum > maxSum) {
                maxSum = sum;
                ordered[2] = pt;
            }
            if (diff < minDiff) {
                minDiff = diff;
                ordered[1] = pt;
            }
            if (diff > maxDiff) {
                maxDiff = diff;
                ordered[3] = pt;
            }
        }

        // Guard against collinear/degenerate tie-breaking returning null slot
        for (int i = 0; i < 4; i++) {
            if (ordered[i] == null) {
                ordered[i] = pts[i % pts.length];
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
