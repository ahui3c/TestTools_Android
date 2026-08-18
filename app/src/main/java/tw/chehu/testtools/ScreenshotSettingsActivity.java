package tw.chehu.testtools;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class ScreenshotSettingsActivity extends Activity {
    private static final int REQUEST_PROJECTION = 6201;
    private static final int REQUEST_STORAGE = 6202;
    private SharedPreferences preferences;
    private TextView status;
    private Button overlayToggle;
    private boolean waitingForOverlayPermission;
    private static final String[] BUTTON_COLOR_NAMES = {
            "科技藍", "天空藍", "青綠", "翠綠", "橘色", "紅色", "紫色", "深灰", "白色", "黑色"
    };
    private static final int[] BUTTON_COLORS = {
            0xFF2563EB, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFFEA580C, 0xFFDC2626, 0xFF7C3AED, 0xFF334155,
            0xFFFFFFFF, 0xFF000000
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(FloatingCaptureOverlay.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 30));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 16, Ui.color("#2563EB"), true);
        back.setPadding(0, 0, 0, Ui.dp(this, 18));
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "浮動快速截圖", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "單擊、雙擊及上下左右滑動都可指定功能；滑動時會有阻尼與回彈動畫。要移動按鈕位置，請先長按不放，感覺到震動後再拖曳。截圖會保存至系統 Screenshots 資料夾。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 18));
        content.addView(intro);

        LinearLayout notice = panel();
        notice.addView(Ui.text(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? "Android 11 以上使用系統的無障礙截圖功能。第一次需手動開啟「TestTools 浮動快速截圖」，之後每次按下浮動按鈕不會再詢問擷取範圍。"
                        : "此 Android 版本需允許顯示在其他 App 上層，並接受一次系統螢幕擷取授權；授權工作階段停止或手機重新啟動後需再次允許。",
                14, Ui.color("#334155"), false));
        addPanel(content, notice);

        LinearLayout gestures = panel();
        TextView gestureTitle = Ui.text(this, "手勢功能設定", 17, Ui.color("#0F172A"), true);
        gestureTitle.setPadding(0, 0, 0, Ui.dp(this, 5));
        gestures.addView(gestureTitle);
        TextView gestureHint = Ui.text(this,
                "功能成功觸發時會短震動。返回與多工功能在 Android 11 以上透過無障礙服務執行。",
                13, Ui.color("#64748B"), false);
        gestureHint.setPadding(0, 0, 0, Ui.dp(this, 8));
        gestures.addView(gestureHint);
        addGestureSelector(gestures, "單擊", FloatingCaptureOverlay.KEY_ACTION_TAP,
                FloatingCaptureOverlay.ACTION_CAPTURE);
        addGestureSelector(gestures, "雙擊", FloatingCaptureOverlay.KEY_ACTION_DOUBLE_TAP,
                FloatingCaptureOverlay.ACTION_NONE);
        addGestureSelector(gestures, "向上滑動", FloatingCaptureOverlay.KEY_ACTION_SWIPE_UP,
                FloatingCaptureOverlay.ACTION_CAPTURE_SHARE);
        addGestureSelector(gestures, "向下滑動", FloatingCaptureOverlay.KEY_ACTION_SWIPE_DOWN,
                FloatingCaptureOverlay.ACTION_NONE);
        addGestureSelector(gestures, "向左滑動", FloatingCaptureOverlay.KEY_ACTION_SWIPE_LEFT,
                FloatingCaptureOverlay.ACTION_NONE);
        addGestureSelector(gestures, "向右滑動", FloatingCaptureOverlay.KEY_ACTION_SWIPE_RIGHT,
                FloatingCaptureOverlay.ACTION_NONE);
        addPanel(content, gestures);

        CheckBox showTime = option("浮動按鈕顯示現在時間",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SHOW_TIME, true));
        CheckBox showBattery = option("浮動按鈕顯示設備電量",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SHOW_BATTERY, true));
        CheckBox flashFeedback = option("截圖成功時顯示明顯白色閃光",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_FLASH_FEEDBACK, true));
        CheckBox vibrateFeedback = option("截圖成功時使用雙段震動",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_VIBRATE_FEEDBACK, false));
        CheckBox soundFeedback = option("截圖成功時播放清晰快門提示音",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SOUND_FEEDBACK, false));
        LinearLayout options = panel();
        options.addView(showTime);
        options.addView(showBattery);
        options.addView(flashFeedback);
        options.addView(vibrateFeedback);
        options.addView(soundFeedback);
        addPanel(content, options);
        showTime.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_SHOW_TIME, checked).apply();
            refreshServices();
        });
        showBattery.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_SHOW_BATTERY, checked).apply();
            refreshServices();
        });
        flashFeedback.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_FLASH_FEEDBACK, checked).apply());
        vibrateFeedback.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_VIBRATE_FEEDBACK, checked).apply());
        soundFeedback.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_SOUND_FEEDBACK, checked).apply());

        TextView appearanceTitle = Ui.text(this, "浮動按鈕外觀", 16, Ui.color("#0F172A"), true);
        appearanceTitle.setPadding(0, 0, 0, Ui.dp(this, 8));
        Spinner colorSpinner = new Spinner(this);
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, BUTTON_COLOR_NAMES);
        colorSpinner.setAdapter(colorAdapter);
        int savedColor = preferences.getInt(FloatingCaptureOverlay.KEY_BUTTON_COLOR,
                FloatingCaptureOverlay.DEFAULT_BUTTON_COLOR);
        colorSpinner.setSelection(colorIndex(savedColor));

        int savedOpacity = preferences.getInt(FloatingCaptureOverlay.KEY_BUTTON_OPACITY,
                FloatingCaptureOverlay.DEFAULT_BUTTON_OPACITY);
        TextView opacityLabel = Ui.text(this, "按鈕不透明度：" + savedOpacity + "%", 15,
                Ui.color("#334155"), false);
        opacityLabel.setPadding(0, Ui.dp(this, 12), 0, 0);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(80);
        opacity.setProgress(Math.max(20, Math.min(100, savedOpacity)) - 20);

        LinearLayout appearance = panel();
        appearance.addView(appearanceTitle);
        appearance.addView(colorSpinner);
        appearance.addView(opacityLabel);
        appearance.addView(opacity);

        int savedCompactSize = preferences.getInt(
                FloatingCaptureOverlay.KEY_COMPACT_SIZE_PERCENT,
                FloatingCaptureOverlay.DEFAULT_COMPACT_SIZE_PERCENT);
        savedCompactSize = Math.max(FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT,
                Math.min(FloatingCaptureOverlay.MAX_COMPACT_SIZE_PERCENT, savedCompactSize));
        TextView compactSizeLabel = Ui.text(this,
                "圓形按鈕大小：" + savedCompactSize + "%", 15,
                Ui.color("#334155"), false);
        compactSizeLabel.setPadding(0, Ui.dp(this, 12), 0, 0);
        SeekBar compactSize = new SeekBar(this);
        compactSize.setMax(FloatingCaptureOverlay.MAX_COMPACT_SIZE_PERCENT
                - FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT);
        compactSize.setProgress(savedCompactSize
                - FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT);
        appearance.addView(compactSizeLabel);
        appearance.addView(compactSize);
        TextView compactHint = Ui.text(this,
                "同時關閉時間與電量後，浮動按鈕會改為可調整大小的一般圓形按鈕；100% 等於原本的 56 dp。",
                13, Ui.color("#64748B"), false);
        compactHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        appearance.addView(compactHint);
        addPanel(content, appearance);

        colorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                                  int position, long id) {
                preferences.edit().putInt(FloatingCaptureOverlay.KEY_BUTTON_COLOR,
                        BUTTON_COLORS[position]).apply();
                refreshServices();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 20;
                opacityLabel.setText("按鈕不透明度：" + value + "%");
                if (fromUser) {
                    preferences.edit().putInt(FloatingCaptureOverlay.KEY_BUTTON_OPACITY, value).apply();
                    refreshServices();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        compactSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT;
                compactSizeLabel.setText("圓形按鈕大小：" + value + "%");
                if (fromUser) {
                    preferences.edit().putInt(
                            FloatingCaptureOverlay.KEY_COMPACT_SIZE_PERCENT, value).apply();
                    refreshServices();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        status = Ui.text(this, "", 14, Ui.color("#475569"), true);
        LinearLayout statusPanel = panel();
        statusPanel.addView(status);
        addPanel(content, statusPanel);

        overlayToggle = actionButton("", "#2563EB", Color.WHITE);
        content.addView(overlayToggle, buttonParams());
        overlayToggle.setOnClickListener(v -> {
            if (preferences.getBoolean(FloatingCaptureOverlay.KEY_ENABLED, false)) {
                disableOverlay();
            } else {
                enableOverlay();
            }
        });
        updateToggleButton();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Button settings = actionButton("開啟無障礙服務設定", "#FFFFFF", Ui.color("#2563EB"));
            LinearLayout.LayoutParams settingsParams = buttonParams();
            settingsParams.topMargin = Ui.dp(this, 10);
            content.addView(settings, settingsParams);
            settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        TextView limitation = Ui.text(this,
                "注意：銀行、串流 DRM、無痕模式等受保護畫面可能禁止截圖；這是 Android 的安全限制。",
                13, Ui.color("#94A3B8"), false);
        limitation.setPadding(0, Ui.dp(this, 18), 0, 0);
        content.addView(limitation);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForOverlayPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false;
            continueLegacyStart();
        }
        updateStatus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isAccessibilityEnabled()) {
            QuickScreenshotAccessibilityService.refreshRunningService();
        }
    }

    private void enableOverlay() {
        preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_ENABLED, true).apply();
        updateStatus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "請開啟「TestTools 浮動快速截圖」服務", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                QuickScreenshotAccessibilityService.refreshRunningService();
                updateStatus();
            }
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true;
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            return;
        }
        continueLegacyStart();
    }

    private void continueLegacyStart() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    private void disableOverlay() {
        preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_ENABLED, false).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            QuickScreenshotAccessibilityService.refreshRunningService();
        } else {
            stopService(new Intent(this, ProjectionCaptureService.class).setAction(
                    ProjectionCaptureService.ACTION_STOP));
        }
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_ENABLED, false).apply();
            Toast.makeText(this, "未取得螢幕擷取授權", Toast.LENGTH_SHORT).show();
            updateStatus();
            return;
        }
        Intent service = new Intent(this, ProjectionCaptureService.class)
                .putExtra(ProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ProjectionCaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            continueLegacyStart();
        } else if (requestCode == REQUEST_STORAGE) {
            preferences.edit().putBoolean(FloatingCaptureOverlay.KEY_ENABLED, false).apply();
            Toast.makeText(this, "需要儲存空間權限才能保存截圖", Toast.LENGTH_LONG).show();
            updateStatus();
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        ComponentName wanted = new ComponentName(this, QuickScreenshotAccessibilityService.class);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            ComponentName found = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (wanted.equals(found)) return true;
        }
        return false;
    }

    private void updateStatus() {
        if (status == null) return;
        boolean wanted = preferences.getBoolean(FloatingCaptureOverlay.KEY_ENABLED, false);
        if (!wanted) {
            status.setText("狀態：浮動按鈕已停止");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isAccessibilityEnabled()) {
            status.setText("狀態：等待開啟無障礙服務");
        } else {
            status.setText("狀態：浮動按鈕已啟用");
        }
        updateToggleButton();
    }

    private void updateToggleButton() {
        if (overlayToggle == null) return;
        boolean enabled = preferences.getBoolean(FloatingCaptureOverlay.KEY_ENABLED, false);
        overlayToggle.setText(enabled
                ? "隱藏並停止浮動按鈕"
                : "啟用／顯示浮動按鈕");
        overlayToggle.setTextColor(enabled ? Ui.color("#334155") : Color.WHITE);
        overlayToggle.setBackground(Ui.background(
                Ui.color(enabled ? "#E2E8F0" : "#2563EB"), 14, this));
    }

    private void refreshServices() {
        QuickScreenshotAccessibilityService.refreshRunningService();
        ProjectionCaptureService.refreshRunningService();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.background(Color.WHITE, 14, this));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return panel;
    }

    private void addPanel(LinearLayout content, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        content.addView(panel, params);
    }

    private CheckBox option(String label, boolean checked) {
        CheckBox option = new CheckBox(this);
        option.setText(label);
        option.setTextSize(16);
        option.setChecked(checked);
        option.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        return option;
    }

    private Button actionButton(String label, String background, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 14, this));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
    }

    private int colorIndex(int color) {
        for (int i = 0; i < BUTTON_COLORS.length; i++) {
            if (BUTTON_COLORS[i] == color) return i;
        }
        return 0;
    }

    private void addGestureSelector(LinearLayout parent, String label, String key,
                                    int defaultAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        row.addView(Ui.text(this, label, 14, Ui.color("#475569"), true));
        Spinner selector = new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                FloatingCaptureOverlay.ACTION_LABELS));
        int selected = preferences.getInt(key, defaultAction);
        if (selected < FloatingCaptureOverlay.ACTION_NONE ||
                selected > FloatingCaptureOverlay.ACTION_QUICK_BACKUP) selected = defaultAction;
        selector.setSelection(selected);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parentView, android.view.View view,
                                                  int position, long id) {
                preferences.edit().putInt(key, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });
        row.addView(selector, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
}
