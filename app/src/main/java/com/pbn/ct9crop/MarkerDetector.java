
package com.pbn.ct9crop;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MarkerDetector {
    private static final String TAG = "MarkerDetector";

    private static final int MIN_MARKER_AREA = 50;
    private static final int MAX_MARKER_AREA = 4000;
    private static final int MAX_MARKERS_RETURNED = 12;

    private int[] grayLinear = null;          // width * height
    private int[] integral = null;            // (width+1)*(height+1)
    private int imgWidth = 0, imgHeight = 0;

    public static class DetectionResult {
        public boolean detected;
        public int markerCount;
        public PointF[] corners;
        public PointF[] markerPositions;

        public DetectionResult() {
            detected = false;
            markerCount = 0;
        }
    }

    public DetectionResult detectMarkers(Bitmap bitmap) {
        // 缩放为较小宽度以加速处理
        int targetWidth = 480;
        int targetHeight = (int) (bitmap.getHeight() * (targetWidth / (float) bitmap.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);

        boolean[][] binary = createBinaryImage(scaled);
        List<Marker> markers = findMarkers(binary, targetWidth, targetHeight);

        Log.d(TAG, "Found " + markers.size() + " potential markers");

        DetectionResult result = new DetectionResult();
        result.markerCount = markers.size();

        if (markers.size() >= 4) {
            // 按接近平方排序并取前 4
            Collections.sort(markers, (a, b) -> {
                float wa = (a.maxX - a.minX + 1);
                float ha = (a.maxY - a.minY + 1);
                float wb = (b.maxX - b.minX + 1);
                float hb = (b.maxY - b.minY + 1);
                float sqA = Math.abs(wa - ha) / Math.max(wa, ha);
                float sqB = Math.abs(wb - hb) / Math.max(wb, hb);
                int cmp = Float.compare(sqA, sqB);
                if (cmp != 0) return cmp;
                return Integer.compare(b.size, a.size);
            });
            if (markers.size() > 4) markers = new ArrayList<>(markers.subList(0, 4));
            result.markerCount = markers.size();

            float scaleX = bitmap.getWidth() / (float) targetWidth;
            float scaleY = bitmap.getHeight() / (float) targetHeight;

            result.markerPositions = new PointF[markers.size()];
            for (int i = 0; i < markers.size(); i++) {
                result.markerPositions[i] = new PointF(
                        markers.get(i).centerX * scaleX,
                        markers.get(i).centerY * scaleY
                );
            }

            PointF[] corners = findGridCorners(markers);
            if (corners != null) {
                result.detected = true;
                result.corners = new PointF[4];
                for (int i = 0; i < 4; i++) {
                    result.corners[i] = new PointF(
                            corners[i].x * scaleX,
                            corners[i].y * scaleY
                    );
                }
            }
        }

        return result;
    }

    // 使用 Otsu 自动阈值并同时构建灰度线性数组与 integral image
    private boolean[][] createBinaryImage(Bitmap bmp) {
        imgWidth = bmp.getWidth();
        imgHeight = bmp.getHeight();
        grayLinear = new int[imgWidth * imgHeight];
        int[] hist = new int[256];

        for (int y = 0; y < imgHeight; y++) {
            for (int x = 0; x < imgWidth; x++) {
                int p = bmp.getPixel(x, y);
                int b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3;
                grayLinear[y * imgWidth + x] = b;
                hist[b & 0xff]++;
            }
        }

        int otsu = computeOtsuThreshold(hist, imgWidth * imgHeight);
        // 给 Otsu 一个小偏移来避免非常亮/暗图像误判
        int threshold = Math.max(20, Math.min(otsu - 8, 200));

        // 构建 integral image (尺寸 (w+1)*(h+1) 便于区域和计算)
        integral = new int[(imgWidth + 1) * (imgHeight + 1)];
        for (int y = 1; y <= imgHeight; y++) {
            int rowSum = 0;
            int baseSrc = (y - 1) * imgWidth;
            int baseInt = y * (imgWidth + 1);
            int baseIntPrev = (y - 1) * (imgWidth + 1);
            for (int x = 1; x <= imgWidth; x++) {
                rowSum += grayLinear[baseSrc + (x - 1)];
                integral[baseInt + x] = integral[baseIntPrev + x] + rowSum;
            }
        }

        boolean[][] binary = new boolean[imgHeight][imgWidth];
        for (int y = 0; y < imgHeight; y++) {
            int rowBase = y * imgWidth;
            for (int x = 0; x < imgWidth; x++) {
                binary[y][x] = grayLinear[rowBase + x] < threshold;
            }
        }
        return binary;
    }

    private int computeOtsuThreshold(int[] hist, int total) {
        double sum = 0;
        for (int t = 0; t < 256; t++) sum += t * hist[t];
        double sumB = 0;
        int wB = 0;
        double varMax = 0;
        int threshold = 0;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += (double) (t * hist[t]);
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double varBetween = (double) wB * (double) wF * (mB - mF) * (mB - mF);
            if (varBetween > varMax) {
                varMax = varBetween;
                threshold = t;
            }
        }
        return threshold;
    }

    private List<Marker> findMarkers(boolean[][] binary, int width, int height) {
        int area = width * height;
        byte[] visited = new byte[area]; // 0/1
        List<Marker> markers = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (binary[y][x] && visited[idx] == 0) {
                    Marker m = floodFill(binary, visited, x, y, width, height);
                    if (m == null) continue;
                    int a = m.size;
                    int w = m.maxX - m.minX + 1;
                    int h = m.maxY - m.minY + 1;
                    float aspect = h > 0 ? (float) w / h : 0f;
                    if (a >= MIN_MARKER_AREA && a <= MAX_MARKER_AREA
                            && aspect > 0.5f && aspect < 2.0f
                            && hasWhiteBorder(m)) {
                        markers.add(m);
                    }
                }
            }
        }

        // 按面积降序并限制返回数量
        Collections.sort(markers, new Comparator<Marker>() {
            @Override
            public int compare(Marker a, Marker b) {
                return Integer.compare(b.size, a.size);
            }
        });
        if (markers.size() > MAX_MARKERS_RETURNED) {
            return new ArrayList<>(markers.subList(0, MAX_MARKERS_RETURNED));
        }
        return markers;
    }

    // 高效栈实现 flood-fill，面积超上限时早期退出并返回 null（或标记过大）
    private Marker floodFill(boolean[][] binary, byte[] visited, int startX, int startY,
                             int width, int height) {
        int maxSize = width * height;
        int[] stack = new int[maxSize];
        int sp = 0;
        int startIdx = startY * width + startX;
        stack[sp++] = startIdx;

        Marker m = new Marker();
        while (sp > 0) {
            int idx = stack[--sp];
            if (visited[idx] != 0) continue;
            int y = idx / width;
            int x = idx - y * width;
            if (!binary[y][x]) continue;

            visited[idx] = 1;
            m.addPoint(x, y);
            if (m.size > MAX_MARKER_AREA) {
                // 早退：标记过大，返回 null 以便上层忽略
                return null;
            }

            // 4-neighbors 推入（检查边界）
            if (x + 1 < width) {
                int i = idx + 1;
                if (visited[i] == 0 && binary[y][x + 1]) stack[sp++] = i;
            }
            if (x - 1 >= 0) {
                int i = idx - 1;
                if (visited[i] == 0 && binary[y][x - 1]) stack[sp++] = i;
            }
            if (y + 1 < height) {
                int i = idx + width;
                if (visited[i] == 0 && binary[y + 1][x]) stack[sp++] = i;
            }
            if (y - 1 >= 0) {
                int i = idx - width;
                if (visited[i] == 0 && binary[y - 1][x]) stack[sp++] = i;
            }
        }

        return m.size > 0 ? m : null;
    }

    // 使用 integral image 快速计算边环平均亮度
    private boolean hasWhiteBorder(Marker m) {
        if (integral == null || grayLinear == null) return false;
        int pad = 3;
        int sx = Math.max(0, m.minX - pad);
        int ex = Math.min(imgWidth - 1, m.maxX + pad);
        int sy = Math.max(0, m.minY - pad);
        int ey = Math.min(imgHeight - 1, m.maxY + pad);

        // outer rect sum
        int outerSum = sumRect(sx, sy, ex, ey);
        int outerArea = (ex - sx + 1) * (ey - sy + 1);

        // inner rect (marker bounding box, clamp)
        int isx = Math.max(0, m.minX);
        int iex = Math.min(imgWidth - 1, m.maxX);
        int isy = Math.max(0, m.minY);
        int iey = Math.min(imgHeight - 1, m.maxY);
        int innerSum = sumRect(isx, isy, iex, iey);
        int innerArea = (iex - isx + 1) * (iey - isy + 1);

        int borderSum = outerSum - innerSum;
        int borderCount = outerArea - innerArea;
        if (borderCount <= 0) return false;
        int avg = borderSum / borderCount;

        // 白色阈值可以调整（近似 180）
        return avg >= 160;
    }

    // integral image 求和，坐标为 inclusive (x0,y0)-(x1,y1)
    private int sumRect(int x0, int y0, int x1, int y1) {
        if (x0 > x1 || y0 > y1) return 0;
        int w = imgWidth + 1;
        int A = integral[y0 * w + x0];
        int B = integral[y0 * w + (x1 + 1)];
        int C = integral[(y1 + 1) * w + x0];
        int D = integral[(y1 + 1) * w + (x1 + 1)];
        return D - B - C + A;
    }

    private PointF[] findGridCorners(List<Marker> markers) {
        if (markers.size() < 3) return null;
        float avgX = 0, avgY = 0;
        for (Marker m : markers) {
            avgX += m.centerX;
            avgY += m.centerY;
        }
        avgX /= markers.size();
        avgY /= markers.size();

        Marker topLeft = null, topRight = null, bottomLeft = null, bottomRight = null;
        float minTLD = Float.MAX_VALUE, minTR = Float.MAX_VALUE, minBL = Float.MAX_VALUE, minBR = Float.MAX_VALUE;

        for (Marker m : markers) {
            float dx = m.centerX - avgX;
            float dy = m.centerY - avgY;
            float dist = (float) Math.hypot(dx, dy);
            if (dx < 0 && dy < 0 && dist < minTLD) { topLeft = m; minTLD = dist; }
            if (dx > 0 && dy < 0 && dist < minTR) { topRight = m; minTR = dist; }
            if (dx < 0 && dy > 0 && dist < minBL) { bottomLeft = m; minBL = dist; }
            if (dx > 0 && dy > 0 && dist < minBR) { bottomRight = m; minBR = dist; }
        }

        int corners = 0;
        if (topLeft != null) corners++;
        if (topRight != null) corners++;
        if (bottomLeft != null) corners++;
        if (bottomRight != null) corners++;
        if (corners < 3) return null;

        float expansion = -10;
        PointF tl = topLeft != null ? new PointF(topLeft.centerX - expansion, topLeft.centerY - expansion)
                : new PointF(avgX - 150, avgY - 150);
        PointF tr = topRight != null ? new PointF(topRight.centerX + expansion, topRight.centerY - expansion)
                : new PointF(avgX + 150, avgY - 150);
        PointF br = bottomRight != null ? new PointF(bottomRight.centerX + expansion, bottomRight.centerY + expansion)
                : new PointF(avgX + 150, avgY + 150);
        PointF bl = bottomLeft != null ? new PointF(bottomLeft.centerX - expansion, bottomLeft.centerY + expansion)
                : new PointF(avgX - 150, avgY + 150);

        return new PointF[]{tl, tr, br, bl};
    }

    private static class Marker {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int sumX = 0, sumY = 0;
        int size = 0;
        float centerX, centerY;

        void addPoint(int x, int y) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumX += x;
            sumY += y;
            size++;
            centerX = (float) sumX / size;
            centerY = (float) sumY / size;
        }
    }
}
