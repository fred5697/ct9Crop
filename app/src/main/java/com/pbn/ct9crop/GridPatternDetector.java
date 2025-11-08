// GridPatternDetector.java
package com.pbn.ct9crop;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class GridPatternDetector {
    private static final String TAG = "GridDetector";
    private static final int MIN_SATURATION = 50;
    private static final int MIN_GRID_SIZE = 80;
    private static final int MAX_GRID_SIZE = 1200;

    // Color detection thresholds
    private static final int YELLOW_HUE_MIN = 40;
    private static final int YELLOW_HUE_MAX = 75;
    private static final int CYAN_HUE_MIN = 165;
    private static final int CYAN_HUE_MAX = 270;
    private static final int BLACK_BRIGHTNESS_MAX = 60;
    private static final int MIN_COLOR_cSATURATION = 2;
    private static final int MIN_COLOR_ySATURATION = 35;

    public static class DetectionResult {
        public boolean detected;
        public boolean isTargetPattern;
        public PointF[] corners;  // 4 corners: TL, TR, BR, BL
        public int[][] colors;
        // HSV values for each sampled cell: [row][col][0=H,1=S,2=V]
        public float[][][] hsvValues;
        // Sample positions in original bitmap coordinates for each cell (row,col)
        public PointF[][] samplePoints;

        public DetectionResult(boolean detected) {
            this.detected = detected;
            this.isTargetPattern = false;
            this.corners = null;
            this.colors = new int[3][3];
            this.hsvValues = new float[3][3][3];
            this.samplePoints = new PointF[3][3];
        }

        /**
         * Convenience formatter for display (e.g. draw under a dot).
         * Returns "H:xxx S:yy% V:zz%" or an empty string if out of range.
         */
        public String getHSVText(int row, int col) {
            if (hsvValues == null) return "";
            if (row < 0 || row >= 3 || col < 0 || col >= 3) return "";
            float h = hsvValues[row][col][0];
            float s = hsvValues[row][col][1];
            float v = hsvValues[row][col][2];
            return String.format("H:%.0f S:%.0f%% V:%.0f%%", h, s, v);
        }
    }

    public DetectionResult detectGrid(Bitmap bitmap) {
        int targetWidth = 640;
        int targetHeight = (int) (bitmap.getHeight() * (targetWidth / (float) bitmap.getWidth()));
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);

        // Find colorful regions
        boolean[][] colorMask = findColorfulRegions(scaledBitmap);

        // Find contours and corners
        PointF[] corners = findGridCorners(colorMask, scaledBitmap.getWidth(), scaledBitmap.getHeight());

        if (corners != null) {
            // Scale corners back to original size
            float scaleX = bitmap.getWidth() / (float) targetWidth;
            float scaleY = bitmap.getHeight() / (float) targetHeight;

            PointF[] originalCorners = new PointF[4];
            for (int i = 0; i < 4; i++) {
                originalCorners[i] = new PointF(
                        corners[i].x * scaleX,
                        corners[i].y * scaleY
                );
            }

            DetectionResult result = new DetectionResult(true);
            result.corners = originalCorners;
            result.colors = extractGridColors(bitmap, originalCorners);

            // Compute sample (floating) positions in original bitmap coordinates
            float centerX = (originalCorners[0].x + originalCorners[1].x + originalCorners[2].x + originalCorners[3].x) / 4f;
            float centerY = (originalCorners[0].y + originalCorners[1].y + originalCorners[2].y + originalCorners[3].y) / 4f;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    float fracRow = (r - 1) / 2.5f;  // -0.4, 0, 0.4
                    float fracCol = (c - 1) / 2.5f;
                    float x = centerX + fracCol * (originalCorners[1].x - originalCorners[0].x) +
                            fracRow * (originalCorners[3].x - originalCorners[0].x);
                    float y = centerY + fracCol * (originalCorners[1].y - originalCorners[0].y) +
                            fracRow * (originalCorners[3].y - originalCorners[0].y);
                    result.samplePoints[r][c] = new PointF(x, y);
                }
            }

            // Fill HSV values for each sampled grid cell (H: 0..360, S: percent, V: percent)
            float[] hsvTmp = new float[3];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    int color = result.colors[r][c];
                    Color.colorToHSV(color, hsvTmp);
                    result.hsvValues[r][c][0] = hsvTmp[0];                   // Hue 0..360
                    result.hsvValues[r][c][1] = hsvTmp[1] * 100f;           // Saturation 0..100%
                    result.hsvValues[r][c][2] = hsvTmp[2] * 100f;           // Brightness 0..100%
                }
            }

            result.isTargetPattern = isTargetPattern(result.colors);

            if (result.isTargetPattern) {
                Log.d(TAG, "✓ Target pattern detected!");
            }

            return result;
        }

        return new DetectionResult(false);
    }

    private boolean[][] findColorfulRegions(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean[][] mask = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                float saturation = calculateSaturation(pixel);
                mask[y][x] = saturation > MIN_SATURATION;
            }
        }

        return mask;
    }

    private PointF[] findGridCorners(boolean[][] mask, int width, int height) {
        // Find bounding box first
        int minX = width, maxX = 0;
        int minY = height, maxY = 0;

        boolean found = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y][x]) {
                    found = true;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (!found) return null;

        int w = maxX - minX;
        int h = maxY - minY;

        // Validate size
        if (w < MIN_GRID_SIZE || h < MIN_GRID_SIZE || w > MAX_GRID_SIZE || h > MAX_GRID_SIZE) {
            return null;
        }

        // Find actual corners by searching for edge transitions
        PointF topLeft = findCornerPoint(mask, minX, minY, width, height, 1, 1);
        PointF topRight = findCornerPoint(mask, maxX, minY, width, height, -1, 1);
        PointF bottomRight = findCornerPoint(mask, maxX, maxY, width, height, -1, -1);
        PointF bottomLeft = findCornerPoint(mask, minX, maxY, width, height, 1, -1);

        return new PointF[] { topLeft, topRight, bottomRight, bottomLeft };
    }

    private PointF findCornerPoint(boolean[][] mask, int startX, int startY,
                                   int width, int height, int dirX, int dirY) {
        int searchRadius = 20;
        int bestX = startX;
        int bestY = startY;

        for (int r = 0; r < searchRadius; r++) {
            int x = Math.max(0, Math.min(width - 1, startX + dirX * r));
            int y = Math.max(0, Math.min(height - 1, startY + dirY * r));

            if (y < height && x < width && mask[y][x]) {
                bestX = x;
                bestY = y;
            }
        }

        return new PointF(bestX, bestY);
    }

    private int[][] extractGridColors(Bitmap bitmap, PointF[] corners) {
        int[][] colors = new int[3][3];

        // Calculate center of grid
        float centerX = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4;
        float centerY = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4;

        // Sample from 9 positions in a grid pattern
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float fracRow = (row - 1) / 2.5f;  // -0.4, 0, 0.4
                float fracCol = (col - 1) / 2.5f;

                // Interpolate position
                float x = centerX + fracCol * (corners[1].x - corners[0].x) +
                        fracRow * (corners[3].x - corners[0].x);
                float y = centerY + fracCol * (corners[1].y - corners[0].y) +
                        fracRow * (corners[3].y - corners[0].y);

                int px = Math.max(0, Math.min(bitmap.getWidth() - 1, (int) x));
                int py = Math.max(0, Math.min(bitmap.getHeight() - 1, (int) y));

                colors[row][col] = bitmap.getPixel(px, py);
            }
        }

        return colors;
    }

    private boolean isTargetPattern(int[][] colors) {
        int topLeft = colors[0][0];
        int bottomLeft = colors[2][0];
        int bottomRight = colors[2][2];

        boolean hasYellow = isYellow(topLeft);
        boolean hasCyan = isCyan(bottomLeft);
        boolean hasBlack = isBlack(bottomRight);

        Log.d(TAG, String.format("Pattern check - Yellow: %b, Cyan: %b, Black: %b",
                hasYellow, hasCyan, hasBlack));

        return hasYellow && hasCyan && hasBlack;
    }

    private boolean isYellow(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        float hue = hsv[0];
        float saturation = hsv[1] * 100;
        float brightness = hsv[2] * 100;

        boolean result = hue >= YELLOW_HUE_MIN && hue <= YELLOW_HUE_MAX &&
                saturation >= MIN_COLOR_ySATURATION && brightness >= 45;

        Log.d(TAG, String.format("Yellow: H=%.1f S=%.1f V=%.1f → %b", hue, saturation, brightness, result));
        return result;
    }

    private boolean isCyan(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        float hue = hsv[0];
        float saturation = hsv[1] * 100;
        float brightness = hsv[2] * 100;

        boolean result = hue >= CYAN_HUE_MIN && hue <= CYAN_HUE_MAX &&
                saturation >= MIN_COLOR_cSATURATION && brightness >= 45;

        Log.d(TAG, String.format("Cyan: H=%.1f S=%.1f V=%.1f → %b", hue, saturation, brightness, result));
        return result;
    }

    private boolean isBlack(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int brightness = (r + g + b) / 3;

        boolean result = brightness <= BLACK_BRIGHTNESS_MAX;
        Log.d(TAG, String.format("Black: RGB(%d,%d,%d) brightness=%d → %b", r, g, b, brightness, result));
        return result;
    }

    private float calculateSaturation(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));

        if (max == 0) return 0;
        return ((max - min) / max) * 100;
    }
}