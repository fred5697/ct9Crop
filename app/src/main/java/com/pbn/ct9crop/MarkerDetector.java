
// MarkerDetector.java
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
    private static final int MIN_MARKER_SIZE = 15;
    private static final int MAX_MARKER_SIZE = 80;
    private static final int MARKER_THRESHOLD = 80;  // Black threshold
    private static final float WHITE_BORDER_THRESHOLD = 180;  // White threshold

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

        // Convert to grayscale and threshold
        boolean[][] binaryImage = createBinaryImage(scaledBitmap);

        // Find all black regions (potential markers)
        List<Marker> markers = findMarkers(binaryImage, scaledBitmap.getWidth(), scaledBitmap.getHeight());

        Log.d(TAG, "Found " + markers.size() + " potential markers");

        DetectionResult result = new DetectionResult();
        result.markerCount = markers.size();

        if (markers.size() >= 3) {
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
                    result.corners[i] = new PointF(
                            corners[i].x * scaleX,
                            corners[i].y * scaleY
                    );
                }
            }
        }

        return result;
    }

    private boolean[][] createBinaryImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean[][] binary = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                binary[y][x] = brightness < MARKER_THRESHOLD;  // true = black
            }
        }

        return binary;
    }

    private List<Marker> findMarkers(boolean[][] binary, int width, int height) {
        boolean[][] visited = new boolean[height][width];
        List<Marker> markers = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (binary[y][x] && !visited[y][x]) {
                    Marker marker = floodFill(binary, visited, x, y, width, height);

                    // Check if this is a valid marker (square-ish black blob)
                    if (marker.size >= MIN_MARKER_SIZE && marker.size <= MAX_MARKER_SIZE * MAX_MARKER_SIZE) {
                        int w = marker.maxX - marker.minX + 1;
                        int h = marker.maxY - marker.minY + 1;
                        float aspectRatio = (float) w / h;

                        // Markers should be roughly square
                        if (aspectRatio > 0.5 && aspectRatio < 2.0) {
                            markers.add(marker);
                        }
                    }
                }
            }
        }

        return markers;
    }

    private Marker floodFill(boolean[][] binary, boolean[][] visited, int startX, int startY,
                             int width, int height) {
        Marker marker = new Marker();
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[]{startX, startY});

        while (!stack.isEmpty()) {
            int[] point = stack.remove(stack.size() - 1);
            int x = point[0];
            int y = point[1];

            if (x < 0 || x >= width || y < 0 || y >= height) continue;
            if (visited[y][x] || !binary[y][x]) continue;

            visited[y][x] = true;
            marker.addPoint(x, y);

            // 4-connected neighbors
            stack.add(new int[]{x + 1, y});
            stack.add(new int[]{x - 1, y});
            stack.add(new int[]{x, y + 1});
            stack.add(new int[]{x, y - 1});
        }

        return marker;
    }

    private PointF[] findGridCorners(List<Marker> markers) {
        if (markers.size() < 3) return null;

        // Sort markers by position to find corners
        float avgX = 0, avgY = 0;
        for (Marker m : markers) {
            avgX += m.centerX;
            avgY += m.centerY;
        }
        avgX /= markers.size();
        avgY /= markers.size();

        // Find markers in each quadrant
        Marker topLeft = null, topRight = null, bottomLeft = null, bottomRight = null;
        float minTLDist = Float.MAX_VALUE, minTRDist = Float.MAX_VALUE;
        float minBLDist = Float.MAX_VALUE, minBRDist = Float.MAX_VALUE;

        for (Marker m : markers) {
            float dx = m.centerX - avgX;
            float dy = m.centerY - avgY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            if (dx < 0 && dy < 0 && dist < minTLDist) {
                topLeft = m;
                minTLDist = dist;
            } else if (dx > 0 && dy < 0 && dist < minTRDist) {
                topRight = m;
                minTRDist = dist;
            } else if (dx < 0 && dy > 0 && dist < minBLDist) {
                bottomLeft = m;
                minBLDist = dist;
            } else if (dx > 0 && dy > 0 && dist < minBRDist) {
                bottomRight = m;
                minBRDist = dist;
            }
        }

        // Need at least 3 corners
        int cornerCount = 0;
        if (topLeft != null) cornerCount++;
        if (topRight != null) cornerCount++;
        if (bottomLeft != null) cornerCount++;
        if (bottomRight != null) cornerCount++;

        if (cornerCount < 3) return null;

        // Expand grid bounds slightly beyond markers
        float expansion = 15;

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