package tw.chehu.testtools;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuickScreenshotAccessibilityService extends AccessibilityService {
    private static QuickScreenshotAccessibilityService running;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private FloatingCaptureOverlay overlay;
    private boolean capturing;

    static void refreshRunningService() {
        if (running != null) running.refreshOverlay();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        refreshOverlay();
    }

    private void refreshOverlay() {
        boolean enabled = getSharedPreferences(FloatingCaptureOverlay.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingCaptureOverlay.KEY_ENABLED, false);
        if (!enabled) {
            if (overlay != null) overlay.remove();
            overlay = null;
            return;
        }
        if (overlay == null) {
            overlay = new FloatingCaptureOverlay(
                    this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, this::capture);
            overlay.show();
        } else {
            overlay.refreshOptions();
            overlay.setVisible(true);
        }
    }

    private void capture() {
        if (capturing || overlay == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        capturing = true;
        overlay.setVisible(false);
        mainHandler.postDelayed(() -> takeScreenshot(
                Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        HardwareBuffer buffer = screenshot.getHardwareBuffer();
                        ColorSpace colorSpace = screenshot.getColorSpace();
                        Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        Bitmap bitmap = hardwareBitmap == null ? null
                                : hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        if (hardwareBitmap != null) hardwareBitmap.recycle();
                        buffer.close();
                        if (bitmap == null) {
                            finishCapture("無法建立截圖", false);
                            return;
                        }
                        fileExecutor.execute(() -> {
                            try {
                                ScreenshotStorage.savePng(QuickScreenshotAccessibilityService.this, bitmap);
                                finishCapture("已儲存截圖", true);
                            } catch (Exception error) {
                                finishCapture("儲存失敗", false);
                            } finally {
                                bitmap.recycle();
                            }
                        });
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        finishCapture("此畫面無法擷取", false);
                    }
                }), 120);
    }

    private void finishCapture(String message, boolean success) {
        mainHandler.post(() -> {
            capturing = false;
            if (overlay != null) overlay.showResult(message, success);
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.remove();
        overlay = null;
        if (running == this) running = null;
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
