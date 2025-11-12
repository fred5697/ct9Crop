// MainActivity.java
package com.pbn.ct9crop;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
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
import com.pbn.ct9crop.R;
import android.view.KeyEvent;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CT9Crop";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private ImageView frameOverlay;
    private TextView statusText;
    private Button captureButton;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        frameOverlay = findViewById(R.id.frameOverlay);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);

        cameraExecutor = Executors.newSingleThreadExecutor();

        captureButton.setOnClickListener(v -> captureImage());

        // Draw square frame overlay after view is laid out
        previewView.post(() -> drawSquareFrame());

        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }
    }

    // java
    private void drawSquareFrame() {
        int width = previewView.getWidth();
        int height = previewView.getHeight();

        if (width == 0 || height == 0) {
            // View not ready yet, try again
            previewView.postDelayed(() -> drawSquareFrame(), 100);
            return;
        }

        // Calculate square frame size (60% of smaller dimension)
        int minDimension = Math.min(width, height);
        int frameSize = (int) (minDimension * 0.6f);

        // Center the frame
        int left = (width - frameSize) / 2;
        int top = (height - frameSize) / 2;

        Bitmap overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);

        // Draw semi-transparent dark background outside frame
        Paint dimPaint = new Paint();
        dimPaint.setColor(0x88000000);  // Semi-transparent black
        canvas.drawRect(0, 0, width, height, dimPaint);

        // Clear the frame area (outer frame window)
        Paint clearPaint = new Paint();
        clearPaint.setColor(Color.TRANSPARENT);
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        canvas.drawRect(left, top, left + frameSize, top + frameSize, clearPaint);

        // Draw outer frame border
        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.GREEN);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(8);
        borderPaint.setAntiAlias(true);
        canvas.drawRect(left, top, left + frameSize, top + frameSize, borderPaint);

        // Draw corner markers for outer frame
        int cornerLength = 50;
        borderPaint.setStrokeWidth(6);

        // Top-left corner
        canvas.drawLine(left, top, left + cornerLength, top, borderPaint);
        canvas.drawLine(left, top, left, top + cornerLength, borderPaint);

        // Top-right corner
        canvas.drawLine(left + frameSize - cornerLength, top, left + frameSize, top, borderPaint);
        canvas.drawLine(left + frameSize, top, left + frameSize, top + cornerLength, borderPaint);

        // Bottom-left corner
        canvas.drawLine(left, top + frameSize - cornerLength, left, top + frameSize, borderPaint);
        canvas.drawLine(left, top + frameSize, left + cornerLength, top + frameSize, borderPaint);

        // Bottom-right corner
        canvas.drawLine(left + frameSize - cornerLength, top + frameSize, left + frameSize, top + frameSize, borderPaint);
        canvas.drawLine(left + frameSize, top + frameSize - cornerLength, left + frameSize, top + frameSize, borderPaint);

        // Draw center crosshair for outer frame
        int centerX = left + frameSize / 2;
        int centerY = top + frameSize / 2;
        int crossSize = 30;
        borderPaint.setStrokeWidth(3);
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, borderPaint);
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, borderPaint);

        // --- 新增：绘制第二个内框，向四边内缩 50 px ---
        final int inset = 80;
        int innerLeft = left + inset;
        int innerTop = top + inset;
        int innerSize = frameSize - inset * 2;

        if (innerSize > 0) {
            // 使用不同颜色和较细的线
            Paint innerPaint = new Paint();
            innerPaint.setColor(Color.YELLOW);
            innerPaint.setStyle(Paint.Style.STROKE);
            innerPaint.setStrokeWidth(6);
            innerPaint.setAntiAlias(true);

            // 绘制内框边线
            canvas.drawRect(innerLeft, innerTop, innerLeft + innerSize, innerTop + innerSize, innerPaint);

            // 内框角标（较短）
            int innerCorner = 30;
            innerPaint.setStrokeWidth(4);

            // Top-left inner
            canvas.drawLine(innerLeft, innerTop, innerLeft + innerCorner, innerTop, innerPaint);
            canvas.drawLine(innerLeft, innerTop, innerLeft, innerTop + innerCorner, innerPaint);

            // Top-right inner
            canvas.drawLine(innerLeft + innerSize - innerCorner, innerTop, innerLeft + innerSize, innerTop, innerPaint);
            canvas.drawLine(innerLeft + innerSize, innerTop, innerLeft + innerSize, innerTop + innerCorner, innerPaint);

            // Bottom-left inner
            canvas.drawLine(innerLeft, innerTop + innerSize - innerCorner, innerLeft, innerTop + innerSize, innerPaint);
            canvas.drawLine(innerLeft, innerTop + innerSize, innerLeft + innerCorner, innerTop + innerSize, innerPaint);

            // Bottom-right inner
            canvas.drawLine(innerLeft + innerSize - innerCorner, innerTop + innerSize, innerLeft + innerSize, innerTop + innerSize, innerPaint);
            canvas.drawLine(innerLeft + innerSize, innerTop + innerSize - innerCorner, innerLeft + innerSize, innerTop + innerSize, innerPaint);
        }

        frameOverlay.setImageBitmap(overlay);

        statusText.setText("Align 3x3 grid in frame");
        statusText.setBackgroundColor(0x80000000);
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

        // Preview
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image Capture
        imageCapture = new ImageCapture.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        // Camera selector
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // Bind to lifecycle
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

        Log.d(TAG, "Camera started successfully");
    }

    private void captureImage() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Capturing...");
        statusText.setBackgroundColor(0xDDFFAA00);
        captureButton.setEnabled(false);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = imageProxyToBitmap(image);
                image.close();

                if (bitmap != null) {
                    processCapturedImage(bitmap);
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Failed to process image", Toast.LENGTH_SHORT).show();
                        resetCaptureButton();
                    });
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Capture failed", exception);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                    resetCaptureButton();
                });
            }
        });
    }



    // 修改后的 processCapturedImage 方法（放入 `app/src/main/java/com/pbn/ct9crop/MainActivity.java`）
    private void processCapturedImage(Bitmap bitmap) {
        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();
        if (previewWidth == 0 || previewHeight == 0 || bitmap == null) return;

        // 与 drawSquareFrame() 保持一致的比例
        float frameFraction = 0.6f;
        int minDimension = Math.min(previewWidth, previewHeight);
        int frameSize = (int) (minDimension * frameFraction);
        int frameLeft = (previewWidth - frameSize) / 2;
        int frameTop = (previewHeight - frameSize) / 2;

        // 计算预览到位图的映射（处理 center-crop 型缩放）
        float scaleX = previewWidth / (float) bitmap.getWidth();
        float scaleY = previewHeight / (float) bitmap.getHeight();
        // 如果 PreviewView 对位图做了填充（center-crop），应使用 max
        float scaleToView = Math.max(scaleX, scaleY);

        float displayedWidth = bitmap.getWidth() * scaleToView;
        float displayedHeight = bitmap.getHeight() * scaleToView;
        float offsetX = (previewWidth - displayedWidth) / 2f;
        float offsetY = (previewHeight - displayedHeight) / 2f;

        // 将 preview 上的框转换到 bitmap 坐标
        float cropLeftF = (frameLeft - offsetX) / scaleToView;
        float cropTopF = (frameTop - offsetY) / scaleToView;
        float cropSizeF = frameSize / scaleToView;

        int cropLeft = Math.max(0, Math.round(cropLeftF));
        int cropTop = Math.max(0, Math.round(cropTopF));
        int cropSize = Math.round(cropSizeF);

        // 扩展区域：向四周各扩展 50 像素（位图坐标）
        final int expandPx = 50;
        cropLeft -= expandPx;
        cropTop -= expandPx;
        cropSize += expandPx * 2;

        // 边界约束：如果超出左/上边界，移动并缩小尺寸
        if (cropLeft < 0) {
            int diff = -cropLeft;
            cropLeft = 0;
            cropSize -= diff;
        }
        if (cropTop < 0) {
            int diff = -cropTop;
            cropTop = 0;
            cropSize -= diff;
        }

        // 边界约束：如果超出右/下边界，缩小尺寸
        if (cropLeft + cropSize > bitmap.getWidth()) {
            int overflow = cropLeft + cropSize - bitmap.getWidth();
            cropSize -= overflow;
        }
        if (cropTop + cropSize > bitmap.getHeight()) {
            int overflow = cropTop + cropSize - bitmap.getHeight();
            cropSize -= overflow;
        }

        // 最终检查，若尺寸无效则回退到中心裁切
        if (cropSize <= 0 || cropLeft < 0 || cropTop < 0
                || cropLeft + cropSize > bitmap.getWidth()
                || cropTop + cropSize > bitmap.getHeight()) {
            cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());
            cropLeft = (bitmap.getWidth() - cropSize) / 2;
            cropTop = (bitmap.getHeight() - cropSize) / 2;
        }

        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropSize, cropSize);
        showRgbInfoDialog(croppedBitmap);

        saveImage(croppedBitmap);
    }



    private void saveImage(Bitmap bitmap) {
        try {
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File ct9Dir = new File(picturesDir, "CT9Crop");
            if (!ct9Dir.exists()) {
                ct9Dir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "CT9_Grid_" + timeStamp + ".jpg";
            File imageFile = new File(ct9Dir, fileName);

            FileOutputStream out = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();

            runOnUiThread(() -> {
                Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_LONG).show();
                statusText.setText("✓ Captured! Ready for next");
                statusText.setBackgroundColor(0xDD00FF00);

                captureButton.setText("✓ SAVED");
                captureButton.postDelayed(() -> {
                    captureButton.setText("CAPTURE");
                    statusText.setText("Align 3x3 grid in frame");
                    statusText.setBackgroundColor(0x80000000);
                }, 1500);

                resetCaptureButton();
            });

            Log.d(TAG, "Image saved: " + imageFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                resetCaptureButton();
            });
        }
    }

    private void resetCaptureButton() {
        runOnUiThread(() -> captureButton.setEnabled(true));
    }

    // java
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return null;

            // Case: single plane (e.g. JPEG compressed)
            if (planes.length == 1) {
                ByteBuffer buffer = planes[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }

            // Case: YUV_420_888 (>= 3 planes)
            if (planes.length >= 3) {
                ByteBuffer yBuffer = planes[0].getBuffer();
                ByteBuffer uBuffer = planes[1].getBuffer();
                ByteBuffer vBuffer = planes[2].getBuffer();

                int ySize = yBuffer.remaining();
                int uSize = uBuffer.remaining();
                int vSize = vBuffer.remaining();

                byte[] nv21 = new byte[ySize + uSize + vSize];

                // Y
                yBuffer.get(nv21, 0, ySize);
                // V
                vBuffer.get(nv21, ySize, vSize);
                // U
                uBuffer.get(nv21, ySize + vSize, uSize);

                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                        image.getWidth(), image.getHeight(), null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
                byte[] imageBytes = out.toByteArray();
                return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            }

        } catch (Exception e) {
            Log.e(TAG, "imageProxyToBitmap error", e);
        }
        return null;
    }

// java
// 在 MainActivity 类中添加以下方法，并在 processCapturedImage 方法的末尾调用 showRgbInfoDialog(croppedBitmap);

    private int[] averageRgbInRegion(Bitmap bmp, int cx, int cy, int regionHalfSize) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int left = Math.max(0, cx - regionHalfSize);
        int right = Math.min(w - 1, cx + regionHalfSize);
        int top = Math.max(0, cy - regionHalfSize);
        int bottom = Math.min(h - 1, cy + regionHalfSize);

        long sumR = 0, sumG = 0, sumB = 0;
        long count = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int px = bmp.getPixel(x, y);
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                sumR += r;
                sumG += g;
                sumB += b;
                count++;
            }
        }
        if (count == 0) return new int[]{0,0,0};
        return new int[]{(int)(sumR / count), (int)(sumG / count), (int)(sumB / count)};
    }

    private int[] averageTopBrightest(Bitmap bmp, int topN) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        // 小根堆保存 topN 最亮的像素（堆元素：float brightness, int packedRGB）
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(topN, (a,b) -> Float.compare((float)a[0], (float)b[0]));

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = bmp.getPixel(x, y);
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                // 使用人眼感知亮度
                float brightness = 0.299f * r + 0.587f * g + 0.114f * b;
                long packed = ((long)r << 16) | ((long)g << 8) | (long)b;
                if (pq.size() < topN) {
                    pq.offer(new long[]{Float.floatToIntBits(brightness), packed});
                } else {
                    float minBrightness = Float.intBitsToFloat((int)pq.peek()[0]);
                    if (brightness > minBrightness) {
                        pq.poll();
                        pq.offer(new long[]{Float.floatToIntBits(brightness), packed});
                    }
                }
            }
        }

        if (pq.isEmpty()) return new int[]{0,0,0};

        long sumR = 0, sumG = 0, sumB = 0;
        int cnt = pq.size();
        while (!pq.isEmpty()) {
            long[] e = pq.poll();
            long packed = e[1];
            int r = (int)((packed >> 16) & 0xFF);
            int g = (int)((packed >> 8) & 0xFF);
            int b = (int)(packed & 0xFF);
            sumR += r;
            sumG += g;
            sumB += b;
        }
        return new int[]{(int)(sumR / cnt), (int)(sumG / cnt), (int)(sumB / cnt)};
    }

    // 替换现有的 showRgbInfoDialog 方法，显示 RGB 及对应的 Lab
    private void showRgbInfoDialog(Bitmap bmp) {
        int[][] points = new int[][] {
                {175,175}, {175,525}, {175,875},
                {528,175}, {515,525}, {525,875},
                {875,175}, {875,525}, {875,875}
        };
        final int regionHalf = 15; // 30x30 区域 => 半径 15

        StringBuilder sb = new StringBuilder();
        sb.append("9 positions RGB (avg 30x30):\n");
        for (int i = 0; i < points.length; i++) {
            int origX = points[i][0];
            int origY = points[i][1];
            int cx = Math.max(0, Math.min(origX, bmp.getWidth() - 1));
            int cy = Math.max(0, Math.min(origY, bmp.getHeight() - 1));
            int[] rgb = averageRgbInRegion(bmp, cx, cy, regionHalf);

            double[] lab = displayP3RgbToLab(rgb[0], rgb[1], rgb[2]);
            sb.append(String.format(Locale.US, "(%d,%d): R=%d G=%d B=%d  →  L=%.1f a=%.1f b=%.1f\n",
                    origX, origY, rgb[0], rgb[1], rgb[2], lab[0], lab[1], lab[2]));
        }

        int[] topAvg = averageTopBrightest(bmp, 30);
        double[] topLab = displayP3RgbToLab(topAvg[0], topAvg[1], topAvg[2]);
        sb.append(String.format(Locale.US,
                "\nAverage of top 30 brightest pixels:\nR=%d G=%d B=%d  →  L=%.1f a=%.1f b=%.1f",
                topAvg[0], topAvg[1], topAvg[2], topLab[0], topLab[1], topLab[2]));

        final String message = sb.toString();

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Captured Image RGB & Lab")
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }

// 修改 processCapturedImage 的结尾：在生成 croppedBitmap 后，先弹窗显示信息，再保存
// 把原先直接调用 saveImage(croppedBitmap); 替换为下面两行：
/*
    showRgbInfoDialog(croppedBitmap);
    saveImage(croppedBitmap);
*/
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
        // 如果按钮可用，使用 performClick 保持相同的 UI 行为；否则直接调用 captureImage()
        if (captureButton != null && captureButton.isEnabled()) {
            captureButton.performClick();
        } else {
            captureImage();
        }
        return true; // 拦截系统音量按键
    }
    return super.onKeyDown(keyCode, event);
}

    // 添加：sRGB/DisplayP3 反伽马（OETF） -> 线性值
    private double srgbToLinear(double c) {
        if (c <= 0.04045) return c / 12.92;
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // 添加：XYZ -> f(t) 用于 Lab 计算
    private double labF(double t) {
        final double delta = 6.0 / 29.0;
        if (t > Math.pow(delta, 3)) {
            return Math.cbrt(t);
        } else {
            return t / (3 * delta * delta) + 4.0 / 29.0;
        }
    }

    // 添加：Display P3 (D65) 线性 RGB -> CIE L*a*b*
    private double[] displayP3RgbToLab(int r, int g, int b) {
        // 归一化到 0..1
        double R = r / 255.0;
        double G = g / 255.0;
        double B = b / 255.0;

        // 反伽马 -> 线性 RGB（DisplayP3 使用与 sRGB 相同的 OETF）
        double lr = srgbToLinear(R);
        double lg = srgbToLinear(G);
        double lb = srgbToLinear(B);

        // Display P3 (D65) 线性 RGB -> XYZ 矩阵
        // 来源常见 DisplayP3->XYZ (D65)
        double X = 0.4865709486482162 * lr + 0.26566769316909306 * lg + 0.1982172852343625 * lb;
        double Y = 0.2289745640697488 * lr + 0.6917385218365064 * lg + 0.079286914093745 * lb;
        double Z = 0.0 * lr + 0.04511338185890264 * lg + 1.043944368900976 * lb;

        // 参考白点 D65 （Y=1）
        double Xn = 0.95047;
        double Yn = 1.00000;
        double Zn = 1.08883;

        double fx = labF(X / Xn);
        double fy = labF(Y / Yn);
        double fz = labF(Z / Zn);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bb = 200.0 * (fy - fz);

        return new double[]{L, a, bb};
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}