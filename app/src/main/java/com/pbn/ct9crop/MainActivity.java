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
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private static final double POS6_REF_L = 56.0;
    private static final double POS6_REF_A = 3.0;
    private static final double POS6_REF_B = -2.0;

    private int scoreForL(double deltaL) {
        if (deltaL < 2.5) return 10;
        if (deltaL <= 3.0) return 8;
        if (deltaL <= 4.0) return 5;
        if (deltaL <= 5.0) return 3;
        if (deltaL <= 6.0) return 2;
        return 0;
    }

    private int scoreForGrayCh(double deltaCh) {
        if (deltaCh < 2.5) return 10;
        if (deltaCh <= 3.0) return 8;
        if (deltaCh <= 4.0) return 5;
        if (deltaCh <= 5.0) return 3;
        if (deltaCh <= 6.0) return 2;
        return 0;
    }



    // 新增類成員：控制第二張是否採用 180° 翻轉配對取樣（預設 true，原行為）
    private boolean secondCaptureFlip = true;


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

        //captureButton.setOnClickListener(v -> captureImage());

        // captureButton 已存在的點擊行為保持不變
        captureButton.setOnClickListener(v -> captureImage());

        // 長按 captureButton 切換第二拍攝模式（翻轉 / 不翻轉）
        captureButton.setOnLongClickListener(v -> {
            secondCaptureFlip = !secondCaptureFlip;
            String modeText = secondCaptureFlip ? "Flip sampling (rotate 180° for 2nd)" : "No-flip sampling (no rotation for 2nd)";
            Toast.makeText(MainActivity.this, "Second-capture mode: " + modeText, Toast.LENGTH_SHORT).show();
            // 更新狀態欄提示使用者目前模式
            statusText.setText("Mode: " + (secondCaptureFlip ? "Flip" : "No-flip") + " — Align 3x3 grid");
            statusText.setBackgroundColor(0x80000000);
            return true; // 表示已消耗長按事件
        });

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

        // 在 processCapturedImage(...) 中，修改第一張暫存時的提示文字，依模式提示使用者是否要翻轉裝置
        if (pendingCapturedCropped == null) {
            pendingCapturedCropped = croppedBitmap; // 儲存第一張（已裁切）
            runOnUiThread(() -> {
                String title = "Please capture 2nd image";
                String message;
                if (secondCaptureFlip) {
                    message = "請將裝置或被攝物件旋轉 180°，然後再次按下 CAPTURE。\n\n第一張已暫存。";
                } else {
                    message = "請直接再次按下 CAPTURE 拍攝第二張（不要翻轉裝置）。\n\n第一張已暫存。";
                }
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> {
                            d.dismiss();
                            statusText.setText(secondCaptureFlip ? "Rotate 180° and capture 2nd" : "Capture 2nd (no rotation)");
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
        int[][] points = gridPointsFromSize(bmp.getWidth(), bmp.getHeight());
        final int regionHalf = 20; // 30x30 区域 => 半径 15

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

        // 在 showRgbInfoDialogDoubleCapture 方法開頭加入此分支
        if (!secondCaptureFlip) {
            if (bmpA == null || bmpB == null) return;
            int[][] points = gridPointsFromSize(bmpA.getWidth(), bmpA.getHeight());
            final int regionHalf = 20; // 30x30

            StringBuilder sbNoFlip = new StringBuilder();
            sbNoFlip.append("No‑Flip mode — ΔE00 of 9 positions + Top30 brightest average\n\n");
            sbNoFlip.append("All positions: Lab (D50) for A and B, ΔE00 and points\n\n");

            double[][] labsA = new double[9][3];
            double[][] labsB = new double[9][3];

            int totalPoints = 0;

            for (int i = 0; i < points.length; i++) {
                int ax = Math.max(0, Math.min(points[i][0], bmpA.getWidth() - 1));
                int ay = Math.max(0, Math.min(points[i][1], bmpA.getHeight() - 1));
                int bx = Math.max(0, Math.min(points[i][0], bmpB.getWidth() - 1));
                int by = Math.max(0, Math.min(points[i][1], bmpB.getHeight() - 1));

                int[] rgbA = averageRgbInRegion(bmpA, ax, ay, regionHalf);
                int[] rgbB = averageRgbInRegion(bmpB, bx, by, regionHalf);

                double[] labA_d65 = displayP3RgbToLab(rgbA[0], rgbA[1], rgbA[2]);
                double[] labA = labD65ToLabD50(labA_d65[0], labA_d65[1], labA_d65[2]);
                double[] labB_d65 = displayP3RgbToLab(rgbB[0], rgbB[1], rgbB[2]);
                double[] labB = labD65ToLabD50(labB_d65[0], labB_d65[1], labB_d65[2]);

                labsA[i][0] = labA[0]; labsA[i][1] = labA[1]; labsA[i][2] = labA[2];
                labsB[i][0] = labB[0]; labsB[i][1] = labB[1]; labsB[i][2] = labB[2];

                double de = deltaE2000(labA, labB);
                int pts = scoreForDeltaENoFlip(de);
                totalPoints += pts;

                sbNoFlip.append(String.format(Locale.US,
                        "pos %d:\n  A L=%.2f a=%.2f b=%.2f\n  B L=%.2f a=%.2f b=%.2f\n  ΔE00 = %.2f  -> %d pts\n\n",
                        i,
                        labA[0], labA[1], labA[2],
                        labB[0], labB[1], labB[2],
                        de, pts));
            }

            // Top30 最亮平均：分別計算 bmpA 與 bmpB 的 top30 average，然後比較
            int[] topA = averageTopBrightest(bmpA, 30);
            int[] topB = averageTopBrightest(bmpB, 30);
            double[] topA_d65 = displayP3RgbToLab(topA[0], topA[1], topA[2]);
            double[] topA_d50 = labD65ToLabD50(topA_d65[0], topA_d65[1], topA_d65[2]);
            double[] topB_d65 = displayP3RgbToLab(topB[0], topB[1], topB[2]);
            double[] topB_d50 = labD65ToLabD50(topB_d65[0], topB_d65[1], topB_d65[2]);

            double deTop = deltaE2000(topA_d50, topB_d50);
            int ptsTop = scoreForDeltaENoFlip(deTop);
            totalPoints += ptsTop;

            sbNoFlip.append(String.format(Locale.US,
                    "Top30 brightest average:\n  A R=%d G=%d B=%d  → L(D50)=%.2f a=%.2f b=%.2f\n  B R=%d G=%d B=%d  → L(D50)=%.2f a=%.2f b=%.2f\n  ΔE00 = %.2f  -> %d pts\n\n",
                    topA[0], topA[1], topA[2], topA_d50[0], topA_d50[1], topA_d50[2],
                    topB[0], topB[1], topB[2], topB_d50[0], topB_d50[1], topB_d50[2],
                    deTop, ptsTop));

            // 總分 (10 項 * 10 = 100 滿分)
            sbNoFlip.append(String.format(Locale.US, "Grand total: %d / 100\n", totalPoints));
            final String message = sbNoFlip.toString();
            final int finalScore = totalPoints;

            runOnUiThread(() -> {
                // 內容 TextView（可滑動）
                TextView contentTv = new TextView(MainActivity.this);
                contentTv.setText(message);
                contentTv.setTextSize(14f);
                contentTv.setTextIsSelectable(true);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                contentTv.setPadding(pad, pad, pad, pad);

                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(contentTv, new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

                // 分數 TextView（單獨顯示並著色）
                TextView scoreTv = new TextView(MainActivity.this);
                scoreTv.setText(String.format(Locale.US, "Score: %d / 100", finalScore));
                scoreTv.setTextSize(18f);
                scoreTv.setTypeface(null, android.graphics.Typeface.BOLD);
                scoreTv.setPadding(pad, pad / 2, pad, pad);

                int color;
                if (finalScore > 88) {
                    color = Color.parseColor("#4CAF50"); // green
                } else if (finalScore >= 78 && finalScore <= 88) {
                    color = Color.parseColor("#FFC107"); // yellow/amber
                } else {
                    color = Color.parseColor("#F44336"); // red
                }
                scoreTv.setTextColor(color);

                LinearLayout container = new LinearLayout(MainActivity.this);
                container.setOrientation(LinearLayout.VERTICAL);
                container.addView(sv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                container.addView(scoreTv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("No‑Flip ΔE & Labs")
                        .setView(container)
                        .setPositiveButton("OK", (d, w) -> d.dismiss())
                        .show();
            });
            return; // 不執行後續 flip-mode 處理
        }


        if (bmpA == null || bmpB == null) return;

        int[][] points = gridPointsFromSize(bmpB.getWidth(), bmpB.getHeight());
        final int regionHalf = 20; // 30x30

        StringBuilder sb = new StringBuilder();
        sb.append("9 positions averaged from two captures (A + B → avg):\n");
        sb.append("Mode: ").append(secondCaptureFlip ? "Flip (180° pairing)" : "No-flip (same positions)").append("\n\n");

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

    // 新增到 MainActivity.java（或替換原先固定 points 陣列的地方）

    private int[][] gridPointsFromSize(int w, int h) {
        // 每個 index (0..8) 的 x 與 y 百分比（對應你要求的排列）
        double[] px = {0.18, 0.18, 0.18, 0.50, 0.50, 0.50, 0.82, 0.82, 0.82};
        double[] py = {0.18, 0.50, 0.85, 0.18, 0.50, 0.82, 0.18, 0.50, 0.82};

        int[][] pts = new int[9][2];
        for (int i = 0; i < 9; i++) {
            int cx = (int) Math.round(px[i] * w);
            int cy = (int) Math.round(py[i] * h);
            // 邊界保護
            cx = Math.max(0, Math.min(w - 1, cx));
            cy = Math.max(0, Math.min(h - 1, cy));
            pts[i][0] = cx;
            pts[i][1] = cy;
        }
        return pts;
    }



    // 輔助：對 avgList 做以 brightest white 為參考的正規化，並顯示第二個對話視窗
    // java
    // 替換原有的 showNormalizedResultsDialog 方法為下列實作（同時儲存每個位置的 Lab(D50) 並在按 OK 後顯示 Delta-E 2000 結果）
    // 更新：在 showNormalizedResultsDialog 內呼叫 showDeltaEResultsDialog 並傳入白點 Lab(D50)
// java
// 修改後的 showNormalizedResultsDialog 與 showDeltaEResultsDialog

    private void showNormalizedResultsDialog(int[][] avgList, int[] topAvg) {
        if (avgList == null || avgList.length == 0) return;

        // 目標白色（B = 236）
        final double targetR = 233.0;
        final double targetG = 231.0;
        final double targetB = 236.0;

        double[] scale = new double[3];
        scale[0] = topAvg[0] > 0 ? (targetR / (double) topAvg[0]) : 1.0;
        scale[1] = topAvg[1] > 0 ? (targetG / (double) topAvg[1]) : 1.0;
        scale[2] = topAvg[2] > 0 ? (targetB / (double) topAvg[2]) : 1.0;

        StringBuilder nsb = new StringBuilder();
        nsb.append("Normalized results (white mapped to ");
        nsb.append(String.format(Locale.US, "R=%.0f G=%.0f B=%.0f", targetR, targetG, targetB));
        nsb.append(")\n\n");

        // 存放每個位置正規化後的 Lab(D50)
        double[][] normalizedLabs = new double[avgList.length][3];

        // 同時建立未正規化 (raw) 的 Lab(D50)
        double[][] originalLabs = new double[avgList.length][3];

        for (int i = 0; i < avgList.length; i++) {
            int orR = avgList[i][0];
            int orG = avgList[i][1];
            int orB = avgList[i][2];

            // 原始 (未正規化) -> Lab(D50)
            double[] labD65_orig = displayP3RgbToLab(orR, orG, orB);
            double[] labD50_orig = labD65ToLabD50(labD65_orig[0], labD65_orig[1], labD65_orig[2]);
            originalLabs[i][0] = labD50_orig[0];
            originalLabs[i][1] = labD50_orig[1];
            originalLabs[i][2] = labD50_orig[2];

            // 正規化 RGB
            int nR = (int) Math.round(orR * scale[0]);
            int nG = (int) Math.round(orG * scale[1]);
            int nB = (int) Math.round(orB * scale[2]);

            nR = Math.max(0, Math.min(255, nR));
            nG = Math.max(0, Math.min(255, nG));
            nB = Math.max(0, Math.min(255, nB));

            double[] labD65 = displayP3RgbToLab(nR, nG, nB);
            double[] labD50 = labD65ToLabD50(labD65[0], labD65[1], labD65[2]);

            normalizedLabs[i][0] = labD50[0];
            normalizedLabs[i][1] = labD50[1];
            normalizedLabs[i][2] = labD50[2];

            nsb.append(String.format(Locale.US,
                    "pos %d -> R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                    i, nR, nG, nB, labD50[0], labD50[1], labD50[2]));
        }

        // 顯示正規化後的白參考值（經同樣縮放）
        int whiteR = (int) Math.round(topAvg[0] * scale[0]);
        int whiteG = (int) Math.round(topAvg[1] * scale[1]);
        int whiteB = (int) Math.round(topAvg[2] * scale[2]);
        whiteR = Math.max(0, Math.min(255, whiteR));
        whiteG = Math.max(0, Math.min(255, whiteG));
        whiteB = Math.max(0, Math.min(255, whiteB));

        double[] whiteLabD65 = displayP3RgbToLab(whiteR, whiteG, whiteB);
        double[] whiteLabD50 = labD65ToLabD50(whiteLabD65[0], whiteLabD65[1], whiteLabD65[2]);

        nsb.append("\nNormalized white (after scaling):\n");
        nsb.append(String.format(Locale.US,
                "R=%d G=%d B=%d  →  L(D50)=%.1f a(D50)=%.1f b(D50)=%.1f\n",
                whiteR, whiteG, whiteB, whiteLabD50[0], whiteLabD50[1], whiteLabD50[2]));

        // 同時產生原始白點的 Lab(D50)（未經縮放）
        double[] origWhiteLabD65 = displayP3RgbToLab(topAvg[0], topAvg[1], topAvg[2]);
        double[] origWhiteLabD50 = labD65ToLabD50(origWhiteLabD65[0], origWhiteLabD65[1], origWhiteLabD65[2]);

        final double[][] finalNormalizedLabs = normalizedLabs;
        final double[] finalWhiteLabD50 = whiteLabD50;
        final double[][] finalOriginalLabs = originalLabs;
        final double[] finalOrigWhiteLabD50 = origWhiteLabD50;

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Normalized RGB & Lab (D50)")
                    .setMessage(nsb.toString())
                    .setPositiveButton("OK", (d, w) -> {
                        d.dismiss();
                        // 按下 OK 後顯示第三個對話視窗（Delta-E 2000、50% CMYK TV）
                        showDeltaEResultsDialog(finalNormalizedLabs, finalWhiteLabD50, finalOriginalLabs, finalOrigWhiteLabD50);
                    })
                    .show();
        });
    }

    // 修改：將 showDeltaEResultsDialog 內的 AlertDialog 的 OK 行為，改為在按下後呼叫 showScoreDialog
// 若原本已定義 showDeltaEResultsDialog，請以此版本替換之
    private void showDeltaEResultsDialog(double[][] normalizedLabs, double[] whiteLabD50, double[][] originalLabs, double[] origWhiteLabD50) {
        if (normalizedLabs == null) return;

        double[] cyanRef = new double[]{56.0, -27.0, -46.0};
        double[] magRef  = new double[]{48.0,  72.0,  -3.0};
        double[] yelRef  = new double[]{89.0,  -1.0,  96.0};
        double[] blkRef  = new double[]{16.0,  0.1,   0.1};

        StringBuilder sb = new StringBuilder();
        sb.append("Delta-E 2000 results (normalized):\n\n");

        double dePos2 = deltaE2000(safeLabAt(normalizedLabs, 2), cyanRef);
        double dePos5 = deltaE2000(safeLabAt(normalizedLabs, 5), magRef);
        double dePos0 = deltaE2000(safeLabAt(normalizedLabs, 0), yelRef);
        double dePos8 = deltaE2000(safeLabAt(normalizedLabs, 8), blkRef);

        sb.append(String.format(Locale.US, "pos 2 → Cyan    : ΔE00 = %.2f\n", dePos2));
        sb.append(String.format(Locale.US, "pos 5 → Magenta : ΔE00 = %.2f\n", dePos5));
        sb.append(String.format(Locale.US, "pos 0 → Yellow  : ΔE00 = %.2f\n", dePos0));
        sb.append(String.format(Locale.US, "pos 8 → Black   : ΔE00 = %.2f\n", dePos8));

        double[] tvNorm = computeCmyk50TvFromLabs(normalizedLabs, whiteLabD50);
        sb.append("\nColorimetric Tone Value (50% CMYK) [normalized]:\n");
        sb.append(String.format(Locale.US, "pos 1 (C 50%%) : TV = %.2f %%\n", tvNorm[0]));
        sb.append(String.format(Locale.US, "pos 4 (M 50%%) : TV = %.2f %%\n", tvNorm[1]));
        sb.append(String.format(Locale.US, "pos 3 (Y 50%%) : TV = %.2f %%\n", tvNorm[2]));
        sb.append(String.format(Locale.US, "pos 7 (K 50%%) : TV = %.2f %%\n", tvNorm[3]));

        // --- 新增區塊：顯示 pos6,pos7,pos8 的 Lab 轉為 D65 (適用 DisplayP3/D65) ---
        double[] lab6_d65 = labD50ToLabD65(safeLabAt(normalizedLabs, 6));
        double[] lab7_d65 = labD50ToLabD65(safeLabAt(normalizedLabs, 7));
        double[] lab8_d65 = labD50ToLabD65(safeLabAt(normalizedLabs, 8));

        // Unnormalized block
        sb.append("\nUnnormalized (raw) results:\n\n");

        double dePos2_u = deltaE2000(safeLabAt(originalLabs, 2), cyanRef);
        double dePos5_u = deltaE2000(safeLabAt(originalLabs, 5), magRef);
        double dePos0_u = deltaE2000(safeLabAt(originalLabs, 0), yelRef);
        double dePos8_u = deltaE2000(safeLabAt(originalLabs, 8), blkRef);

        sb.append("\nPositions 6,7,8 as Lab (D65) - suitable for P3/D65:\n");
        sb.append(String.format(Locale.US, "pos 6 : L=%.2f a=%.2f b=%.2f\n", lab6_d65[0], lab6_d65[1], lab6_d65[2]));
        sb.append(String.format(Locale.US, "pos 7 : L=%.2f a=%.2f b=%.2f\n", lab7_d65[0], lab7_d65[1], lab7_d65[2]));
        sb.append(String.format(Locale.US, "pos 8 : L=%.2f a=%.2f b=%.2f\n", lab8_d65[0], lab8_d65[1], lab8_d65[2]));
        //

        sb.append(String.format(Locale.US, "pos 2 → Cyan    : ΔE00 = %.2f\n", dePos2_u));
        sb.append(String.format(Locale.US, "pos 5 → Magenta : ΔE00 = %.2f\n", dePos5_u));
        sb.append(String.format(Locale.US, "pos 0 → Yellow  : ΔE00 = %.2f\n", dePos0_u));
        sb.append(String.format(Locale.US, "pos 8 → Black   : ΔE00 = %.2f\n", dePos8_u));

        double[] tvRaw = computeCmyk50TvFromLabs(originalLabs, origWhiteLabD50);
        sb.append("\nColorimetric Tone Value (50% CMYK) [raw]:\n");
        sb.append(String.format(Locale.US, "pos 1 (C 50%%) : TV = %.2f %%\n", tvRaw[0]));
        sb.append(String.format(Locale.US, "pos 4 (M 50%%) : TV = %.2f %%\n", tvRaw[1]));
        sb.append(String.format(Locale.US, "pos 3 (Y 50%%) : TV = %.2f %%\n", tvRaw[2]));
        sb.append(String.format(Locale.US, "pos 7 (K 50%%) : TV = %.2f %%\n", tvRaw[3]));

        final double f_dePos2 = dePos2;
        final double f_dePos5 = dePos5;
        final double f_dePos0 = dePos0;
        final double f_dePos8 = dePos8;

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Delta‑E 2000 & TV results")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", (d, w) -> {
                        d.dismiss();
                        // 按下 OK 後顯示第四個彈窗：分數
                       // showScoreDialog(f_dePos2, f_dePos5, f_dePos0, f_dePos8);
                        showScoreDialog(f_dePos2, f_dePos5, f_dePos0, f_dePos8, tvNorm[0], tvNorm[1], tvNorm[2], tvNorm[3],
                                normalizedLabs);
                    })
                    .show();
        });
    }

    // 新增：Lab(D50) -> 相對 Y (0..1)
    private double labToRelativeY(double[] labD50) {
        if (labD50 == null || labD50.length < 3) return 0.0;
        double L = labD50[0];
        double a = labD50[1];
        double b = labD50[2];
        double fy = (L + 16.0) / 116.0;
        // 使用 labFinvSafe，Yn = 1，因此 labFinvSafe(fy) 就是相對 Y
        double yr = labFinvSafe(fy);
        if (yr < 0.0) yr = 0.0;
        return yr; // 相對 Y (0..1)
    }

    // 新增：計算並顯示 Delta-E 2000（pos2→Cyan, pos5→Magenta, pos0→Yellow, pos8→Black）
// 改寫過的 showDeltaEResultsDialog，使用 safeLabAt 而非 IntFunction.apply
// 更新：計算並顯示 Delta-E 2000（pos2→Cyan, pos5→Magenta, pos0→Yellow, pos8→Black）
// 並顯示 50% CMYK 的色度學 TV（pos1 = C50, pos4 = M50, pos3 = Y50, pos7 = K50）
// 使用 TV = 100 * (1 - Y_patch / Y_white)
    private void showDeltaEResultsDialog(double[][] normalizedLabs, double[] whiteLabD50) {
        if (normalizedLabs == null) return;

        // 參考 Lab (D50)
        double[] cyanRef = new double[]{56.0, -37.0, -50.0};
        double[] magRef  = new double[]{48.0,  75.0,  -4.0};
        double[] yelRef  = new double[]{89.0,  -4.0,  93.0};
        double[] blkRef  = new double[]{16.0,  0.1,   0.1};

        StringBuilder sb = new StringBuilder();
        sb.append("Delta-E 2000 results:\n\n");

        double dePos2 = deltaE2000(safeLabAt(normalizedLabs, 2), cyanRef);
        double dePos5 = deltaE2000(safeLabAt(normalizedLabs, 5), magRef);
        double dePos0 = deltaE2000(safeLabAt(normalizedLabs, 0), yelRef);
        double dePos8 = deltaE2000(safeLabAt(normalizedLabs, 8), blkRef);

        sb.append(String.format(Locale.US, "pos 2 → Cyan    : ΔE00 = %.2f\n", dePos2));
        sb.append(String.format(Locale.US, "pos 5 → Magenta : ΔE00 = %.2f\n", dePos5));
        sb.append(String.format(Locale.US, "pos 0 → Yellow  : ΔE00 = %.2f\n", dePos0));
        sb.append(String.format(Locale.US, "pos 8 → Black   : ΔE00 = %.2f\n", dePos8));

        // 計算 50% CMYK 的 TV（colorimetric）
        sb.append("\nColorimetric Tone Value (50% CMYK) :\n");

        // 白點相對 Y
        double[] whiteXYZ = labD50ToXyz(whiteLabD50);
        double PX = whiteXYZ[0], PY = whiteXYZ[1], PZ = whiteXYZ[2];

        // 取得 solid 與 50% patch 的 XYZ（索引對應：solid C=pos2, solid M=pos5, solid Y=pos0, solid K=pos8；50%: pos1,pos4,pos3,pos7）
        double[] cSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 2));
        double[] mSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 5));
        double[] ySolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 0));
        double[] kSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 8));



        double[] c50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 1));
        double[] m50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 4));
        double[] y50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 3));
        double[] k50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 7));


/*
// C: 使用 X 和 Z 混合項 (係數 0.55)
        double denomC = (PX - 0.55 * PZ) - (cSolidXYZ[0] - 0.55 * cSolidXYZ[2]);
        double numerC = (PX - 0.55 * PZ) - (c50XYZ[0] - 0.55 * c50XYZ[2]);
        double tvC = denomC == 0.0 ? 0.0 : (numerC / denomC) * 100.0;
 */
        double denomC = PY - cSolidXYZ[1];
        double numerC = PY - c50XYZ[1];
        double tvC = denomC == 0.0 ? 0.0 : (numerC / denomC) * 100.0;

// M: 使用 Y 通道
        double denomM = PY - mSolidXYZ[1];
        double numerM = PY - m50XYZ[1];
        double tvM = denomM == 0.0 ? 0.0 : (numerM / denomM) * 100.0;

// Y: 使用 Z 通道
        double denomY = PZ - ySolidXYZ[2];
        double numerY = PZ - y50XYZ[2];
        double tvY = denomY == 0.0 ? 0.0 : (numerY / denomY) * 100.0;

// K: 使用 Y 通道（與 M 相同）
        double denomK = PY - kSolidXYZ[2];
        double numerK = PY - k50XYZ[1];
        double tvK = denomK == 0.0 ? 0.0 : (numerK / denomK) * 100.0;

        // 限制在 0..100 範圍以顯示
        tvC = Math.max(0.0, Math.min(100.0, tvC));
        tvM = Math.max(0.0, Math.min(100.0, tvM));
        tvY = Math.max(0.0, Math.min(100.0, tvY));
        tvK = Math.max(0.0, Math.min(100.0, tvK));

        sb.append(String.format(Locale.US, "pos 1 (C 50%%) : TV = %.2f %%\n", tvC));
        sb.append(String.format(Locale.US, "pos 4 (M 50%%) : TV = %.2f %%\n", tvM));
        sb.append(String.format(Locale.US, "pos 3 (Y 50%%) : TV = %.2f %%\n", tvY));
        sb.append(String.format(Locale.US, "pos 7 (K 50%%) : TV = %.2f %%\n", tvK));

        runOnUiThread(() -> {
            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Delta-E 2000  & 50% CMYK TV")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }

    // 新增：Delta-E 2000 實作（接受兩個 Lab {L,a,b}，回傳 ΔE00）
    private double deltaE2000(double[] lab1, double[] lab2) {
        // 參考實作來源：CIEDE2000 演算法
        double L1 = lab1[0], a1 = lab1[1], b1 = lab1[2];
        double L2 = lab2[0], a2 = lab2[1], b2 = lab2[2];

        double avgLp = (L1 + L2) / 2.0;
        double C1 = Math.hypot(a1, b1);
        double C2 = Math.hypot(a2, b2);
        double avgC = (C1 + C2) / 2.0;

        double pow7 = Math.pow(avgC, 7);
        double G = 0.5 * (1 - Math.sqrt( pow7 / (pow7 + Math.pow(25.0, 7)) ));

        double a1p = a1 * (1 + G);
        double a2p = a2 * (1 + G);

        double C1p = Math.hypot(a1p, b1);
        double C2p = Math.hypot(a2p, b2);
        double avgCp = (C1p + C2p) / 2.0;

        double h1p = Math.atan2(b1, a1p);
        if (h1p < 0) h1p += 2.0 * Math.PI;
        double h2p = Math.atan2(b2, a2p);
        if (h2p < 0) h2p += 2.0 * Math.PI;

        double dLp = L2 - L1;
        double dCp = C2p - C1p;

        double dhp;
        if (C1p * C2p == 0) {
            dhp = 0;
        } else {
            double diff = h2p - h1p;
            if (Math.abs(diff) <= Math.PI) {
                dhp = diff;
            } else if (diff > Math.PI) {
                dhp = diff - 2.0 * Math.PI;
            } else {
                dhp = diff + 2.0 * Math.PI;
            }
        }
        double dHp = 2.0 * Math.sqrt(Math.max(0.0, C1p * C2p)) * Math.sin(dhp / 2.0);

        double avgHp;
        if (C1p * C2p == 0) {
            avgHp = h1p + h2p;
        } else {
            double diff = Math.abs(h1p - h2p);
            if (diff > Math.PI) {
                avgHp = (h1p + h2p + 2.0 * Math.PI) / 2.0;
            } else {
                avgHp = (h1p + h2p) / 2.0;
            }
        }

        double avgHpDeg = Math.toDegrees(avgHp);

        double T = 1.0
                - 0.17 * Math.cos(Math.toRadians(avgHpDeg - 30.0))
                + 0.24 * Math.cos(Math.toRadians(2.0 * avgHpDeg))
                + 0.32 * Math.cos(Math.toRadians(3.0 * avgHpDeg + 6.0))
                - 0.20 * Math.cos(Math.toRadians(4.0 * avgHpDeg - 63.0));

        double deltaTheta = 30.0 * Math.exp(- Math.pow((avgHpDeg - 275.0) / 25.0, 2.0));
        double Rc = 2.0 * Math.sqrt( Math.pow(avgCp, 7.0) / ( Math.pow(avgCp, 7.0) + Math.pow(25.0, 7.0) ) );
        double Rt = - Math.sin(Math.toRadians(2.0 * deltaTheta)) * Rc;

        double Sl = 1.0 + ( (0.015 * Math.pow(avgLp - 50.0, 2.0)) / Math.sqrt(20.0 + Math.pow(avgLp - 50.0, 2.0)) );
        double Sc = 1.0 + 0.045 * avgCp;
        double Sh = 1.0 + 0.015 * avgCp * T;

        double kl = 1.0, kc = 1.0, kh = 1.0;

        double termL = dLp / (Sl * kl);
        double termC = dCp / (Sc * kc);
        double termH = dHp / (Sh * kh);

        double deltaE = Math.sqrt( termL * termL + termC * termC + termH * termH + Rt * termC * termH );
        return deltaE;
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


    // 安全取用 normalizedLabs 的 helper（避免使用 java.util.function.*，支援 minSdk 21）
    private double[] safeLabAt(double[][] normalizedLabs, int idx) {
        if (normalizedLabs == null) return new double[]{0.0, 0.0, 0.0};
        if (idx < 0 || idx >= normalizedLabs.length) return new double[]{0.0, 0.0, 0.0};
        return normalizedLabs[idx];
    }

    // Lab(D50) -> XYZ (relative, Yn = 1)
    private double[] labD50ToXyz(double[] lab) {
        if (lab == null || lab.length < 3) return new double[]{0.0, 0.0, 0.0};
        final double Xn = 0.96422;
        final double Yn = 1.0;
        final double Zn = 0.82521;
        double L = lab[0], a = lab[1], b = lab[2];
        double fy = (L + 16.0) / 116.0;
        double fx = fy + (a / 500.0);
        double fz = fy - (b / 200.0);
        double xr = labFinvSafe(fx);
        double yr = labFinvSafe(fy);
        double zr = labFinvSafe(fz);
        double X = xr * Xn;
        double Y = yr * Yn;
        double Z = zr * Zn;
        return new double[]{X, Y, Z};
    }

    // 計算 50% CMYK 的 colorimetric TV（依你提供的 PHP 公式）
    private double[] computeCmyk50TvFromLabs(double[][] normalizedLabs, double[] whiteLabD50) {
        // 索引映射：solid C=pos2, solid M=pos5, solid Y=pos0, solid K=pos8
        // 50% patches: C50=pos1, M50=pos4, Y50=pos3, K50=pos7
        double[] whiteXYZ = labD50ToXyz(whiteLabD50);

        double[] cSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 2));
        double[] mSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 5));
        double[] ySolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 0));
        double[] kSolidXYZ  = labD50ToXyz(safeLabAt(normalizedLabs, 8));

        double[] c50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 1));
        double[] m50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 4));
        double[] y50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 3));
        double[] k50XYZ = labD50ToXyz(safeLabAt(normalizedLabs, 7));

        double PX = whiteXYZ[0], PY = whiteXYZ[1], PZ = whiteXYZ[2];

        // C: 使用 X 和 Z 混合項 (係數 0.55)
        double denomC = (PX - 0.55 * PZ) - (cSolidXYZ[0] - 0.55 * cSolidXYZ[2]);
        double numerC = (PX - 0.55 * PZ) - (c50XYZ[0] - 0.55 * c50XYZ[2]);
        double tvC = denomC == 0.0 ? 0.0 : (numerC / denomC) * 100.0;

        // M: 使用 Y 通道
        double denomM = PY - mSolidXYZ[1];
        double numerM = PY - m50XYZ[1];
        double tvM = denomM == 0.0 ? 0.0 : (numerM / denomM) * 100.0;

        // Y: 使用 Z 通道
        double denomY = PZ - ySolidXYZ[2];
        double numerY = PZ - y50XYZ[2];
        double tvY = denomY == 0.0 ? 0.0 : (numerY / denomY) * 100.0;

        // K: 使用 Y 通道（此處採用 kY 作為分母，對應 M 的做法）
        double denomK = PY - kSolidXYZ[1];
        double numerK = PY - k50XYZ[1];
        double tvK = denomK == 0.0 ? 0.0 : (numerK / denomK) * 100.0;

        // 限制 0..100 範圍並回傳順序 {C, M, Y, K}
        tvC = Math.max(0.0, Math.min(100.0, tvC));
        tvM = Math.max(0.0, Math.min(100.0, tvM));
        tvY = Math.max(0.0, Math.min(100.0, tvY));
        tvK = Math.max(0.0, Math.min(100.0, tvK));

        return new double[]{tvC, tvM, tvY, tvK};
    }

    // 新增：依 ΔE00 計算單一 patch 分數
    private int scoreForDelta(double de) {
        if (de <= 3.5) return 5;
        if (de <= 4.0) return 4;
        if (de <= 5.0) return 3;
        if (de <= 6.0) return 2;
        return 0;
    }

    // 依 TV 絕對差異計分（new rules: <3 ->10, (3,4]->7, (4,5]->5, (5,6]->3, (6,7]->1, >7->0）
    private int scoreForTvDiff(double diff) {
        double d = Math.abs(diff);
        if (d < 3.0) return 10;
        if (d <= 4.0) return 7;
        if (d <= 5.0) return 5;
        if (d <= 6.0) return 3;
        if (d <= 7.0) return 1;
        return 0;
    }

    // 顯示第四個彈窗：列出每個 patch 的 ΔE、ΔE 分數、TV、TV 差異與 TV 分數，以及小計與總分
// java
    private void showScoreDialog(double dePos2, double dePos5, double dePos0, double dePos8,
                                 double tvC, double tvM, double tvY, double tvK,
                                 double[][] normalizedLabs) {

        StringBuilder sb = new StringBuilder();

        // --- 新增：計算 pos6 的 L 與 chroma score（gray patch） ---
        double[] lab6 = safeLabAt(normalizedLabs, 6); // Lab(D50)
        double capL6 = lab6[0];
        double capA6 = lab6[1];
        double capB6 = lab6[2];

        double deltaL6 = Math.abs(POS6_REF_L - capL6);
        int lScore6 = scoreForL(deltaL6);

        double deltaCh6 = Math.hypot(POS6_REF_A - capA6, POS6_REF_B - capB6); // sqrt((da)^2 + (db)^2)
        int chScore6 = scoreForGrayCh(deltaCh6);

        //final int extraPos6 = 2; // 額外 +2 分

        sb.append("\npos 6 (gray) scoring:\n");
        sb.append(String.format(Locale.US,
                "  L_ref=%.0f  L_capture=%.2f  ΔL=%.2f  -> %d pts\n",
                POS6_REF_L, capL6, deltaL6, lScore6));
        sb.append(String.format(Locale.US,
                "  a_ref=%.2f  a_capture=%.2f  b_ref=%.2f  b_capture=%.2f  Δch=%.2f  -> %d pts\n",
                POS6_REF_A, capA6, POS6_REF_B, capB6, deltaCh6, chScore6));
        //sb.append(String.format(Locale.US, "  Extra fixed pts for pos6: +%d\n", extraPos6));
        // --- 新增區塊結束 ---

        // 參考 TV (C, M, Y, K)
        double[] refTv = new double[]{70.0, 67.0, 63.0, 68.0};

        final int s2 = scoreForDelta(dePos2);
        final int s5 = scoreForDelta(dePos5);
        final int s0 = scoreForDelta(dePos0);
        final int s8 = scoreForDelta(dePos8);
        int deTotal = s2 + s5 + s0 + s8; // max 20

        double diffC = tvC - refTv[0];
        double diffM = tvM - refTv[1];
        double diffY = tvY - refTv[2];
        double diffK = tvK - refTv[3];

        final int tvsC = scoreForTvDiff(diffC);
        final int tvsM = scoreForTvDiff(diffM);
        final int tvsY = scoreForTvDiff(diffY);
        final int tvsK = scoreForTvDiff(diffK);
        int tvTotal = tvsC + tvsM + tvsY + tvsK; // max 40

        int pos6Total = lScore6 + chScore6 ;
        int grandTotal = deTotal + tvTotal + pos6Total;
        double score = (double) grandTotal / 80 * 100;

        sb.append("\nColor ΔE00 scores (pos2,pos5,pos0,pos8):\n\n");
        sb.append(String.format(Locale.US, "pos 2 → Cyan    : ΔE00 = %.2f  -> %d pts\n", dePos2, s2));
        sb.append(String.format(Locale.US, "pos 5 → Magenta : ΔE00 = %.2f  -> %d pts\n", dePos5, s5));
        sb.append(String.format(Locale.US, "pos 0 → Yellow  : ΔE00 = %.2f  -> %d pts\n", dePos0, s0));
        sb.append(String.format(Locale.US, "pos 8 → Black   : ΔE00 = %.2f  -> %d pts\n", dePos8, s8));
        sb.append(String.format(Locale.US, "\nΔE subtotal: %d / 20\n\n", deTotal));

        sb.append("Colorimetric TV comparison (pos1,pos4,pos3,pos7):\n\n");
        sb.append(String.format(Locale.US, "pos 1 (C50) : TV=%.2f  ref=%.1f  Δ=%.2f  -> %d pts\n", tvC, refTv[0], diffC, tvsC));
        sb.append(String.format(Locale.US, "pos 4 (M50) : TV=%.2f  ref=%.1f  Δ=%.2f  -> %d pts\n", tvM, refTv[1], diffM, tvsM));
        sb.append(String.format(Locale.US, "pos 3 (Y50) : TV=%.2f  ref=%.1f  Δ=%.2f  -> %d pts\n", tvY, refTv[2], diffY, tvsY));
        sb.append(String.format(Locale.US, "pos 7 (K50) : TV=%.2f  ref=%.1f  Δ=%.2f  -> %d pts\n", tvK, refTv[3], diffK, tvsK));
        sb.append(String.format(Locale.US, "\nTV subtotal: %d / 40\n\n", tvTotal));

        sb.append(String.format(Locale.US, "Gray subtotal: %d /20 (L=%d + ch=%d )\n", pos6Total, lScore6, chScore6));
        sb.append(String.format(Locale.US, "\nGrand total: %d /80\n", grandTotal));



        sb.append(String.format(Locale.US, "\nScore: %.2f \n", score));
        final String message = sb.toString();
        final double finalScore = score;

        runOnUiThread(() -> {
            // 內容 TextView（可滑動）
            TextView contentTv = new TextView(MainActivity.this);
            contentTv.setText(message);
            contentTv.setTextSize(14f);
            contentTv.setTextIsSelectable(true);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            contentTv.setPadding(pad, pad, pad, pad);

            ScrollView sv = new ScrollView(MainActivity.this);
            sv.addView(contentTv, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

            // 分數 TextView（單獨顯示並著色）
            TextView scoreTv = new TextView(MainActivity.this);
            scoreTv.setText(String.format(Locale.US, "Score: %.2f", finalScore));
            scoreTv.setTextSize(18f);
            scoreTv.setTypeface(null, android.graphics.Typeface.BOLD);
            scoreTv.setPadding(pad, pad / 2, pad, pad);

            int color;
            if (finalScore > 80.0) {
                color = Color.parseColor("#4CAF50"); // green
            } else if (finalScore >= 70.0 && finalScore <= 80.0) {
                color = Color.parseColor("#FFC107"); // yellow/amber
            } else if (finalScore < 70.0) {
                color = Color.parseColor("#F44336"); // red
            } else {
                color = Color.RED;
            }
            scoreTv.setTextColor(color);

            // 組合 layout
            LinearLayout container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            container.addView(scoreTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Patch scoring")
                    .setView(container)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }

    // 新增：Lab(D50) -> Lab(D65)（使用 Bradford 適配從 D50 -> D65）
    private double[] labD50ToLabD65(double[] labD50) {
        if (labD50 == null || labD50.length < 3) return new double[]{0.0, 0.0, 0.0};

        // Lab(D50) -> XYZ(D50)
        double[] xyzD50 = labD50ToXyz(labD50); // 已存在的方法，返回相對 XYZ (Yn = 1)

        // Bradford matrices
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

        // White points
        double[] whiteD50 = new double[]{0.96422, 1.0, 0.82521};
        double[] whiteD65 = new double[]{0.95047, 1.0, 1.08883};

        // cone responses
        double[] srcCone = mulMatVec(M, whiteD50);
        double[] dstCone = mulMatVec(M, whiteD65);

        // convert XYZ(D50) to cone
        double[] cone = mulMatVec(M, xyzD50);

        // scale from D50 -> D65 in cone space
        double[] scale = new double[3];
        for (int i = 0; i < 3; i++) {
            scale[i] = srcCone[i] == 0.0 ? 1.0 : (dstCone[i] / srcCone[i]);
        }
        double[] adaptedCone = new double[3];
        for (int i = 0; i < 3; i++) adaptedCone[i] = cone[i] * scale[i];

        // back to XYZ (D65)
        double[] adaptedXYZ = mulMatVec(M_INV, adaptedCone);
        double Xd65 = adaptedXYZ[0];
        double Yd65 = adaptedXYZ[1];
        double Zd65 = adaptedXYZ[2];

        // XYZ(D65) -> Lab(D65)
        final double Xn_D65 = 0.95047;
        final double Yn = 1.0;
        final double Zn_D65 = 1.08883;

        double fx = labF(Xd65 / Xn_D65);
        double fy = labF(Yd65 / Yn);
        double fz = labF(Zd65 / Zn_D65);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double b = 200.0 * (fy - fz);

        return new double[]{L, a, b};
    }

    // 新增：No‑Flip 模式用的 delta-E -> 分數對應（10..3..0）
    private int scoreForDeltaENoFlip(double de) {
        if (de < 1.0) return 10;
        if (de < 2.0) return 9;
        if (de < 3.0) return 8;
        if (de < 4.0) return 7;
        if (de < 5.0) return 6;
        if (de < 6.0) return 5;
        if (de < 7.0) return 4;
        if (de < 8.0) return 3;
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}