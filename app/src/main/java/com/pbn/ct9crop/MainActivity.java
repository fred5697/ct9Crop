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

    static {
        // 对应 opencv-android 包提供的 native 名（多数版本为 opencv_java4）
        System.loadLibrary("opencv_java4");
    }

    private static final String TAG = "CT9Crop";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private ImageView detectionOverlay;
    private TextView statusText;
    private Button captureButton;
    private ExecutorService cameraExecutor;
    private GridPatternDetector gridDetector;

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

        gridDetector = new GridPatternDetector();
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
            GridPatternDetector.DetectionResult result = gridDetector.detectGrid(bitmap);

            runOnUiThread(() -> {
                if (result.detected && result.corners != null && result.corners.length == 4) {
                    if (result.isTargetPattern) {
                        statusText.setText("✓ Target pattern detected!");
                        statusText.setBackgroundColor(0xDD00FF00);
                    } else {
                        statusText.setText("✓ Grid detected (not target pattern)");
                        statusText.setBackgroundColor(0xDDFFAA00);
                    }
                    captureButton.setVisibility(View.VISIBLE);

                    lastDetectedBitmap = bitmap.copy(bitmap.getConfig(), true);
                    lastDetectedCorners = result.corners;

                    drawDetectionOverlay(bitmap, result.corners, result.samplePoints);
                } else {
                    statusText.setText("Searching for grid pattern...");
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

    private void drawDetectionOverlay(Bitmap originalBitmap, PointF[] corners, PointF[][] samplePoints) {
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

        // Draw sample points from grid detection
        if (samplePoints != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.CYAN);
            for (int row = 0; row < samplePoints.length; row++) {
                for (int col = 0; col < samplePoints[row].length; col++) {
                    if (samplePoints[row][col] != null) {
                        float mx = samplePoints[row][col].x * scale + offsetX;
                        float my = samplePoints[row][col].y * scale + offsetY;
                        canvas.drawCircle(mx, my, 10, paint);
                    }
                }
            }
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
}