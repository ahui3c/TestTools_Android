package tw.chehu.testtools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class FloatingCaptureOverlay {
    interface CaptureListener { void onCaptureRequested(); }

    static final String PREFS = "floating_screenshot";
    static final String KEY_ENABLED = "overlay_enabled";
    static final String KEY_SHOW_TIME = "show_time";
    static final String KEY_SHOW_BATTERY = "show_battery";
    static final String KEY_FLASH_FEEDBACK = "flash_feedback";
    static final String KEY_VIBRATE_FEEDBACK = "vibrate_feedback";
    static final String KEY_SOUND_FEEDBACK = "sound_feedback";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CaptureListener listener;
    private final TextView view;
    private final WindowManager.LayoutParams params;
    private final MediaActionSound captureSound;
    private final ToneGenerator confirmationTone;
    private int batteryPercent = -1;
    private boolean attached;
    private float downRawX;
    private float downRawY;
    private int downWindowX;
    private int downWindowY;
    private boolean moved;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateText(null);
            handler.postDelayed(this, 30_000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryPercent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            updateText(null);
        }
    };

    FloatingCaptureOverlay(Context context, int windowType, CaptureListener listener) {
        this.context = context;
        this.listener = listener;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        captureSound = createCaptureSound();
        confirmationTone = createConfirmationTone();
        view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setElevation(Ui.dp(context, 8));
        view.setPadding(Ui.dp(context, 12), Ui.dp(context, 9), Ui.dp(context, 12), Ui.dp(context, 9));
        view.setBackground(background("#E62563EB"));
        view.setOnTouchListener(this::onTouch);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = preferences.getInt(KEY_X, Ui.dp(context, 12));
        params.y = preferences.getInt(KEY_Y, Ui.dp(context, 180));
    }

    void show() {
        if (attached) return;
        windowManager.addView(view, params);
        attached = true;
        context.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.post(clockTick);
        updateText(null);
    }

    void remove() {
        if (!attached) return;
        handler.removeCallbacksAndMessages(null);
        try { context.unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
        try { windowManager.removeView(view); } catch (IllegalArgumentException ignored) {}
        if (captureSound != null) captureSound.release();
        if (confirmationTone != null) confirmationTone.release();
        attached = false;
    }

    void setVisible(boolean visible) {
        if (attached) view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    void showResult(String message, boolean success) {
        if (!attached) return;
        if (success) playSuccessFeedback();
        setVisible(true);
        view.setBackground(background(success ? "#E616A34A" : "#E6DC2626"));
        updateText(message);
        if (success) {
            view.setScaleX(0.82f);
            view.setScaleY(0.82f);
            view.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
        handler.postDelayed(() -> {
            view.setBackground(background("#E62563EB"));
            updateText(null);
        }, 900);
    }

    private void playSuccessFeedback() {
        if (preferences.getBoolean(KEY_FLASH_FEEDBACK, true)) showFlash();
        if (preferences.getBoolean(KEY_VIBRATE_FEEDBACK, false)) vibrate();
        if (preferences.getBoolean(KEY_SOUND_FEEDBACK, false)) playSound();
    }

    private void showFlash() {
        View flash = new View(context);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0.92f);
        WindowManager.LayoutParams flashParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                params.type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            windowManager.addView(flash, flashParams);
            flash.postDelayed(() -> flash.animate()
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction(() -> {
                        try { windowManager.removeView(flash); }
                        catch (IllegalArgumentException ignored) {}
                    }).start(), 65);
        } catch (RuntimeException ignored) {
            // 權限或視窗狀態改變時，略過閃光但仍保留其他成功回饋。
        }
    }

    private void vibrate() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] timing = {0, 70, 55, 115};
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                        timing, new int[]{0, 210, 0, 255}, -1));
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timing, -1));
            }
        }
    }

    private void playSound() {
        try {
            if (captureSound != null) captureSound.play(MediaActionSound.SHUTTER_CLICK);
            if (confirmationTone != null) {
                handler.postDelayed(() -> confirmationTone.startTone(
                        ToneGenerator.TONE_PROP_ACK, 150), 70);
            }
        } catch (RuntimeException ignored) {
            // 音訊服務不可用或系統靜音時不影響截圖結果。
        }
    }

    private MediaActionSound createCaptureSound() {
        try {
            MediaActionSound sound = new MediaActionSound();
            sound.load(MediaActionSound.SHUTTER_CLICK);
            return sound;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ToneGenerator createConfirmationTone() {
        try {
            return new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    void refreshOptions() {
        updateText(null);
    }

    private void updateText(String temporary) {
        if (temporary != null) {
            view.setText(temporary);
            return;
        }
        StringBuilder text = new StringBuilder("截圖");
        if (preferences.getBoolean(KEY_SHOW_TIME, true)) {
            text.append("  ").append(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
        if (preferences.getBoolean(KEY_SHOW_BATTERY, true)) {
            text.append("\n電量 ").append(batteryPercent < 0 ? "--" : batteryPercent).append('%');
        }
        view.setText(text.toString());
    }

    private boolean onTouch(View ignored, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downWindowX = params.x;
                downWindowY = params.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (Math.hypot(dx, dy) > Ui.dp(context, 8)) moved = true;
                params.x = downWindowX + Math.round(dx);
                params.y = downWindowY + Math.round(dy);
                if (attached) windowManager.updateViewLayout(view, params);
                return true;
            case MotionEvent.ACTION_UP:
                preferences.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply();
                if (!moved) listener.onCaptureRequested();
                return true;
            default:
                return false;
        }
    }

    private GradientDrawable background(String color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(Ui.dp(context, 18));
        drawable.setStroke(Ui.dp(context, 1), Color.parseColor("#66FFFFFF"));
        return drawable;
    }
}
