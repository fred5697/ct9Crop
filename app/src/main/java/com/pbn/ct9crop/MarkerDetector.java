// java
package com.pbn.ct9crop;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public class MarkerDetector {
    private static final String TAG = "MarkerDetector";
    private static boolean openCvLoaded = false;

    static {
        try {
            System.loadLibrary("opencv_java4");
            openCvLoaded = true;
            Log.i(TAG, "Loaded libopencv_java4 via System.loadLibrary");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "System.loadLibrary(opencv_java4) failed, try OpenCVLoader.initDebug()", e);
            try {
                if (OpenCVLoader.initDebug()) {
                    openCvLoaded = true;
                    Log.i(TAG, "OpenCV initialized via OpenCVLoader.initDebug()");
                } else {
                    Log.e(TAG, "OpenCVLoader.initDebug() returned false");
                }
            } catch (Throwable t) {
                Log.e(TAG, "OpenCVLoader.initDebug() threw", t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error loading OpenCV native library", t);
        }
    }

    public static class DetectionResult {
        public boolean detected;
        public PointF[] corners;         // 期望 4 个角点（顺序 TL, TR, BR, BL）
        public int markerCount;         // 期望 9
        public PointF[] markerPositions; // 9 个中心点（原图坐标）

        public DetectionResult() {
            this.detected = false;
            this.corners = null;
            this.markerCount = 0;
            this.markerPositions = null;
        }
    }

    public MarkerDetector() { }

    // 找最长连续大于 thr 的段（包含两端）
    private static int[] findLongestSegment(double[] a, double thr) {
        int bestL = -1, bestR = -2;
        int curL = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] > thr) {
                if (curL == -1) curL = i;
            } else {
                if (curL != -1) {
                    int curR = i - 1;
                    if (curR - curL > bestR - bestL) { bestL = curL; bestR = curR; }
                    curL = -1;
                }
            }
        }
        if (curL != -1) {
            int curR = a.length - 1;
            if (curR - curL > bestR - bestL) { bestL = curL; bestR = curR; }
        }
        if (bestL == -1) return new int[]{0, -1};
        return new int[]{bestL, bestR};
    }

    /**
     * 基于饱和度的 ROI 查找（返回 roi Rect，相对于原图坐标）
     */
    private static Rect findColorGridRoi(Mat src) {
        if (src == null || src.empty()) return new Rect(0,0,0,0);

        Mat hsv = new Mat();
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV);

        List<Mat> ch = new ArrayList<>();
        Core.split(hsv, ch);
        Mat sat = ch.get(1); // 饱和度通道

        int rows = sat.rows();
        int cols = sat.cols();

        double[] colMeans = new double[cols];
        for (int c = 0; c < cols; c++) {
            Mat col = sat.col(c);
            Scalar m = Core.mean(col);
            colMeans[c] = m.val[0];
            col.release();
        }

        double[] rowMeans = new double[rows];
        for (int r = 0; r < rows; r++) {
            Mat row = sat.row(r);
            Scalar m = Core.mean(row);
            rowMeans[r] = m.val[0];
            row.release();
        }

        double globalMean = Core.mean(sat).val[0];
        double thr = Math.max(12.0, globalMean * 0.55);

        int[] colSeg = findLongestSegment(colMeans, thr);
        int[] rowSeg = findLongestSegment(rowMeans, thr);

        int left = colSeg[0], right = colSeg[1], top = rowSeg[0], bottom = rowSeg[1];
        if (left >= right || top >= bottom) {
            // fallback: 中心裁剪 60% 区域
            int w = cols * 6 / 10;
            int h = rows * 6 / 10;
            left = Math.max(0, (cols - w) / 2);
            top = Math.max(0, (rows - h) / 2);
            right = left + w - 1;
            bottom = top + h - 1;
        }

        // 扩展少量像素，确保包含边缘色块
        int padW = Math.max(2, (int)((right - left + 1) * 0.06));
        int padH = Math.max(2, (int)((bottom - top + 1) * 0.06));
        left = Math.max(0, left - padW);
        top = Math.max(0, top - padH);
        right = Math.min(cols - 1, right + padW);
        bottom = Math.min(rows - 1, bottom + padH);

        hsv.release();
        sat.release();
        for (Mat m : ch) if (m != null && !m.empty()) m.release();

        int w = Math.max(0, right - left + 1);
        int h = Math.max(0, bottom - top + 1);
        if (w <= 0 || h <= 0) return new Rect(0,0,0,0);
        return new Rect(left, top, w, h);
    }

    /**
     * 主检测函数：找到包含 3x3 色块的 ROI，然后按网格采样 9 个中心点并返回四角。
     */
    public DetectionResult detectMarkers(Bitmap bitmap) {
        DetectionResult res = new DetectionResult();
        if (bitmap == null) return res;
        if (!openCvLoaded) {
            Log.e(TAG, "OpenCV native library not loaded; skipping detection");
            return res;
        }

        Mat src = new Mat();
        try {
            Utils.bitmapToMat(bitmap, src); // BGR mat
            Rect roiRect = findColorGridRoi(src);
            if (roiRect == null || roiRect.width <= 8 || roiRect.height <= 8) {
                // ROI 太小，认为未检测到
                return res;
            }

            Mat cropped = new Mat(src, roiRect).clone();
            // 基本验证：检查 cropped 的平均亮度或饱和度，判断是否为色块区域
            Mat hsv = new Mat();
            Imgproc.cvtColor(cropped, hsv, Imgproc.COLOR_BGR2HSV);
            List<Mat> ch = new ArrayList<>();
            Core.split(hsv, ch);
            double meanSat = Core.mean(ch.get(1)).val[0];
            double meanVal = Core.mean(ch.get(2)).val[0];

            // 如果饱和度/亮度太低，可能不是色块
            boolean likelyGrid = (meanSat > 10.0) || (meanVal > 30.0);

            // 计算 3x3 网格中心点（在原图坐标系中）
            PointF[] centers = new PointF[9];
            int idx = 0;
            int cw = roiRect.width;
            int chh = roiRect.height;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    float cx = (c + 0.5f) * cw / 3f;
                    float cy = (r + 0.5f) * chh / 3f;
                    // 转换为原图坐标
                    centers[idx++] = new PointF(roiRect.x + cx, roiRect.y + cy);
                }
            }

            // 填充 DetectionResult
            res.markerCount = 9;
            res.markerPositions = centers;
            res.corners = new PointF[] {
                    new PointF(roiRect.x, roiRect.y), // TL
                    new PointF(roiRect.x + roiRect.width - 1, roiRect.y), // TR
                    new PointF(roiRect.x + roiRect.width - 1, roiRect.y + roiRect.height - 1), // BR
                    new PointF(roiRect.x, roiRect.y + roiRect.height - 1) // BL
            };

            res.detected = likelyGrid; // 依据均值简单判断
            // 清理
            hsv.release();
            for (Mat m : ch) if (m != null && !m.empty()) m.release();
            if (cropped != null && !cropped.empty()) cropped.release();

        } catch (Exception e) {
            Log.e(TAG, "Exception in detectMarkers", e);
            // 返回默认未检测
            res.detected = false;
            res.markerCount = 0;
            res.corners = null;
            res.markerPositions = null;
        } finally {
            if (src != null && !src.empty()) src.release();
        }
        return res;
    }
}
