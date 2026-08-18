package tw.chehu.testtools;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class FloatingCaptureOverlay {
    interface ActionListener { boolean onActionRequested(int action); }

    static final int ACTION_NONE = 0;
    static final int ACTION_CAPTURE = 1;
    static final int ACTION_CAPTURE_SHARE = 2;
    static final int ACTION_BACK = 3;
    static final int ACTION_RECENTS = 4;
    static final int ACTION_HOME = 5;
    static final int ACTION_TESTTOOLS_HOME = 6;
    static final int ACTION_QUICK_BACKUP = 7;
    static final int ACTION_HIDE_TO_EDGE = 8;
    static final int ACTION_TOGGLE_TORCH = 9;
    static final int ACTION_NOTIFICATIONS = 10;
    static final int ACTION_OPEN_SELECTED_APP = 11;
    static final int ACTION_RUN_APP_ACTION = 12;
    static final int ACTION_TOGGLE_MUTE = 13;
    static final String[] ACTION_LABELS = {
            "未指定", "抓圖儲存", "抓圖儲存並分享", "返回（Back）",
            "多工按鍵", "返回系統首頁", "開啟 TestTools 首頁", "開啟影音快速備份",
            "隱藏至螢幕側邊", "開關補光燈／手電筒", "下拉通知面板",
            "開啟指定應用程式", "執行指定程式動作", "快速切換系統靜音"
    };

    static final String PREFS = "floating_screenshot";
    static final String KEY_ENABLED = "overlay_enabled";
    static final String KEY_SHOW_TIME = "show_time";
    static final String KEY_SHOW_BATTERY = "show_battery";
    static final String KEY_FLASH_FEEDBACK = "flash_feedback";
    static final String KEY_VIBRATE_FEEDBACK = "vibrate_feedback";
    static final String KEY_SOUND_FEEDBACK = "sound_feedback";
    static final String KEY_BUTTON_COLOR = "button_color";
    static final String KEY_BUTTON_OPACITY = "button_opacity";
    static final String KEY_COMPACT_SIZE_PERCENT = "compact_size_percent";
    static final String KEY_ACTION_TAP = "action_tap";
    static final String KEY_ACTION_DOUBLE_TAP = "action_double_tap";
    static final String KEY_ACTION_SWIPE_UP = "action_swipe_up";
    static final String KEY_ACTION_SWIPE_DOWN = "action_swipe_down";
    static final String KEY_ACTION_SWIPE_LEFT = "action_swipe_left";
    static final String KEY_ACTION_SWIPE_RIGHT = "action_swipe_right";
    static final String KEY_EDGE_HIDDEN = "edge_hidden";
    static final int DEFAULT_BUTTON_COLOR = 0xFF2563EB;
    static final int DEFAULT_BUTTON_OPACITY = 90;
    static final int DEFAULT_COMPACT_SIZE_PERCENT = 60;
    static final int MIN_COMPACT_SIZE_PERCENT = 40;
    static final int MAX_COMPACT_SIZE_PERCENT = 150;
    private static final int BASE_COMPACT_SIZE_DP = 56;
    private static final int EDGE_TOUCH_WIDTH_DP = 18;
    private static final int EDGE_LINE_WIDTH_DP = 5;
    private static final int EDGE_LINE_HEIGHT_DP = 42;
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_EDGE_RIGHT = "edge_right";
    private static final String KEY_EDGE_Y = "edge_y";

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActionListener listener;
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
    private boolean longPressTriggered;
    private boolean positionMoveMode;
    private boolean pendingSingleTap;
    private boolean secondTapCandidate;
    private boolean edgeHidden;
    private boolean edgeRight;

    private final Runnable longPress;
    private final Runnable singleTap;
    private ValueAnimator returnAnimator;

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

    FloatingCaptureOverlay(Context context, int windowType, ActionListener listener) {
        this.context = context;
        this.listener = listener;
        singleTap = () -> {
            if (!pendingSingleTap || !attached) return;
            pendingSingleTap = false;
            performConfiguredAction(KEY_ACTION_TAP, ACTION_CAPTURE);
        };
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SystemQuickActions.initialize(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        captureSound = createCaptureSound();
        confirmationTone = createConfirmationTone();
        view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setElevation(Ui.dp(context, 8));
        view.setPadding(Ui.dp(context, 12), Ui.dp(context, 9), Ui.dp(context, 12), Ui.dp(context, 9));
        view.setBackground(buttonBackground());
        view.setOnTouchListener(this::onTouch);
        longPress = () -> {
            if (!moved && attached) {
                handler.removeCallbacks(singleTap);
                pendingSingleTap = false;
                secondTapCandidate = false;
                longPressTriggered = true;
                positionMoveMode = true;
                view.animate().cancel();
                view.animate().scaleX(1.12f).scaleY(1.12f)
                        .setDuration(130).start();
                vibrateTrigger(35, 150);
            }
        };

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
        edgeHidden = preferences.getBoolean(KEY_EDGE_HIDDEN, false);
        edgeRight = preferences.getBoolean(KEY_EDGE_RIGHT, false);
        if (edgeHidden) applyEdgeHandleAppearance();
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
        if (returnAnimator != null) returnAnimator.cancel();
        try { context.unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
        try { windowManager.removeView(view); } catch (IllegalArgumentException ignored) {}
        if (captureSound != null) captureSound.release();
        if (confirmationTone != null) confirmationTone.release();
        attached = false;
    }

    void setVisible(boolean visible) {
        if (!attached) return;
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (visible && edgeHidden) applyEdgeHandleAppearance();
    }

    void showResult(String message, boolean success) {
        if (!attached) return;
        if (success) playSuccessFeedback();
        if (edgeHidden) return;
        setVisible(true);
        updateText(message);
        view.setBackground(background(success ? "#E616A34A" : "#E6DC2626"));
        if (success) {
            view.setScaleX(0.82f);
            view.setScaleY(0.82f);
            view.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
        handler.postDelayed(() -> {
            view.setBackground(buttonBackground());
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
        if (edgeHidden) applyEdgeHandleAppearance();
        else {
            view.setBackground(buttonBackground());
            updateText(null);
        }
    }

    private void updateText(String temporary) {
        if (edgeHidden) {
            applyEdgeHandleAppearance();
            return;
        }
        if (temporary != null) {
            setCompactMode(false);
            view.setTextColor(Color.WHITE);
            view.setText(temporary);
            return;
        }
        boolean showTime = preferences.getBoolean(KEY_SHOW_TIME, true);
        boolean showBattery = preferences.getBoolean(KEY_SHOW_BATTERY, true);
        boolean compact = !showTime && !showBattery;
        setCompactMode(compact);
        StringBuilder text = new StringBuilder();
        if (showTime) {
            text.append(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
        if (showBattery) {
            if (text.length() > 0) text.append('\n');
            text.append(batteryPercent < 0 ? "--" : batteryPercent).append('%');
        }
        view.setTextColor(buttonTextColor());
        view.setText(text.toString());
    }

    private void setCompactMode(boolean compact) {
        int horizontal = compact ? 0 : Ui.dp(context, 12);
        int vertical = compact ? 0 : Ui.dp(context, 9);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        int compactSize = compactSizePx();
        params.width = compact ? compactSize : WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = compact ? compactSize : WindowManager.LayoutParams.WRAP_CONTENT;
        view.setBackground(buttonBackground());
        if (attached) {
            try { windowManager.updateViewLayout(view, params); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private boolean onTouch(View ignored, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (returnAnimator != null) returnAnimator.cancel();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                if (edgeHidden) {
                    moved = false;
                    view.animate().cancel();
                    view.animate().alpha(0.78f).setDuration(80).start();
                    return true;
                }
                downWindowX = params.x;
                downWindowY = params.y;
                moved = false;
                longPressTriggered = false;
                positionMoveMode = false;
                secondTapCandidate = pendingSingleTap;
                if (secondTapCandidate) {
                    handler.removeCallbacks(singleTap);
                    pendingSingleTap = false;
                }
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (edgeHidden) {
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(context).getScaledTouchSlop()) {
                        moved = true;
                    }
                    return true;
                }
                if (positionMoveMode) {
                    params.x = downWindowX + Math.round(dx);
                    params.y = downWindowY + Math.round(dy);
                    clampPosition();
                    if (attached) windowManager.updateViewLayout(view, params);
                    return true;
                }
                if (Math.hypot(dx, dy) > Ui.dp(context, 8)) {
                    moved = true;
                    handler.removeCallbacks(longPress);
                    if (secondTapCandidate) secondTapCandidate = false;
                }
                params.x = downWindowX + Math.round(dx * 0.42f);
                params.y = downWindowY + Math.round(dy * 0.42f);
                if (attached) windowManager.updateViewLayout(view, params);
                return true;
            case MotionEvent.ACTION_UP:
                if (edgeHidden) {
                    float hiddenDx = event.getRawX() - downRawX;
                    float hiddenDy = event.getRawY() - downRawY;
                    view.animate().cancel();
                    view.setAlpha(0.5f);
                    if (!moved && Math.hypot(hiddenDx, hiddenDy)
                            <= ViewConfiguration.get(context).getScaledTouchSlop()) {
                        restoreFromEdge();
                    }
                    return true;
                }
                handler.removeCallbacks(longPress);
                float upDx = event.getRawX() - downRawX;
                float upDy = event.getRawY() - downRawY;
                if (positionMoveMode) {
                    positionMoveMode = false;
                    clampPosition();
                    preferences.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply();
                    view.animate().scaleX(1f).scaleY(1f)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .setDuration(260).start();
                    vibrateTrigger(25, 120);
                } else if (Math.hypot(upDx, upDy) >= Ui.dp(context, 42)) {
                    animateWindowBack();
                    performSwipe(upDx, upDy);
                } else if (!longPressTriggered) {
                    animateWindowBack();
                    if (secondTapCandidate) {
                        secondTapCandidate = false;
                        performConfiguredAction(KEY_ACTION_DOUBLE_TAP, ACTION_NONE);
                    } else {
                        pendingSingleTap = true;
                        handler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout());
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (edgeHidden) {
                    moved = false;
                    view.animate().cancel();
                    view.setAlpha(0.5f);
                    return true;
                }
                handler.removeCallbacks(longPress);
                secondTapCandidate = false;
                positionMoveMode = false;
                animateWindowBack();
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                return true;
            default:
                return false;
        }
    }

    private void performSwipe(float dx, float dy) {
        if (Math.abs(dy) >= Math.abs(dx)) {
            performConfiguredAction(dy < 0 ? KEY_ACTION_SWIPE_UP : KEY_ACTION_SWIPE_DOWN,
                    dy < 0 ? ACTION_CAPTURE_SHARE : ACTION_NONE);
        } else {
            performConfiguredAction(dx < 0 ? KEY_ACTION_SWIPE_LEFT : KEY_ACTION_SWIPE_RIGHT,
                    ACTION_NONE);
        }
    }

    private void performConfiguredAction(String key, int defaultAction) {
        int action = preferences.getInt(key, defaultAction);
        if (action <= ACTION_NONE || action > ACTION_TOGGLE_MUTE) return;
        if (action == ACTION_HIDE_TO_EDGE) {
            hideToNearestEdge();
            return;
        }
        if (listener.onActionRequested(action)) {
            vibrateTrigger(28, 170);
            view.animate().cancel();
            view.setScaleX(0.86f);
            view.setScaleY(0.86f);
            view.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(new OvershootInterpolator(2.5f))
                    .setDuration(280).start();
        }
    }

    private void animateWindowBack() {
        if (!attached || (params.x == downWindowX && params.y == downWindowY)) return;
        if (returnAnimator != null) returnAnimator.cancel();
        int startX = params.x;
        int startY = params.y;
        returnAnimator = ValueAnimator.ofFloat(0f, 1f);
        returnAnimator.setDuration(360);
        returnAnimator.setInterpolator(new OvershootInterpolator(2.2f));
        returnAnimator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            params.x = startX + Math.round((downWindowX - startX) * value);
            params.y = startY + Math.round((downWindowY - startY) * value);
            if (attached) {
                try { windowManager.updateViewLayout(view, params); }
                catch (IllegalArgumentException ignored) {}
            }
        });
        returnAnimator.start();
    }

    private void clampPosition() {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int viewWidth = Math.max(view.getWidth(), params.width > 0 ? params.width : 0);
        int viewHeight = Math.max(view.getHeight(), params.height > 0 ? params.height : 0);
        params.x = Math.max(0, Math.min(params.x, Math.max(0, metrics.widthPixels - viewWidth)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, metrics.heightPixels - viewHeight)));
    }

    private void hideToNearestEdge() {
        if (!attached || edgeHidden) return;
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(singleTap);
        pendingSingleTap = false;
        secondTapCandidate = false;
        positionMoveMode = false;
        if (returnAnimator != null) returnAnimator.cancel();

        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int normalX = preferences.getInt(KEY_X, params.x);
        int normalY = preferences.getInt(KEY_Y, params.y);
        int normalWidth = Math.max(view.getWidth(), params.width > 0 ? params.width : 0);
        edgeRight = normalX + normalWidth / 2 >= metrics.widthPixels / 2;
        int edgeY = Math.max(0, Math.min(normalY,
                Math.max(0, metrics.heightPixels - Ui.dp(context, EDGE_LINE_HEIGHT_DP))));
        edgeHidden = true;
        preferences.edit()
                .putBoolean(KEY_EDGE_HIDDEN, true)
                .putBoolean(KEY_EDGE_RIGHT, edgeRight)
                .putInt(KEY_EDGE_Y, edgeY)
                .apply();

        view.animate().cancel();
        view.animate()
                .alpha(0.15f)
                .scaleX(0.45f)
                .scaleY(0.45f)
                .setDuration(120)
                .withEndAction(() -> {
                    if (!attached || !edgeHidden) return;
                    applyEdgeHandleAppearance();
                    view.setScaleX(0.65f);
                    view.setScaleY(0.65f);
                    view.animate().scaleX(1f).scaleY(1f).alpha(0.5f)
                            .setInterpolator(new OvershootInterpolator(1.8f))
                            .setDuration(220).start();
                })
                .start();
        vibrateTrigger(24, 125);
    }

    private void restoreFromEdge() {
        if (!attached || !edgeHidden) return;
        edgeHidden = false;
        preferences.edit().putBoolean(KEY_EDGE_HIDDEN, false).apply();
        params.x = preferences.getInt(KEY_X, Ui.dp(context, 12));
        params.y = preferences.getInt(KEY_Y, Ui.dp(context, 180));
        view.animate().cancel();
        view.setAlpha(0.45f);
        view.setScaleX(0.7f);
        view.setScaleY(0.7f);
        updateText(null);
        clampPosition();
        try { windowManager.updateViewLayout(view, params); }
        catch (IllegalArgumentException ignored) {}
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .setDuration(280).start();
        vibrateTrigger(24, 135);
    }

    private void applyEdgeHandleAppearance() {
        edgeRight = preferences.getBoolean(KEY_EDGE_RIGHT, edgeRight);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int touchWidth = Ui.dp(context, EDGE_TOUCH_WIDTH_DP);
        int lineHeight = Ui.dp(context, EDGE_LINE_HEIGHT_DP);
        params.width = touchWidth;
        params.height = lineHeight;
        params.x = edgeRight ? Math.max(0, metrics.widthPixels - touchWidth) : 0;
        params.y = Math.max(0, Math.min(
                preferences.getInt(KEY_EDGE_Y, preferences.getInt(KEY_Y, Ui.dp(context, 180))),
                Math.max(0, metrics.heightPixels - lineHeight)));
        view.setText("");
        view.setTextColor(Color.TRANSPARENT);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(edgeHandleBackground());
        view.setAlpha(0.5f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (attached) {
            try { windowManager.updateViewLayout(view, params); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private Drawable edgeHandleBackground() {
        int rgb = preferences.getInt(KEY_BUTTON_COLOR, DEFAULT_BUTTON_COLOR) & 0x00FFFFFF;
        GradientDrawable line = new GradientDrawable();
        line.setColor(0xFF000000 | rgb);
        line.setCornerRadius(Ui.dp(context, EDGE_LINE_WIDTH_DP) / 2f);
        if (rgb == 0x00FFFFFF) {
            line.setStroke(Ui.dp(context, 1), Color.parseColor("#660F172A"));
        }
        int remaining = Ui.dp(context, EDGE_TOUCH_WIDTH_DP - EDGE_LINE_WIDTH_DP);
        return edgeRight
                ? new InsetDrawable(line, remaining, 0, 0, 0)
                : new InsetDrawable(line, 0, 0, remaining, 0);
    }

    private void vibrateTrigger(int durationMs, int amplitude) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs,
                    vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    private GradientDrawable background(String color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(Ui.dp(context, 18));
        drawable.setStroke(Ui.dp(context, 1), Color.parseColor("#66FFFFFF"));
        return drawable;
    }

    private Drawable buttonBackground() {
        int rgb = preferences.getInt(KEY_BUTTON_COLOR, DEFAULT_BUTTON_COLOR) & 0x00FFFFFF;
        int opacity = Math.max(20, Math.min(100,
                preferences.getInt(KEY_BUTTON_OPACITY, DEFAULT_BUTTON_OPACITY)));
        int alpha = Math.round(opacity * 255f / 100f);
        GradientDrawable outer = new GradientDrawable();
        outer.setColor((alpha << 24) | rgb);
        boolean compact = !preferences.getBoolean(KEY_SHOW_TIME, true)
                && !preferences.getBoolean(KEY_SHOW_BATTERY, true);
        outer.setCornerRadius(compact ? compactSizePx() / 2f : Ui.dp(context, 18));
        outer.setStroke(Ui.dp(context, 1), Color.parseColor(
                rgb == 0x00FFFFFF ? "#33000000" : "#66FFFFFF"));
        return outer;
    }

    private int buttonTextColor() {
        int color = preferences.getInt(KEY_BUTTON_COLOR, DEFAULT_BUTTON_COLOR);
        return (color & 0x00FFFFFF) == 0x00FFFFFF ? Color.BLACK : Color.WHITE;
    }

    private int compactSizePx() {
        int percent = preferences.getInt(KEY_COMPACT_SIZE_PERCENT,
                DEFAULT_COMPACT_SIZE_PERCENT);
        percent = Math.max(MIN_COMPACT_SIZE_PERCENT,
                Math.min(MAX_COMPACT_SIZE_PERCENT, percent));
        return Ui.dp(context, Math.round(BASE_COMPACT_SIZE_DP * percent / 100f));
    }
}
