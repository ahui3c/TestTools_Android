package tw.chehu.testtools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.util.ArrayList;

public class BrightnessTestActivity extends Activity {
    private static final String PREFS = "brightness_settings";
    private static final String KEY_LAST_TEST_PERCENTAGE = "last_test_percentage";
    static final String EXTRA_PERCENTAGES = "percentages";
    static final String EXTRA_CIRCLE = "circle";
    static final String EXTRA_MAX_BRIGHTNESS = "max_brightness";
    private float downX;
    private float downY;
    private BrightnessPatternView pattern;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getIntent().getBooleanExtra(EXTRA_MAX_BRIGHTNESS, true)) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 1f;
            getWindow().setAttributes(params);
        }

        ArrayList<Integer> values = getIntent().getIntegerArrayListExtra(EXTRA_PERCENTAGES);
        if (values == null || values.isEmpty()) {
            values = new ArrayList<>();
            int[] defaults = {5, 10, 25, 50, 75, 100};
            for (int value : defaults) values.add(value);
        }
        int initialPercentage = preferences.getInt(KEY_LAST_TEST_PERCENTAGE, values.get(0));
        pattern = new BrightnessPatternView(
                this,
                values,
                getIntent().getBooleanExtra(EXTRA_CIRCLE, false),
                initialPercentage);
        pattern.setOnTouchListener(this::handleTouch);
        setContentView(pattern);
        pattern.post(this::hideSystemUi);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    private boolean handleTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (Math.hypot(dx, dy) > Ui.dp(this, 45)) {
                if (Math.abs(dx) >= Math.abs(dy)) {
                    if (dx > 0) pattern.previous(); else pattern.next();
                } else {
                    if (dy > 0) pattern.previous(); else pattern.next();
                }
                preferences.edit().putInt(KEY_LAST_TEST_PERCENTAGE, pattern.currentPercentage()).apply();
            }
            return true;
        }
        return true;
    }

    private void hideSystemUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Fullscreen.hide(getWindow());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** 將 API 30 類別隔離，避免 Android 6～10 在載入 Activity 時驗證失敗。 */
    private static final class Api30Fullscreen {
        private Api30Fullscreen() {}

        static void hide(Window window) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
