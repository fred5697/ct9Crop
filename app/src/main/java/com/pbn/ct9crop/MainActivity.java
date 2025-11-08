// MainActivity.java
package com.pbn.ct9crop;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CT9Crop";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private ImageView detectionOverlay;
    private TextView statusText;
    private Button captureButton;
    private ExecutorService cameraExecutor;
    private MarkerDetector markerDetector;

    private Bitmap lastDetectedBitmap = null;
    private PointF[] lastDetectedCorners = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        detectionOverlay = findViewById(R.id.detectionOverlay);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);

        markerDetector = new MarkerDetector();
        cameraExecutor = Executors.newSingleThreadExecutor();

        captureButton.setVisibility(View.GONE);
        captureButton.setOnClickListener(v -> captureAndSaveGrid());

        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        int aspectRatio = androidx.camera.core.AspectRatio.RATIO_4_3;

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                processImage(image);
            }
        });

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void processImage(ImageProxy image) {
        Bitmap bitmap = imageProxyToBitmap(image);

        if (bitmap != null) {
            MarkerDetector.DetectionResult result = markerDetector.detectMarkers(bitmap);

            runOnUiThread(() -> {
                if (result.detected && result.corners != null && result.corners.length == 4) {
                    statusText.setText(String.format("✓ %d markers detected!", result.markerCount));
                    statusText.setBackgroundColor(0xDD00FF00);
                    captureButton.setVisibility(View.VISIBLE);

                    lastDetectedBitmap = bitmap.copy(bitmap.getConfig(), true);
                    lastDetectedCorners = result.corners;

                    drawDetectionOverlay(bitmap, result.corners, result.markerPositions);
                } else if (result.markerCount > 0) {
                    statusText.setText(String.format("Found %d markers (need 3+)", result.markerCount));
                    statusText.setBackgroundColor(0xDDFFAA00);
                    captureButton.setVisibility(View.GONE);
                    if (result.markerPositions != null) {
                        drawMarkersOnly(bitmap, result.markerPositions);
                    }
                } else {
                    statusText.setText("Searching for corner markers...");
                    statusText.setBackgroundColor(0x80000000);
                    captureButton.setVisibility(View.GONE);
                    detectionOverlay.setImageBitmap(null);
                }
            });
        }

        image.close();
    }

    private void captureAndSaveGrid() {
        if (lastDetectedBitmap == null || lastDetectedCorners == null) {
            Toast.makeText(this, "No grid to capture", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Bitmap croppedGrid = perspectiveCorrection(lastDetectedBitmap, lastDetectedCorners);

            int[][][] avgRgb = sampleAverageRGBFromRectified(croppedGrid, 4);
            showGridRGBDialog(avgRgb);

            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File ct9Dir = new File(picturesDir, "CT9Crop");
            if (!ct9Dir.exists()) {
                ct9Dir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "CT9_Grid_" + timeStamp + ".jpg";
            File imageFile = new File(ct9Dir, fileName);

            FileOutputStream out = new FileOutputStream(imageFile);
            croppedGrid.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();

            Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_LONG).show();

            captureButton.setText("✓ Captured!");
            captureButton.postDelayed(() -> captureButton.setText("CAPTURE GRID"), 1000);

        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap perspectiveCorrection(Bitmap source, PointF[] corners) {
        float width1 = distance(corners[0], corners[1]);
        float width2 = distance(corners[2], corners[3]);
        float height1 = distance(corners[0], corners[3]);
        float height2 = distance(corners[1], corners[2]);

        int outputWidth = (int) Math.max(width1, width2);
        int outputHeight = (int) Math.max(height1, height2);

        Matrix matrix = new Matrix();
        float[] src = new float[] {
                corners[0].x, corners[0].y,
                corners[1].x, corners[1].y,
                corners[2].x, corners[2].y,
                corners[3].x, corners[3].y
        };
        float[] dst = new float[] {
                0, 0,
                outputWidth, 0,
                outputWidth, outputHeight,
                0, outputHeight
        };

        matrix.setPolyToPoly(src, 0, dst, 0, 4);

        Bitmap corrected = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(corrected);
        canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG));

        return corrected;
    }

    private float distance(PointF p1, PointF p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void drawDetectionOverlay(Bitmap originalBitmap, PointF[] corners, PointF[] markers) {
        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();

        if (previewWidth == 0 || previewHeight == 0) return;

        float scaleX = previewWidth / (float) originalBitmap.getWidth();
        float scaleY = previewHeight / (float) originalBitmap.getHeight();
        float scale = Math.min(scaleX, scaleY);

        float scaledWidth = originalBitmap.getWidth() * scale;
        float scaledHeight = originalBitmap.getHeight() * scale;
        float offsetX = (previewWidth - scaledWidth) / 2;
        float offsetY = (previewHeight - scaledHeight) / 2;

        Bitmap overlay = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);

        Paint paint = new Paint();
        paint.setAntiAlias(true);

        // Draw grid outline
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8);

        PointF[] transformedCorners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            transformedCorners[i] = new PointF(
                    corners[i].x * scale + offsetX,
                    corners[i].y * scale + offsetY
            );
        }

        for (int i = 0; i < 4; i++) {
            PointF start = transformedCorners[i];
            PointF end = transformedCorners[(i + 1) % 4];
            canvas.drawLine(start.x, start.y, end.x, end.y, paint);
        }

        // Draw detected markers
        if (markers != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.RED);
            for (PointF marker : markers) {
                float mx = marker.x * scale + offsetX;
                float my = marker.y * scale + offsetY;
                canvas.drawCircle(mx, my, 12, paint);
            }
        }

        detectionOverlay.setImageBitmap(overlay);
    }

    private void drawMarkersOnly(Bitmap originalBitmap, PointF[] markers) {
        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();

        if (previewWidth == 0 || previewHeight == 0) return;

        float scaleX = previewWidth / (float) originalBitmap.getWidth();
        float scaleY = previewHeight / (float) originalBitmap.getHeight();
        float scale = Math.min(scaleX, scaleY);

        float scaledWidth = originalBitmap.getWidth() * scale;
        float scaledHeight = originalBitmap.getHeight() * scale;
        float offsetX = (previewWidth - scaledWidth) / 2;
        float offsetY = (previewHeight - scaledHeight) / 2;

        Bitmap overlay = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.YELLOW);

        for (PointF marker : markers) {
            float mx = marker.x * scale + offsetX;
            float my = marker.y * scale + offsetY;
            canvas.drawCircle(mx, my, 10, paint);
        }

        detectionOverlay.setImageBitmap(overlay);
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);

        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    // java
// Add to `app/src/main/java/com/pbn/ct9crop/MainActivity.java`
    private int[][][] sampleAverageRGBFromRectified(Bitmap bmp, int patchSize) {
        // 返回 [row][col][channel]  channel: 0=R,1=G,2=B
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[][][] result = new int[3][3][3];

        int half = patchSize / 2;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                // 以均匀 3x3 网格中心为采样点
                float cx = (col + 0.5f) * w / 3f;
                float cy = (row + 0.5f) * h / 3f;
                int startX = Math.round(cx) - half;
                int startY = Math.round(cy) - half;

                long sumR = 0, sumG = 0, sumB = 0;
                int count = 0;

                for (int yy = 0; yy < patchSize; yy++) {
                    int py = startY + yy;
                    if (py < 0 || py >= h) continue;
                    for (int xx = 0; xx < patchSize; xx++) {
                        int px = startX + xx;
                        if (px < 0 || px >= w) continue;
                        int pixel = bmp.getPixel(px, py);
                        sumR += android.graphics.Color.red(pixel);
                        sumG += android.graphics.Color.green(pixel);
                        sumB += android.graphics.Color.blue(pixel);
                        count++;
                    }
                }

                if (count == 0) {
                    result[row][col][0] = result[row][col][1] = result[row][col][2] = 0;
                } else {
                    result[row][col][0] = (int) (sumR / count);
                    result[row][col][1] = (int) (sumG / count);
                    result[row][col][2] = (int) (sumB / count);
                }
            }
        }
        return result;
    }

    private void showGridRGBDialog(int[][][] rgb) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                sb.append(String.format("Cell %d,%d: R=%d G=%d B=%d", r, c,
                        rgb[r][c][0], rgb[r][c][1], rgb[r][c][2]));
                if (!(r == 2 && c == 2)) sb.append("\n");
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Average RGB (4x4 center patch)")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // java
// Add to `app/src/main/java/com/pbn/ct9crop/MainActivity.java`
    private int[] findBrightestRGBInBitmap(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int bestR = 0, bestG = 0, bestB = 0;
        int bestSum = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bmp.getPixel(x, y);
                int r = Color.red(p);
                int g = Color.green(p);
                int b = Color.blue(p);
                int sum = r + g + b;
                if (sum > bestSum) {
                    bestSum = sum;
                    bestR = r; bestG = g; bestB = b;
                }
            }
        }
        return new int[]{bestR, bestG, bestB};
    }

    /**
     * 在仅亮度高于 threshold 的像素中寻找最亮像素（用于 marker 白边场景）
     * threshold: 0..255, 建议 ~200
     */
    private int[] findBrightestRGBInBrightRegions(Bitmap bmp, int threshold) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int bestR = 0, bestG = 0, bestB = 0;
        int bestSum = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bmp.getPixel(x, y);
                int r = Color.red(p);
                int g = Color.green(p);
                int b = Color.blue(p);
                int brightness = (r + g + b) / 3;
                if (brightness < threshold) continue;
                int sum = r + g + b;
                if (sum > bestSum) {
                    bestSum = sum;
                    bestR = r; bestG = g; bestB = b;
                }
            }
        }
        // 若未找到任何亮区域，则退回整图最亮
        if (bestSum < 0) return findBrightestRGBInBitmap(bmp);
        return new int[]{bestR, bestG, bestB};
    }

    /**
     * 以参考最亮 RGB 对 3x3 rgb 做归一化（参考亮组映射到 255,255,255）
     * 输入: rgb[3][3][3] channel: 0=R,1=G,2=B
     */
    private int[][][] normalizeGridRGBByReference(int[][][] rgb, int[] referenceRGB) {
        int[][][] out = new int[3][3][3];
        int refR = Math.max(0, referenceRGB[0]);
        int refG = Math.max(0, referenceRGB[1]);
        int refB = Math.max(0, referenceRGB[2]);

        boolean anyZero = (refR == 0) || (refG == 0) || (refB == 0);
        float scaleR, scaleG, scaleB;
        if (anyZero) {
            int maxChannel = Math.max(1, Math.max(refR, Math.max(refG, refB)));
            float uniform = 255f / (float) maxChannel;
            scaleR = scaleG = scaleB = uniform;
        } else {
            scaleR = 255f / (float) refR;
            scaleG = 255f / (float) refG;
            scaleB = 255f / (float) refB;
        }

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int R = rgb[r][c][0];
                int G = rgb[r][c][1];
                int B = rgb[r][c][2];
                int nR = Math.round(R * scaleR);
                int nG = Math.round(G * scaleG);
                int nB = Math.round(B * scaleB);
                out[r][c][0] = Math.min(255, Math.max(0, nR));
                out[r][c][1] = Math.min(255, Math.max(0, nG));
                out[r][c][2] = Math.min(255, Math.max(0, nB));
            }
        }
        return out;
    }

// 示例：在 captureAndSaveGrid() 中替换原来的归一化调用部分
// ---------------------------
// Bitmap croppedGrid = perspectiveCorrection(lastDetectedBitmap, lastDetectedCorners);
// int[][][] avgRgb = sampleAverageRGBFromRectified(croppedGrid, 4);

// 选项 A: 使用整张校正图的最亮像素作为参考
// int[] brightest = findBrightestRGBInBitmap(croppedGrid);

// 选项 B: 只在很亮的区域（如 marker 白边）中找最亮，阈值可调整（例如 200）
// int[] brightest = findBrightestRGBInBrightRegions(croppedGrid, 200);

// 然后归一化并显示
// int[][][] normalized = normalizeGridRGBByReference(avgRgb, brightest);
// showGridRGBDialog(normalized);
// ---------------------------



}
