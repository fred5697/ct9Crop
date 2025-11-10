package com.pbn.ct9crop;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class MarkerDetector {
    private static final String TAG = "MarkerDetector";

    // 要求最小边长（像素）
    private static final int MIN_MARKER_DIM = 40;
    // 最小面积（可选）
    private static final int MIN_MARKER_AREA = MIN_MARKER_DIM * MIN_MARKER_DIM;

    // 原有 HSV 阈值：H in [45,65] degrees, S > 0.68, V > 0.75
    private static final float H_MIN = 45f;
    private static final float H_MAX = 65f;
    private static final float S_MIN = 0.68f;
    private static final float V_MIN = 0.75f;

    // BL 专用阈值：H in [200,220], S > 0.68, V > 0.73
    private static final float BL_H_MIN = 190f;
    private static final float BL_H_MAX = 230f;
    private static final float BL_S_MIN = 0.63f;
    private static final float BL_V_MIN = 0.63f;

    // TR 专用阈值（之前的）：S < 20% , V between 30% and 65% , no specific H constraint
    private static final float TR_H_MIN = 0f;
    private static final float TR_H_MAX = 360f;
    private static final float TR_S_MAX = 0.20f;
    private static final float TR_V_MIN = 0.30f;
    private static final float TR_V_MAX = 0.65f;

    // 新：第4个 marker 要求 S < 20% 且 V < 25% (命名为 BR)
    private static final float BR_S_MAX = 0.20f;
    private static final float BR_V_MAX = 0.25f;

    public static class DetectionResult {
        public boolean detected;
        public int markerCount;
        public PointF[] corners;  // 4 corners: TL, TR, BR, BL
        public PointF[] markerPositions;  // All detected marker centers

        public DetectionResult() {
            this.detected = false;
            this.markerCount = 0;
        }
    }

    public DetectionResult detectMarkers(Bitmap bitmap) {
        int targetWidth = 640;
        int targetHeight = (int) (bitmap.getHeight() * (targetWidth / (float) bitmap.getWidth()));
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);

        // Create HSV mask for primary markers
        boolean[][] mask = createHSVMask(scaledBitmap);

        // Find connected components on mask (pass bitmap so floodFill can accumulate HSV)
        List<Marker> markers = findMarkers(mask, scaledBitmap.getWidth(), scaledBitmap.getHeight(), scaledBitmap);

        // 如果有 marker，取第一个作为 TL 并输出其平均 HSV
        if (markers.size() > 0) {
            Marker tl = markers.get(0);
            float avgH = tl.getAvgH();
            float avgS = tl.getAvgS() * 100f; // percent
            float avgV = tl.getAvgV() * 100f; // percent

            Log.d(TAG, String.format("Found %d potential markers \u2014 TL HSV: H=%.1f° S=%.0f%% V=%.0f%%",
                    markers.size(), avgH, avgS, avgV));
        } else {
            Log.d(TAG, "Found 0 potential markers");
        }

        // 单独用 BL 阈值再找一个 marker（H 200..220, S>0.68, V>0.73）
        Marker blMarker = findMarkerByHSV(scaledBitmap, BL_H_MIN, BL_H_MAX, BL_S_MIN, BL_V_MIN);
        if (blMarker != null) {
            boolean duplicate = false;
            for (Marker m : markers) {
                float dx = m.centerX - blMarker.centerX;
                float dy = m.centerY - blMarker.centerY;
                if (Math.hypot(dx, dy) < 10.0f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                markers.add(blMarker);
            }

            float avgH = blMarker.getAvgH();
            float avgS = blMarker.getAvgS() * 100f;
            float avgV = blMarker.getAvgV() * 100f;
            Log.d(TAG, String.format("BL found \u2014 H=%.1f° S=%.0f%% V=%.0f%% at (%.1f,%.1f)",
                    avgH, avgS, avgV, blMarker.centerX, blMarker.centerY));


        } else {
            Log.d(TAG, "No BL marker found with H in [200,220], S>68%%, V>73%%");
        }

        // 查找第三个 marker：TR(Top-Left) - S < 20% 且 V 在 30%..65%，H 任意
        Marker trMarker = findMarkerByHSVRange(scaledBitmap, TR_H_MIN, TR_H_MAX, TR_S_MAX, TR_V_MIN, TR_V_MAX);
        if (trMarker != null) {
            boolean duplicate = false;
            for (Marker m : markers) {
                float dx = m.centerX - trMarker.centerX;
                float dy = m.centerY - trMarker.centerY;
                if (Math.hypot(dx, dy) < 10.0f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                markers.add(trMarker);
            }

            float avgH = trMarker.getAvgH();
            float avgS = trMarker.getAvgS() * 100f;
            float avgV = trMarker.getAvgV() * 100f;
            Log.d(TAG, String.format("TR(Top-Left) found \u2014 H=%.1f° S=%.0f%% V=%.0f%% at (%.1f,%.1f)",
                    avgH, avgS, avgV, trMarker.centerX, trMarker.centerY));
        } else {
            Log.d(TAG, "No TR(Top-Left) marker found (S<20%% and V in 30%%..65%%)");
        }

        // 新：查找第4个 marker：S < 20% 且 V < 25%，命名为 BR(Bottom-right)
        Marker brMarker = findMarkerBySAndVUpper(scaledBitmap, BR_S_MAX, BR_V_MAX);
        if (brMarker != null) {
            boolean duplicate = false;
            for (Marker m : markers) {
                float dx = m.centerX - brMarker.centerX;
                float dy = m.centerY - brMarker.centerY;
                if (Math.hypot(dx, dy) < 10.0f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                markers.add(brMarker);
            }

            float avgH = brMarker.getAvgH();
            float avgS = brMarker.getAvgS() * 100f;
            float avgV = brMarker.getAvgV() * 100f;
            Log.d(TAG, String.format("BR (Bottom-right) found \u2014 H=%.1f° S=%.0f%% V=%.0f%% at (%.1f,%.1f)",
                    avgH, avgS, avgV, brMarker.centerX, brMarker.centerY));
        } else {
            Log.d(TAG, "No BR marker found (S<20%% and V<25%%)");
        }

        DetectionResult result = new DetectionResult();
        result.markerCount = markers.size();

        if (markers.size() >= 4) {
            // Scale marker positions back to original size
            float scaleX = bitmap.getWidth() / (float) targetWidth;
            float scaleY = bitmap.getHeight() / (float) targetHeight;

            result.markerPositions = new PointF[markers.size()];
            for (int i = 0; i < markers.size(); i++) {
                result.markerPositions[i] = new PointF(
                        markers.get(i).centerX * scaleX,
                        markers.get(i).centerY * scaleY
                );
            }

            // Find corner markers and compute grid bounds
            PointF[] corners = findGridCorners(markers);

            if (corners != null) {
                result.detected = true;
                result.corners = new PointF[4];
                for (int i = 0; i < 4; i++) {
                    // scale corners back to original bitmap size
                    result.corners[i] = new PointF(corners[i].x * scaleX, corners[i].y * scaleY);
                }
            }
        }

        return result;
    }

    private boolean[][] createHSVMask(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        boolean[][] mask = new boolean[h][w];
        float[] hsv = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = bitmap.getPixel(x, y);
                Color.colorToHSV(px, hsv); // hsv[0]=H(0..360), hsv[1]=S(0..1), hsv[2]=V(0..1)
                float H = hsv[0];
                float S = hsv[1];
                float V = hsv[2];

                boolean hMatch = (H >= H_MIN && H <= H_MAX);
                boolean sMatch = (S >= S_MIN);
                boolean vMatch = (V >= V_MIN);

                mask[y][x] = hMatch && sMatch && vMatch;
            }
        }

        return mask;
    }

    private List<Marker> findMarkers(boolean[][] mask, int width, int height, Bitmap bitmap) {
        boolean[][] visited = new boolean[height][width];
        List<Marker> markers = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y][x] && !visited[y][x]) {
                    Marker m = floodFill(mask, visited, x, y, width, height, bitmap);

                    int wBox = m.maxX - m.minX + 1;
                    int hBox = m.maxY - m.minY + 1;
                    int area = m.size;

                    // 要求至少 40x40 的包围盒并且面积合理
                    if (wBox >= MIN_MARKER_DIM && hBox >= MIN_MARKER_DIM && area >= MIN_MARKER_AREA) {
                        // 可选的长宽比过滤（保持宽高接近）
                        float aspect = wBox / (float) hBox;
                        if (aspect > 0.5f && aspect < 2.0f) {
                            markers.add(m);
                            Log.d(TAG, String.format("Candidate marker: center=(%.1f,%.1f) w=%d h=%d area=%d H=%.1f S=%.2f V=%.2f",
                                    m.centerX, m.centerY, wBox, hBox, area, m.getAvgH(), m.getAvgS(), m.getAvgV()));
                        }
                    }
                }
            }
        }

        return markers;
    }

    // 在单独的 HSV 范围内寻找第一个满足尺寸的 marker（用于 BL）
    private Marker findMarkerByHSV(Bitmap bitmap, float hMin, float hMax, float sMin, float vMin) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean[][] mask = new boolean[height][width];
        float[] hsv = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = bitmap.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                float H = hsv[0], S = hsv[1], V = hsv[2];
                boolean match = (H >= hMin && H <= hMax) && (S >= sMin) && (V >= vMin);
                mask[y][x] = match;
            }
        }

        boolean[][] visited = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y][x] && !visited[y][x]) {
                    Marker m = floodFill(mask, visited, x, y, width, height, bitmap);
                    int wBox = m.maxX - m.minX + 1;
                    int hBox = m.maxY - m.minY + 1;
                    int area = m.size;
                    if (wBox >= MIN_MARKER_DIM && hBox >= MIN_MARKER_DIM && area >= MIN_MARKER_AREA) {
                        return m;
                    }
                }
            }
        }

        return null;
    }

    // 新：支持 S 上限和 V 范围的通用查找（用于 TR）
    private Marker findMarkerByHSVRange(Bitmap bitmap, float hMin, float hMax, float sMax, float vMin, float vMax) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean[][] mask = new boolean[height][width];
        float[] hsv = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = bitmap.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                float H = hsv[0], S = hsv[1], V = hsv[2];
                boolean match = (H >= hMin && H <= hMax) && (S <= sMax) && (V >= vMin && V <= vMax);
                mask[y][x] = match;
            }
        }

        boolean[][] visited = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y][x] && !visited[y][x]) {
                    Marker m = floodFill(mask, visited, x, y, width, height, bitmap);
                    int wBox = m.maxX - m.minX + 1;
                    int hBox = m.maxY - m.minY + 1;
                    int area = m.size;
                    if (wBox >= MIN_MARKER_DIM && hBox >= MIN_MARKER_DIM && area >= MIN_MARKER_AREA) {
                        return m;
                    }
                }
            }
        }

        return null;
    }

    // 新：查找 S <= sMax 且 V <= vMax 的第一个满足尺寸的连通域（用于 BR）
    private Marker findMarkerBySAndVUpper(Bitmap bitmap, float sMax, float vMax) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean[][] mask = new boolean[height][width];
        float[] hsv = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = bitmap.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                float S = hsv[1], V = hsv[2];
                boolean match = (S <= sMax) && (V <= vMax);
                mask[y][x] = match;
            }
        }

        boolean[][] visited = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y][x] && !visited[y][x]) {
                    Marker m = floodFill(mask, visited, x, y, width, height, bitmap);
                    int wBox = m.maxX - m.minX + 1;
                    int hBox = m.maxY - m.minY + 1;
                    int area = m.size;
                    if (wBox >= MIN_MARKER_DIM && hBox >= MIN_MARKER_DIM && area >= MIN_MARKER_AREA) {
                        return m;
                    }
                }
            }
        }

        return null;
    }

    private Marker floodFill(boolean[][] mask, boolean[][] visited, int startX, int startY,
                             int width, int height, Bitmap bitmap) {
        Marker marker = new Marker();
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[]{startX, startY});
        float[] hsv = new float[3];

        while (!stack.isEmpty()) {
            int[] p = stack.remove(stack.size() - 1);
            int x = p[0], y = p[1];

            if (x < 0 || x >= width || y < 0 || y >= height) continue;
            if (visited[y][x] || !mask[y][x]) continue;

            visited[y][x] = true;

            // 读取 HSV 并累加到 marker
            int px = bitmap.getPixel(x, y);
            Color.colorToHSV(px, hsv);
            marker.addPoint(x, y, hsv[0], hsv[1], hsv[2]);

            // 4-neighbors
            stack.add(new int[]{x + 1, y});
            stack.add(new int[]{x - 1, y});
            stack.add(new int[]{x, y + 1});
            stack.add(new int[]{x, y - 1});
        }

        return marker;
    }

    private PointF[] findGridCorners(List<Marker> markers) {
        if (markers.size() < 3) return null;

        // Sort markers by centroid relative to average to choose corners
        float avgX = 0, avgY = 0;
        for (Marker m : markers) {
            avgX += m.centerX;
            avgY += m.centerY;
        }
        avgX /= markers.size();
        avgY /= markers.size();

        Marker topLeft = null, topRight = null, bottomLeft = null, bottomRight = null;
        float minTLDist = Float.MAX_VALUE, minTRDist = Float.MAX_VALUE;
        float minBLDist = Float.MAX_VALUE, minBRDist = Float.MAX_VALUE;

        for (Marker m : markers) {
            float dx = m.centerX - avgX;
            float dy = m.centerY - avgY;
            float dist = (float) Math.hypot(dx, dy);

            if (dx < 0 && dy < 0 && dist < minTLDist) {
                topLeft = m; minTLDist = dist;
            } else if (dx > 0 && dy < 0 && dist < minTRDist) {
                topRight = m; minTRDist = dist;
            } else if (dx < 0 && dy > 0 && dist < minBLDist) {
                bottomLeft = m; minBLDist = dist;
            } else if (dx > 0 && dy > 0 && dist < minBRDist) {
                bottomRight = m; minBRDist = dist;
            }
        }

        int cornerCount = 0;
        if (topLeft != null) cornerCount++;
        if (topRight != null) cornerCount++;
        if (bottomLeft != null) cornerCount++;
        if (bottomRight != null) cornerCount++;

        if (cornerCount < 3) return null;

        float expansion = 15f;
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

        // HSV 累加
        private float sumH = 0f, sumS = 0f, sumV = 0f;

        void addPoint(int x, int y, float h, float s, float v) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumX += x;
            sumY += y;
            size++;
            centerX = (float) sumX / size;
            centerY = (float) sumY / size;

            // HSV 累加（注意 H 环绕情况：本算法假设 H 在同一区间，无跨 360 度）
            sumH += h;
            sumS += s;
            sumV += v;
        }

        float getAvgH() {
            return size > 0 ? sumH / size : 0f;
        }

        float getAvgS() {
            return size > 0 ? sumS / size : 0f;
        }

        float getAvgV() {
            return size > 0 ? sumV / size : 0f;
        }
    }
}
