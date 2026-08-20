package tw.chehu.testtools;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

public class ScreenPatternActivity extends Activity {
    static final String EXTRA_MAX_BRIGHTNESS = "max_brightness";
    static final String EXTRA_KEEP_AWAKE = "keep_awake";
    private float downX;
    private float downY;
    private ScreenPatternView pattern;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_KEEP_AWAKE, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (getIntent().getBooleanExtra(EXTRA_MAX_BRIGHTNESS, true)) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 1f;
            getWindow().setAttributes(params);
        }
        pattern = new ScreenPatternView(this);
        pattern.setOnTouchListener(this::handleTouch);
        setContentView(pattern);
        pattern.post(this::hideSystemUi);
        Toast.makeText(this, "點擊／左滑下一張，右滑上一張", Toast.LENGTH_LONG).show();
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    private boolean handleTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            view.performClick();
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (Math.hypot(dx, dy) < Ui.dp(this, 45)) pattern.next();
            else if (Math.abs(dx) >= Math.abs(dy)) {
                if (dx < 0) pattern.next(); else pattern.previous();
            } else {
                if (dy < 0) pattern.next(); else pattern.previous();
            }
            return true;
        }
        return true;
    }

    private void hideSystemUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Api30Fullscreen.hide(getWindow());
        else getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static final class Api30Fullscreen {
        @TargetApi(Build.VERSION_CODES.R)
        static void hide(Window window) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
