package tw.chehu.testtools;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

final class SystemQuickActions {
    static final String KEY_SELECTED_APP_PACKAGE = "selected_app_package";
    static final String KEY_APP_ACTION = "selected_app_action";
    static final String KEY_APP_ACTION_URI = "selected_app_action_uri";
    static final String DEFAULT_APP_ACTION = Intent.ACTION_VIEW;
    private static final String KEY_TORCH_ON = "torch_on";

    private static CameraManager cameraManager;
    private static String torchCameraId;
    private static boolean torchCallbackRegistered;
    private static boolean torchEnabled;

    private SystemQuickActions() {}

    static void initialize(Context context) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        ensureTorch(context.getApplicationContext());
    }

    static Result toggleTorch(Context context) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.failure("請先在浮動截圖設定中允許相機權限");
        }
        if (!ensureTorch(context.getApplicationContext()) || torchCameraId == null) {
            return Result.failure("此裝置找不到可控制的補光燈");
        }
        boolean target = !torchEnabled;
        try {
            cameraManager.setTorchMode(torchCameraId, target);
            torchEnabled = target;
            preferences(context).edit().putBoolean(KEY_TORCH_ON, target).apply();
            return Result.success(target ? "手電筒已開啟" : "手電筒已關閉");
        } catch (Exception error) {
            return Result.failure("無法切換手電筒");
        }
    }

    static Result openSelectedApp(Context context) {
        String packageName = preferences(context).getString(KEY_SELECTED_APP_PACKAGE, "");
        if (packageName == null || packageName.trim().isEmpty()) {
            return Result.failure("尚未指定要開啟的應用程式");
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return Result.failure("指定的應用程式目前無法開啟");
        return start(context, launch, "已開啟指定應用程式");
    }

    static Result runConfiguredAppAction(Context context) {
        SharedPreferences prefs = preferences(context);
        String packageName = value(prefs.getString(KEY_SELECTED_APP_PACKAGE, ""));
        String action = value(prefs.getString(KEY_APP_ACTION, DEFAULT_APP_ACTION));
        String uriText = value(prefs.getString(KEY_APP_ACTION_URI, ""));
        if (action.isEmpty() && uriText.isEmpty()) {
            return Result.failure("尚未設定程式動作或 Deep Link");
        }
        Intent intent;
        try {
            Uri uri = uriText.isEmpty() ? null : Uri.parse(uriText);
            intent = new Intent(action.isEmpty() ? Intent.ACTION_VIEW : action, uri);
        } catch (RuntimeException error) {
            return Result.failure("程式動作的 Deep Link 格式錯誤");
        }
        if (!packageName.isEmpty()) intent.setPackage(packageName);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return Result.failure("指定應用程式不支援這個動作");
        }
        return start(context, intent, "已執行指定程式動作");
    }

    static Result toggleMute(Context context) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null
                && !notificationManager.isNotificationPolicyAccessGranted()) {
            return Result.failure("請先允許測試工具箱存取勿擾模式");
        }
        AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return Result.failure("無法取得系統音效控制");
        try {
            boolean mute = audioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT;
            audioManager.setRingerMode(mute
                    ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL);
            return Result.success(mute ? "系統已切換為靜音" : "系統已恢復響鈴");
        } catch (SecurityException error) {
            return Result.failure("請先允許測試工具箱存取勿擾模式");
        } catch (RuntimeException error) {
            return Result.failure("無法切換系統靜音");
        }
    }

    private static synchronized boolean ensureTorch(Context context) {
        if (cameraManager == null) {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        }
        if (cameraManager == null) return false;
        if (torchCameraId == null) torchCameraId = findTorchCamera(cameraManager);
        if (torchCameraId == null) return false;
        if (!torchCallbackRegistered) {
            try {
                torchEnabled = preferences(context).getBoolean(KEY_TORCH_ON, false);
                cameraManager.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (!cameraId.equals(torchCameraId)) return;
                        torchEnabled = enabled;
                        preferences(context).edit().putBoolean(KEY_TORCH_ON, enabled).apply();
                    }

                    @Override
                    public void onTorchModeUnavailable(String cameraId) {
                        if (!cameraId.equals(torchCameraId)) return;
                        torchEnabled = false;
                        preferences(context).edit().putBoolean(KEY_TORCH_ON, false).apply();
                    }
                }, new Handler(Looper.getMainLooper()));
                torchCallbackRegistered = true;
            } catch (SecurityException ignored) {
                return false;
            }
        }
        return true;
    }

    private static String findTorchCamera(CameraManager manager) {
        String fallback = null;
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(flash)) continue;
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
                if (fallback == null) fallback = cameraId;
            }
        } catch (Exception ignored) {
            return null;
        }
        return fallback;
    }

    private static Result start(Context context, Intent intent, String successMessage) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
            return Result.success(successMessage);
        } catch (RuntimeException error) {
            return Result.failure("無法執行指定程式動作");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FloatingCaptureOverlay.PREFS, Context.MODE_PRIVATE);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Result {
        final boolean success;
        final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static Result success(String message) { return new Result(true, message); }
        static Result failure(String message) { return new Result(false, message); }
    }
}
