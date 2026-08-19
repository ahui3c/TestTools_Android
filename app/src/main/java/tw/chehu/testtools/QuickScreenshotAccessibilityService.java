package tw.chehu.testtools;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
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
                    this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, this::handleAction);
            overlay.show();
        } else {
            overlay.refreshOptions();
            overlay.setVisible(true);
        }
    }

    private boolean handleAction(int action) {
        switch (action) {
            case FloatingCaptureOverlay.ACTION_CAPTURE:
                return capture(false);
            case FloatingCaptureOverlay.ACTION_CAPTURE_SHARE:
                return capture(true);
            case FloatingCaptureOverlay.ACTION_BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case FloatingCaptureOverlay.ACTION_RECENTS:
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case FloatingCaptureOverlay.ACTION_HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case FloatingCaptureOverlay.ACTION_TESTTOOLS_HOME:
                return openActivity(new Intent(this, MainActivity.class));
            case FloatingCaptureOverlay.ACTION_QUICK_BACKUP:
                return openActivity(new Intent(this, tw.chehu.quicksend.MainActivity.class));
            case FloatingCaptureOverlay.ACTION_TOGGLE_TORCH:
                return handleQuickAction(SystemQuickActions.toggleTorch(this));
            case FloatingCaptureOverlay.ACTION_NOTIFICATIONS:
                boolean opened = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                if (!opened) Toast.makeText(this, "無法下拉通知面板", Toast.LENGTH_SHORT).show();
                return opened;
            case FloatingCaptureOverlay.ACTION_OPEN_SELECTED_APP:
                return handleQuickAction(SystemQuickActions.openSelectedApp(this));
            case FloatingCaptureOverlay.ACTION_TOGGLE_MUTE:
                return handleQuickAction(SystemQuickActions.toggleMute(this));
            default:
                return false;
        }
    }

    private boolean handleQuickAction(SystemQuickActions.Result result) {
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
        return result.success;
    }

    private boolean openActivity(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean capture(boolean shareAfterCapture) {
        if (capturing || overlay == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
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
                                Uri uri = ScreenshotStorage.savePng(
                                        QuickScreenshotAccessibilityService.this, bitmap);
                                finishCapture(shareAfterCapture ? "準備分享" : "已儲存",
                                        true, uri, shareAfterCapture);
                            } catch (Exception error) {
                                finishCapture("儲存失敗", false, null, false);
                            } finally {
                                bitmap.recycle();
                            }
                        });
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        finishCapture("此畫面無法擷取", false, null, false);
                    }
                }), 120);
        return true;
    }

    private void finishCapture(String message, boolean success) {
        finishCapture(message, success, null, false);
    }

    private void finishCapture(String message, boolean success, Uri uri, boolean share) {
        mainHandler.post(() -> {
            capturing = false;
            if (overlay != null) overlay.showResult(message, success);
            if (success && share && uri != null) {
                try { ScreenshotStorage.share(this, uri); }
                catch (RuntimeException error) {
                    if (overlay != null) overlay.showResult("已儲存，但無法開啟分享", false);
                }
            }
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
