package tw.chehu.testtools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProjectionCaptureService extends Service {
    static final String ACTION_STOP = "tw.chehu.testtools.STOP_PROJECTION_CAPTURE";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL_ID = "floating_capture";
    private static final int NOTIFICATION_ID = 9105;
    private static ProjectionCaptureService running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private FloatingCaptureOverlay overlay;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int width;
    private int height;
    private volatile boolean captureRequested;
    private volatile boolean saving;

    static void refreshRunningService() {
        if (running != null && running.overlay != null) running.overlay.refreshOptions();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, createNotification());
        if (projection == null && intent != null) startProjection(intent);
        return START_NOT_STICKY;
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            stopSelf();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, mainHandler);

        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, mainHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "TestToolsCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, mainHandler);

        running = this;
        overlay = new FloatingCaptureOverlay(
                this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this::requestCapture);
        overlay.show();
    }

    private void requestCapture() {
        if (saving || overlay == null) return;
        overlay.setVisible(false);
        captureRequested = true;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !captureRequested || saving) return;
            captureRequested = false;
            saving = true;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = width + (rowStride - pixelStride * width) / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (bitmap != padded) padded.recycle();
            fileExecutor.execute(() -> {
                try {
                    ScreenshotStorage.savePng(ProjectionCaptureService.this, bitmap);
                    showResult("已儲存截圖", true);
                } catch (Exception error) {
                    showResult("儲存失敗", false);
                } finally {
                    bitmap.recycle();
                }
            });
        } catch (Exception error) {
            captureRequested = false;
            saving = false;
            showResult("截圖失敗", false);
        } finally {
            if (image != null) image.close();
        }
    }

    private void showResult(String message, boolean success) {
        mainHandler.post(() -> {
            saving = false;
            if (overlay != null) overlay.showResult(message, success);
        });
    }

    private Notification createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "浮動快速截圖", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stopIntent = new Intent(this, ProjectionCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TestTools 浮動快速截圖")
                .setContentText("點浮動按鈕即可儲存截圖")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        if (overlay != null) overlay.remove();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        overlay = null;
        virtualDisplay = null;
        imageReader = null;
        projection = null;
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
