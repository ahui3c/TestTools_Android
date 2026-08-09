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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class ScreenshotSettingsActivity extends Activity {
    private static final int REQUEST_PROJECTION = 6201;
    private static final int REQUEST_STORAGE = 6202;
    private SharedPreferences preferences;
    private TextView status;
    private boolean waitingForOverlayPermission;

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
                "浮動按鈕會顯示現在時間與設備電量；可拖曳到任意位置，輕點後才會擷取。截圖會自動保存至系統 Pictures/Screenshots；若偵測到手機使用 DCIM/Screenshots 則會自動沿用，不會建立 App 專屬資料夾。",
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

        status = Ui.text(this, "", 14, Ui.color("#475569"), true);
        LinearLayout statusPanel = panel();
        statusPanel.addView(status);
        addPanel(content, statusPanel);

        Button start = actionButton("啟用／顯示浮動按鈕", "#2563EB", Color.WHITE);
        content.addView(start, buttonParams());
        start.setOnClickListener(v -> enableOverlay());

        Button hide = actionButton("隱藏並停止浮動按鈕", "#E2E8F0", Ui.color("#334155"));
        LinearLayout.LayoutParams hideParams = buttonParams();
        hideParams.topMargin = Ui.dp(this, 10);
        content.addView(hide, hideParams);
        hide.setOnClickListener(v -> disableOverlay());

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
}
