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
import androidx.camera.core.Camera;
import android.widget.SeekBar;
import android.util.Range;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CT9Crop";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private ImageView frameOverlay;
    private TextView statusText;
    private Button captureButton;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private Camera camera = null;
    private SeekBar exposureSeekBar;
    private TextView exposureValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        exposureSeekBar = findViewById(R.id.exposureSeekBar);
        exposureValue = findViewById(R.id.exposureValue);
        exposureSeekBar.setEnabled(false);
        exposureValue.setText("Exposure: 0");

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
        // 修改 processCapturedImage 中裁切後的處理：
// 把最後一段 showRgbInfoDialog(croppedBitmap); saveImage(croppedBitmap);
// 替換為下列流程（直接貼入 processCapturedImage 的 croppedBitmap 生成後）：

        if (pendingCapturedCropped == null) {
            // 第一張已拍，提示使用者翻轉裝置並再次拍攝
            pendingCapturedCropped = croppedBitmap; // 儲存第一張（已裁切）
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Please capture 2nd image")
                        .setMessage("請將裝置或被攝物件旋轉 180°，然後再次按下 CAPTURE。\n\n第一張已暫存。")
                        .setPositiveButton("OK", (d, w) -> {
                            d.dismiss();
                            statusText.setText("Rotate 180° and capture 2nd");
                            statusText.setBackgroundColor(0xDDFFAA00);
                            resetCaptureButton();
                        })
                        .setCancelable(false)
                        .show();
            });
        } else {
            // 第二張已拍，進行平均、顯示並儲存
            final Bitmap bmpA = pendingCapturedCropped;
            final Bitmap bmpB = croppedBitmap;

            // 顯示兩張配對平均的數據（使用已有方法）
            showRgbInfoDialogDoubleCapture(bmpA, bmpB);

            // 產生像素平均圖並儲存
            Bitmap avgBmp = averageBitmaps(bmpA, bmpB);
            if (avgBmp != null) {
                // 計算 top30 最亮平均並檢查是否足夠明亮
                int[] brightest = averageTopBrightest(avgBmp, 30);
                int brightestMax = Math.max(brightest[0], Math.max(brightest[1], brightest[2]));
                if (brightestMax < 200) {
                    // 不儲存，提示使用者調整曝光並重新拍攝
                    final int bMax = brightestMax;
                    if (!avgBmp.isRecycled()) avgBmp.recycle();
                    pendingCapturedCropped = null;
                    runOnUiThread(() -> {
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("曝光不足")
                                .setMessage(String.format(Locale.getDefault(),
                                        "偵測到目前最亮 RGB = %d (< 200)。請增加曝光（使用畫面下方滑桿）後重新拍攝。", bMax))
                                .setPositiveButton("OK", (d, w) -> d.dismiss())
                                .show();
                        statusText.setText("Increase exposure and recapture");
                        statusText.setBackgroundColor(0xDDFF4444);
                        resetCaptureButton();
                    });
                } else {
                    // 亮度足夠，儲存 avgBmp
                    saveImage(avgBmp);
                }
            }

            // 清除暫存並回復 UI
            if (!bmpA.isRecycled()) bmpA.recycle();
            pendingCapturedCropped = null;

            runOnUiThread(() -> {
                statusText.setText("Align 3x3 grid in frame");
                statusText.setBackgroundColor(0x80000000);
                resetCaptureButton();
            });
        }

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
    // 替换现有的 showRgbInfoDialog 方法为下列实现（显示 D50 Lab），并在类中添加 labD65ToLabD50 / labFinvSafe / mulMatVec 方法。

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

            // 先得到 D65 Lab，再转换为 D50 Lab
            double[] labD65 = displayP3RgbToLab(rgb[0], rgb[1], rgb[2]);
            double[] labD50 = labD65ToLabD50(labD65[0], labD65[1], labD65[2]);

            sb.append(String.format(Locale.US,
                    "(%d,%d): R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                    origX, origY, rgb[0], rgb[1], rgb[2], labD50[0], labD50[1], labD50[2]));
        }

        int[] topAvg = averageTopBrightest(bmp, 30);
        double[] topLabD65 = displayP3RgbToLab(topAvg[0], topAvg[1], topAvg[2]);
        double[] topLabD50 = labD65ToLabD50(topLabD65[0], topLabD65[1], topLabD65[2]);

        sb.append(String.format(Locale.US,
                "\nAverage of top 30 brightest pixels:\nR=%d G=%d B=%d  →  L(D50x)=%.1f a(D50)=%.1f b(D50)=%.1f",
                topAvg[0], topAvg[1], topAvg[2], topLabD50[0], topLabD50[1], topLabD50[2]));

        final String message = sb.toString();

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Captured Image RGB & Lab (D50)")
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

// java
// 添加到 MainActivity.java

    // 将 CIE L*a*b* (D65) 转为 CIE L*a*b* (D50)（Bradford 适配）
    private double[] labD65ToLabD50(double L, double a, double b) {
        // 参考白点
        final double Xn_D65 = 0.95047;
        final double Yn = 1.0;
        final double Zn_D65 = 1.08883;

        final double Xn_D50 = 0.96422;
        final double Zn_D50 = 0.82521;

        // Lab (D65) -> XYZ (D65)
        double fy = (L + 16.0) / 116.0;
        double fx = fy + (a / 500.0);
        double fz = fy - (b / 200.0);

        double xr = labFinvSafe(fx);
        double yr = labFinvSafe(fy);
        double zr = labFinvSafe(fz);

        double Xd65 = xr * Xn_D65;
        double Yd65 = yr * Yn;
        double Zd65 = zr * Zn_D65;

        // Bradford adaptation matrices
        double[][] M = {
                {0.8951000,  0.2664000, -0.1614000},
                {-0.7502000, 1.7135000,  0.0367000},
                {0.0389000, -0.0685000,  1.0296000}
        };
        double[][] M_INV = {
                { 0.9869929, -0.1470543,  0.1599627},
                { 0.4323053,  0.5183603,  0.0492912},
                {-0.0085287,  0.0400428,  0.9684867}
        };

        // convert source and destination white to cone response domain
        double[] srcWhiteCone = mulMatVec(M, new double[]{Xn_D65, Yn, Zn_D65});
        double[] dstWhiteCone = mulMatVec(M, new double[]{Xn_D50, Yn, Zn_D50});

        // convert XYZ (D65) to cone responses
        double[] cone = mulMatVec(M, new double[]{Xd65, Yd65, Zd65});

        // scale in cone domain
        double[] scale = new double[3];
        for (int i = 0; i < 3; i++) {
            scale[i] = srcWhiteCone[i] == 0.0 ? 1.0 : (dstWhiteCone[i] / srcWhiteCone[i]);
        }
        double[] adaptedCone = new double[3];
        for (int i = 0; i < 3; i++) adaptedCone[i] = cone[i] * scale[i];

        // back to XYZ (D50)
        double[] adaptedXYZ = mulMatVec(M_INV, adaptedCone);
        double Xd50 = adaptedXYZ[0];
        double Yd50 = adaptedXYZ[1];
        double Zd50 = adaptedXYZ[2];

        // XYZ (D50) -> Lab (D50)
       // final double Xn_D50 = 0.96422;
        final double Zn_D50_CONST = 0.82521;

        double fx2 = labF(Xd50 / Xn_D50);
        double fy2 = labF(Yd50 / Yn);
        double fz2 = labF(Zd50 / Zn_D50_CONST);

        double L2 = 116.0 * fy2 - 16.0;
        double a2 = 500.0 * (fx2 - fy2);
        double b2 = 200.0 * (fy2 - fz2);

        return new double[]{L2, a2, b2};
    }

    // 安全版 lab f^{-1}（用于 Lab -> XYZ）
    private double labFinvSafe(double f) {
        final double delta = 6.0 / 29.0;
        if (f > delta) {
            return f * f * f;
        } else {
            return 3.0 * delta * delta * (f - 4.0 / 29.0);
        }
    }

    // 矩阵乘向量 (3x3 * 3x1)
    private double[] mulMatVec(double[][] m, double[] v) {
        double[] r = new double[3];
        r[0] = m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2];
        r[1] = m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2];
        r[2] = m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2];
        return r;
    }


    // 新增：對兩張顛倒拍攝的圖像，對應點配對平均後顯示 D50 Lab

    // 修改後：對兩張顛倒拍攝的圖像，對應點配對平均後顯示 D50 Lab，並附加 top30 最亮像素平均（白參考）
    private void showRgbInfoDialogDoubleCapture(Bitmap bmpA, Bitmap bmpB) {
        if (bmpA == null || bmpB == null) return;

        int[][] points = new int[][] {
                {175,175}, {175,525}, {175,875},
                {528,175}, {515,525}, {525,875},
                {875,175}, {875,525}, {875,875}
        };
        final int regionHalf = 15; // 30x30

        StringBuilder sb = new StringBuilder();
        sb.append("9 positions averaged from two captures (A + B → avg):\n");

        // 保存每個位置的平均 RGB 以便稍後正規化
        int[][] avgList = new int[points.length][3];

        for (int i = 0; i < points.length; i++) {
            int flip = 8 - i; // 180° 對應
            int ax = Math.max(0, Math.min(points[i][0], bmpA.getWidth() - 1));
            int ay = Math.max(0, Math.min(points[i][1], bmpA.getHeight() - 1));
            int bx = Math.max(0, Math.min(points[flip][0], bmpB.getWidth() - 1));
            int by = Math.max(0, Math.min(points[flip][1], bmpB.getHeight() - 1));

            int[] rgbA = averageRgbInRegion(bmpA, ax, ay, regionHalf);
            int[] rgbB = averageRgbInRegion(bmpB, bx, by, regionHalf);

            int avgR = (rgbA[0] + rgbB[0]) / 2;
            int avgG = (rgbA[1] + rgbB[1]) / 2;
            int avgB = (rgbA[2] + rgbB[2]) / 2;

            avgList[i][0] = avgR;
            avgList[i][1] = avgG;
            avgList[i][2] = avgB;

            double[] labD65 = displayP3RgbToLab(avgR, avgG, avgB);
            double[] labD50 = labD65ToLabD50(labD65[0], labD65[1], labD65[2]);

            sb.append(String.format(Locale.US,
                    "pos %d (A@%d,%d + B@%d,%d) → R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                    i, ax, ay, bx, by, avgR, avgG, avgB, labD50[0], labD50[1], labD50[2]));
        }

        // 產生兩張影像的像素平均圖，並計算 top30 最亮像素平均作為白參考
        Bitmap avgBmp = averageBitmaps(bmpA, bmpB);
        int[] topAvg = new int[]{0,0,0};
        if (avgBmp != null) {
            topAvg = averageTopBrightest(avgBmp, 30);
            double[] topLabD65 = displayP3RgbToLab(topAvg[0], topAvg[1], topAvg[2]);
            double[] topLabD50 = labD65ToLabD50(topLabD65[0], topLabD65[1], topLabD65[2]);

            sb.append(String.format(Locale.US,
                    "\nAverage of top 30 brightest pixels (avg image):\nR=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                    topAvg[0], topAvg[1], topAvg[2], topLabD50[0], topLabD50[1], topLabD50[2]));

            if (!avgBmp.isRecycled()) avgBmp.recycle();
        }

        final int[] finalTopAvg = topAvg; // for lambda

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Averaged RGB & Lab (D50)")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", (d, w) -> {
                        d.dismiss();
                        // 按下 OK 後顯示正規化結果的第二個對話視窗
                        showNormalizedResultsDialog(avgList, finalTopAvg);
                    })
                    .show();
        });
    }

    // 輔助：對 avgList 做以 brightest white 為參考的正規化，並顯示第二個對話視窗
    // java
    private void showNormalizedResultsDialog(int[][] avgList, int[] topAvg) {
        if (avgList == null || avgList.length == 0) return;

        // 目標白色（修正：B = 236）
        final double targetR = 233.0;
        final double targetG = 231.0;
        final double targetB = 236.0;

        // 計算通道放大係數（避開除以 0）
        double scaleR = topAvg[0] > 0 ? (targetR / (double) topAvg[0]) : 1.0;
        double scaleG = topAvg[1] > 0 ? (targetG / (double) topAvg[1]) : 1.0;
        double scaleB = topAvg[2] > 0 ? (targetB / (double) topAvg[2]) : 1.0;

        StringBuilder nsb = new StringBuilder();
        nsb.append("Normalized results (white mapped to ");
        nsb.append(String.format(Locale.US, "R=%.0f G=%.0f B=%.0f", targetR, targetG, targetB));
        nsb.append(")\n\n");

        // 正規化每個位置（只 clamp 到 0..255）
        for (int i = 0; i < avgList.length; i++) {
            int orR = avgList[i][0];
            int orG = avgList[i][1];
            int orB = avgList[i][2];

            int nR = (int) Math.round(orR * scaleR);
            int nG = (int) Math.round(orG * scaleG);
            int nB = (int) Math.round(orB * scaleB);

            // 僅保證 0..255
            nR = Math.max(0, Math.min(255, nR));
            nG = Math.max(0, Math.min(255, nG));
            nB = Math.max(0, Math.min(255, nB));

            double[] labD65 = displayP3RgbToLab(nR, nG, nB);
            double[] labD50 = labD65ToLabD50(labD65[0], labD65[1], labD65[2]);

            nsb.append(String.format(Locale.US,
                    "pos %d -> R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                    i, nR, nG, nB, labD50[0], labD50[1], labD50[2]));
        }

        // 顯示正規化後的白參考值（經同樣縮放）
        int whiteR = (int) Math.round(topAvg[0] * scaleR);
        int whiteG = (int) Math.round(topAvg[1] * scaleG);
        int whiteB = (int) Math.round(topAvg[2] * scaleB);
        whiteR = Math.max(0, Math.min(255, whiteR));
        whiteG = Math.max(0, Math.min(255, whiteG));
        whiteB = Math.max(0, Math.min(255, whiteB));

        double[] whiteLabD65 = displayP3RgbToLab(whiteR, whiteG, whiteB);
        double[] whiteLabD50 = labD65ToLabD50(whiteLabD65[0], whiteLabD65[1], whiteLabD65[2]);

        nsb.append("\nNormalized white (after scaling):\n");
        nsb.append(String.format(Locale.US,
                "R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                whiteR, whiteG, whiteB, whiteLabD50[0], whiteLabD50[1], whiteLabD50[2]));

        final String message = nsb.toString();

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Normalized RGB & Lab (D50)")
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }



    // 在 MainActivity 類的成員區新增：
    private Bitmap pendingCapturedCropped = null;


    // 新增：像素逐點平均兩張同尺寸 Bitmap（若尺寸不同嘗試裁切到相同大小）
    private Bitmap averageBitmaps(Bitmap a, Bitmap b) {
        if (a == null || b == null) return null;

        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        int[] pixelsA = new int[w];
        int[] pixelsB = new int[w];
        int[] outPixels = new int[w];

        for (int y = 0; y < h; y++) {
            a.getPixels(pixelsA, 0, w, 0, y, w, 1);
            b.getPixels(pixelsB, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int pa = pixelsA[x];
                int pb = pixelsB[x];

                int ra = (pa >> 16) & 0xFF;
                int ga = (pa >> 8) & 0xFF;
                int ba = pa & 0xFF;

                int rb = (pb >> 16) & 0xFF;
                int gb = (pb >> 8) & 0xFF;
                int bb = pb & 0xFF;

                int r = (ra + rb) / 2;
                int g = (ga + gb) / 2;
                int bch = (ba + bb) / 2;

                outPixels[x] = 0xFF000000 | (r << 16) | (g << 8) | bch;
            }
            out.setPixels(outPixels, 0, w, 0, y, w, 1);
        }
        return out;
    }

    // 3) 在 bindCameraUseCases(...) 中取得 Camera 實例並設定曝光滑桿
    // java
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

        // Unbind previous use-cases and bind new ones, 並取得 Camera 實例
        cameraProvider.unbindAll();
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind camera use cases", e);
            return;
        }

        // 設定曝光滑桿（若可用）
        try {
            androidx.camera.core.ExposureState es = camera.getCameraInfo().getExposureState();
            Range<Integer> range = es.getExposureCompensationRange();
            int min = range.getLower();
            int max = range.getUpper();
            final int offset = min; // map seekbar 0..(max-min) -> index = progress + offset
            exposureSeekBar.setMax(max - min);
            int currentIndex = es.getExposureCompensationIndex();
            exposureSeekBar.setProgress(currentIndex - offset);
            exposureSeekBar.setEnabled(true);
            exposureValue.setText("Exposure: " + currentIndex);

            exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int index = progress + offset;
                    exposureValue.setText("Exposure: " + index);
                    if (camera != null) {
                        camera.getCameraControl().setExposureCompensationIndex(index);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        } catch (Exception e) {
            Log.w(TAG, "Exposure control not available", e);
            exposureSeekBar.setEnabled(false);
        }

        Log.d(TAG, "Camera started successfully");
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}