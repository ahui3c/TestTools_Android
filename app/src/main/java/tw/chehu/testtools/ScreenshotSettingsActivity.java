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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

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
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 28));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 15, Ui.color("#2563EB"), true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(Ui.dp(this, 40));
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "浮動快速截圖", 26, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "自訂點擊與滑動功能；長按震動後可拖曳按鈕位置。",
                13, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        content.addView(intro);

        TextView notice = Ui.text(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? "首次使用需開啟無障礙服務，之後截圖不會重複詢問。"
                        : "首次使用需允許顯示於其他 App 上層及螢幕擷取。",
                13, Ui.color("#1E40AF"), false);
        notice.setBackground(Ui.background(Ui.color("#EFF6FF"), 12, this));
        Ui.setPadding(notice, 12, 10);
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
        noticeParams.bottomMargin = Ui.dp(this, 10);
        content.addView(notice, noticeParams);

        LinearLayout gestures = panel();
        TextView gestureTitle = sectionTitle("手勢功能");
        gestures.addView(gestureTitle);
        TextView gestureHint = Ui.text(this,
                "觸發成功會短震動；設定為「隱藏」可收納至最近的螢幕側邊。",
                12, Ui.color("#64748B"), false);
        gestureHint.setPadding(0, 0, 0, Ui.dp(this, 6));
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
        Button quickActionSettings = actionButton("快捷功能與 App 設定",
                "#E8F0FE", Ui.color("#1D4ED8"));
        LinearLayout.LayoutParams quickActionParams = buttonParams();
        quickActionParams.topMargin = Ui.dp(this, 6);
        gestures.addView(quickActionSettings, quickActionParams);
        quickActionSettings.setOnClickListener(v -> startActivity(
                new Intent(this, QuickActionSettingsActivity.class)));
        addPanel(content, gestures);

        Switch showTime = toggleOption("顯示現在時間",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SHOW_TIME, true));
        Switch showBattery = toggleOption("顯示設備電量",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SHOW_BATTERY, true));
        Switch flashFeedback = toggleOption("白色閃光",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_FLASH_FEEDBACK, true));
        Switch vibrateFeedback = toggleOption("雙段震動",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_VIBRATE_FEEDBACK, false));
        Switch soundFeedback = toggleOption("快門提示音",
                preferences.getBoolean(FloatingCaptureOverlay.KEY_SOUND_FEEDBACK, false));
        LinearLayout options = panel();
        options.addView(sectionTitle("顯示與截圖回饋"));
        addToggle(options, showTime, true);
        addToggle(options, showBattery, true);
        addToggle(options, flashFeedback, true);
        addToggle(options, vibrateFeedback, true);
        addToggle(options, soundFeedback, false);
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

        TextView appearanceTitle = sectionTitle("按鈕外觀");
        Spinner colorSpinner = new Spinner(this);
        colorSpinner.setAdapter(compactAdapter(BUTTON_COLOR_NAMES));
        int savedColor = preferences.getInt(FloatingCaptureOverlay.KEY_BUTTON_COLOR,
                FloatingCaptureOverlay.DEFAULT_BUTTON_COLOR);
        colorSpinner.setSelection(colorIndex(savedColor));

        int savedOpacity = preferences.getInt(FloatingCaptureOverlay.KEY_BUTTON_OPACITY,
                FloatingCaptureOverlay.DEFAULT_BUTTON_OPACITY);
        TextView opacityLabel = Ui.text(this, percentLabel("透明度", savedOpacity), 14,
                Ui.color("#334155"), false);
        opacityLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(80);
        opacity.setProgress(Math.max(20, Math.min(100, savedOpacity)) - 20);

        LinearLayout appearance = panel();
        appearance.addView(appearanceTitle);
        LinearLayout colorRow = compactSettingRow("顏色", colorSpinner);
        appearance.addView(colorRow);
        appearance.addView(opacityLabel);
        appearance.addView(opacity);

        int savedCompactSize = preferences.getInt(
                FloatingCaptureOverlay.KEY_COMPACT_SIZE_PERCENT,
                FloatingCaptureOverlay.DEFAULT_COMPACT_SIZE_PERCENT);
        savedCompactSize = Math.max(FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT,
                Math.min(FloatingCaptureOverlay.MAX_COMPACT_SIZE_PERCENT, savedCompactSize));
        TextView compactSizeLabel = Ui.text(this,
                percentLabel("圓形大小", savedCompactSize), 14,
                Ui.color("#334155"), false);
        compactSizeLabel.setPadding(0, Ui.dp(this, 8), 0, 0);
        SeekBar compactSize = new SeekBar(this);
        compactSize.setMax(FloatingCaptureOverlay.MAX_COMPACT_SIZE_PERCENT
                - FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT);
        compactSize.setProgress(savedCompactSize
                - FloatingCaptureOverlay.MIN_COMPACT_SIZE_PERCENT);
        appearance.addView(compactSizeLabel);
        appearance.addView(compactSize);
        TextView compactHint = Ui.text(this,
                "關閉時間與電量後套用圓形大小；100% 為 56 dp。",
                12, Ui.color("#64748B"), false);
        compactHint.setPadding(0, Ui.dp(this, 2), 0, 0);
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
                opacityLabel.setText(percentLabel("透明度", value));
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
                compactSizeLabel.setText(percentLabel("圓形大小", value));
                if (fromUser) {
                    preferences.edit().putInt(
                            FloatingCaptureOverlay.KEY_COMPACT_SIZE_PERCENT, value).apply();
                    refreshServices();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        status = Ui.text(this, "", 13, Ui.color("#334155"), true);
        status.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPadding(status, 12, 9);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.bottomMargin = Ui.dp(this, 8);
        content.addView(status, statusParams);

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
            Button settings = actionButton("無障礙服務設定", "#FFFFFF", Ui.color("#2563EB"));
            LinearLayout.LayoutParams settingsParams = buttonParams();
            settingsParams.topMargin = Ui.dp(this, 10);
            content.addView(settings, settingsParams);
            settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        TextView limitation = Ui.text(this,
                "注意：銀行、串流 DRM、無痕模式等受保護畫面可能禁止截圖；這是 Android 的安全限制。",
                12, Ui.color("#94A3B8"), false);
        limitation.setPadding(0, Ui.dp(this, 14), 0, 0);
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
        startForegroundService(service);
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
            setStatusStyle("浮動按鈕已停止", "#F1F5F9", "#475569");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isAccessibilityEnabled()) {
            setStatusStyle("等待開啟無障礙服務", "#FFF7ED", "#9A3412");
        } else if (preferences.getBoolean(FloatingCaptureOverlay.KEY_EDGE_HIDDEN, false)) {
            setStatusStyle("浮動按鈕已收納於螢幕側邊", "#EFF6FF", "#1D4ED8");
        } else {
            setStatusStyle("浮動按鈕已啟用", "#ECFDF5", "#047857");
        }
        updateToggleButton();
    }

    private void setStatusStyle(String value, String background, String textColor) {
        status.setText(String.format(Locale.TAIWAN, "●  %s", value));
        status.setTextColor(Ui.color(textColor));
        status.setBackground(Ui.background(Ui.color(background), 10, this));
    }

    private void updateToggleButton() {
        if (overlayToggle == null) return;
        boolean enabled = preferences.getBoolean(FloatingCaptureOverlay.KEY_ENABLED, false);
        overlayToggle.setText(enabled
                ? "停止浮動按鈕"
                : "啟用浮動按鈕");
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
        panel.setBackground(Ui.background(Color.WHITE, 16, this));
        panel.setElevation(Ui.dp(this, 1));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return panel;
    }

    private void addPanel(LinearLayout content, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 10);
        content.addView(panel, params);
    }

    private TextView sectionTitle(String label) {
        TextView title = Ui.text(this, label, 17, Ui.color("#0F172A"), true);
        title.setPadding(0, 0, 0, Ui.dp(this, 7));
        return title;
    }

    private Switch toggleOption(String label, boolean checked) {
        Switch option = new Switch(this);
        option.setText(label);
        option.setTextSize(14);
        option.setTextColor(Ui.color("#334155"));
        option.setChecked(checked);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(Ui.dp(this, 44));
        option.setPadding(0, 0, 0, 0);
        return option;
    }

    private void addToggle(LinearLayout parent, Switch option, boolean dividerAfter) {
        parent.addView(option, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        if (dividerAfter) {
            View divider = new View(this);
            divider.setBackgroundColor(Ui.color("#E2E8F0"));
            parent.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        }
    }

    private Button actionButton(String label, String background, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 14, this));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
    }

    private LinearLayout compactSettingRow(String label, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, label, 14, Ui.color("#475569"), true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 64), Ui.dp(this, 44));
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, titleParams);
        row.addView(control, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        return row;
    }

    private ArrayAdapter<String> compactAdapter(String[] labels) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return spinnerText(labels[position], false);
            }

            @Override
            public View getDropDownView(int position, View convertView,
                                        android.view.ViewGroup parent) {
                return spinnerText(labels[position], true);
            }
        };
    }

    private String percentLabel(String label, int value) {
        return String.format(Locale.TAIWAN, "%s  %d%%", label, value);
    }

    private TextView spinnerText(String label, boolean dropdown) {
        TextView text = Ui.text(this, label, dropdown ? 15 : 14,
                Ui.color("#0F172A"), false);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMinHeight(Ui.dp(this, 44));
        text.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 30), 0);
        text.setBackground(Ui.background(
                Ui.color(dropdown ? "#FFFFFF" : "#F1F5F9"), 10, this));
        return text;
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
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        TextView gestureLabel = Ui.text(this, label, 13, Ui.color("#475569"), true);
        gestureLabel.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(gestureLabel, new LinearLayout.LayoutParams(
                Ui.dp(this, 76), Ui.dp(this, 44)));
        Spinner selector = new Spinner(this);
        selector.setAdapter(compactAdapter(FloatingCaptureOverlay.ACTION_LABELS));
        int selected = preferences.getInt(key, defaultAction);
        if (selected == FloatingCaptureOverlay.LEGACY_ACTION_RUN_APP_ACTION) {
            selected = FloatingCaptureOverlay.ACTION_NONE;
            preferences.edit().putInt(key, selected).apply();
        }
        int selectedPosition = actionPosition(selected);
        if (selectedPosition < 0) selectedPosition = actionPosition(defaultAction);
        selector.setSelection(Math.max(0, selectedPosition));
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parentView, android.view.View view,
                                                  int position, long id) {
                preferences.edit().putInt(key,
                        FloatingCaptureOverlay.ACTION_IDS[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });
        row.addView(selector, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private int actionPosition(int action) {
        for (int position = 0; position < FloatingCaptureOverlay.ACTION_IDS.length; position++) {
            if (FloatingCaptureOverlay.ACTION_IDS[position] == action) return position;
        }
        return -1;
    }
}
