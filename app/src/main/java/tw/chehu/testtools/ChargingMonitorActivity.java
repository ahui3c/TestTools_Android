package tw.chehu.testtools;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChargingMonitorActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 7601;
    private static final int REQUEST_EXPORT = 7602;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshHandler.postDelayed(this, 3_000);
        }
    };

    private SharedPreferences preferences;
    private TextView stateView;
    private TextView latestView;
    private TextView sessionView;
    private Button startButton;
    private Button stopButton;
    private Button exportButton;
    private boolean startAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ChargingStorage.migrateIfUnlocked(this);
        preferences = ChargingStorage.preferences(this);

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
        content.addView(Ui.text(this, "充電數據監控", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "開始後每分鐘記錄一次電量、變化、充電狀態、供電類型、健康度、溫度、電壓、電流與電池端估算功率；插拔電、低電量、開機恢復、開始與停止也會立即記錄。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 18));
        content.addView(intro);

        LinearLayout statusPanel = panel();
        stateView = Ui.text(this, "", 17, Ui.color("#0F172A"), true);
        latestView = Ui.text(this, "", 15, Ui.color("#334155"), false);
        latestView.setPadding(0, Ui.dp(this, 12), 0, 0);
        sessionView = Ui.text(this, "", 13, Ui.color("#64748B"), false);
        sessionView.setPadding(0, Ui.dp(this, 10), 0, 0);
        statusPanel.addView(stateView);
        statusPanel.addView(latestView);
        statusPanel.addView(sessionView);
        addPanel(content, statusPanel);

        startButton = actionButton("開始新的監控記錄", "#2563EB", Color.WHITE);
        content.addView(startButton, buttonParams(0));
        startButton.setOnClickListener(v -> prepareStart());

        stopButton = actionButton("停止監控", "#FEE2E2", Ui.color("#B91C1C"));
        content.addView(stopButton, buttonParams(10));
        stopButton.setOnClickListener(v -> stopMonitoring());

        exportButton = actionButton("匯出目前／最近一次 CSV", "#DCFCE7", Ui.color("#166534"));
        content.addView(exportButton, buttonParams(10));
        exportButton.setOnClickListener(v -> exportCsv());

        Button notificationSettings = actionButton(
                "開啟監控通知設定", "#FFFFFF", Ui.color("#2563EB"));
        content.addView(notificationSettings, buttonParams(10));
        notificationSettings.setOnClickListener(v -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(settings);
        });

        Button appSettings = actionButton(
                "開啟 App 電池與自動啟動設定", "#FFFFFF", Ui.color("#2563EB"));
        content.addView(appSettings, buttonParams(10));
        appSettings.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        LinearLayout notePanel = panel();
        notePanel.addView(Ui.text(this,
                "低負載設計：每分鐘僅查詢一次系統電池資料並追加一行本機 CSV；不使用網路、不持有 WakeLock，也不持續輪詢感測器。監控時 Android 會顯示常駐通知。",
                14, Ui.color("#334155"), false));
        TextView accuracy = Ui.text(this,
                "功率＝系統回報的電池電流 × 電池電壓，是電池端估算值；不等同充電器插座輸出，部分手機也可能不提供電流或循環次數。",
                13, Ui.color("#92400E"), false);
        accuracy.setPadding(0, Ui.dp(this, 10), 0, 0);
        notePanel.addView(accuracy);
        TextView rebootNote = Ui.text(this,
                "啟用狀態與 CSV 使用 Device Protected Storage。手機耗盡關機後，只要插電並進入 Android 開機流程，即會在鎖定開機階段恢復。部分廠牌仍需允許「自動啟動」或將電池使用設為不限制。",
                13, Ui.color("#64748B"), false);
        rebootNote.setPadding(0, Ui.dp(this, 10), 0, 0);
        notePanel.addView(rebootNote);
        TextView widgetNote = Ui.text(this,
                "桌面 Widget：長按桌面空白處 → 小工具 → TestTools → 加入 2×1「充電數據監控」。Widget 可顯示最新數值並開始／停止監控。",
                13, Ui.color("#2563EB"), false);
        widgetNote.setPadding(0, Ui.dp(this, 10), 0, 0);
        notePanel.addView(widgetNote);
        addPanel(content, notePanel);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences.getBoolean(ChargingMonitorService.KEY_ACTIVE, false)
                && !ChargingMonitorService.isRunning()) {
            Intent resume = new Intent(this, ChargingMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(resume);
            else startService(resume);
        }
        refreshHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void prepareStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        startMonitoring();
    }

    private void startMonitoring() {
        Intent service = new Intent(this, ChargingMonitorService.class)
                .setAction(ChargingMonitorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "已開始每分鐘記錄", Toast.LENGTH_SHORT).show();
        refreshHandler.postDelayed(this::refreshStatus, 400);
    }

    private void stopMonitoring() {
        Intent service = new Intent(this, ChargingMonitorService.class)
                .setAction(ChargingMonitorService.ACTION_STOP);
        startService(service);
        Toast.makeText(this, "正在停止並保存最後一筆資料", Toast.LENGTH_SHORT).show();
        refreshHandler.postDelayed(this::refreshStatus, 500);
    }

    private void exportCsv() {
        File file = currentFile();
        if (file == null || !file.isFile()) {
            Toast.makeText(this, "目前沒有可匯出的記錄", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, defaultExportFileName());
        startActivityForResult(create, REQUEST_EXPORT);
    }

    private String defaultExportFileName() {
        String deviceName = Settings.Global.getString(
                getContentResolver(), Settings.Global.DEVICE_NAME);
        if (deviceName == null || deviceName.trim().isEmpty()) deviceName = Build.MODEL;
        if (deviceName == null || deviceName.trim().isEmpty()) deviceName = "Android";

        String safeName = deviceName.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ");
        String date = new SimpleDateFormat("MMdd", Locale.getDefault()).format(new Date());
        return safeName + "_" + date + ".csv";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        File source = currentFile();
        Uri destination = data.getData();
        if (source == null || !source.isFile()) {
            Toast.makeText(this, "記錄檔已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> copyFile(source, destination), "charging-csv-export").start();
    }

    private void copyFile(File source, Uri destination) {
        try {
            synchronized (ChargingMonitorService.FILE_LOCK) {
                try (FileInputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IllegalStateException("無法開啟輸出檔案");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                }
            }
            runOnUiThread(() -> Toast.makeText(
                    this, "CSV 已匯出", Toast.LENGTH_LONG).show());
        } catch (Exception error) {
            runOnUiThread(() -> Toast.makeText(
                    this, "CSV 匯出失敗", Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS && startAfterPermission) {
            startAfterPermission = false;
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "未允許通知；系統可能只在執行中服務清單顯示監控狀態",
                        Toast.LENGTH_LONG).show();
            }
            startMonitoring();
        }
    }

    private void refreshStatus() {
        if (stateView == null) return;
        boolean active = preferences.getBoolean(ChargingMonitorService.KEY_ACTIVE, false);
        stateView.setText(active
                ? (ChargingMonitorService.isRunning() ? "● 正在監控" : "● 正在恢復監控")
                : "○ 監控已停止");
        stateView.setTextColor(Ui.color(active ? "#15803D" : "#64748B"));
        latestView.setText(preferences.getString(
                ChargingMonitorService.KEY_LATEST_SUMMARY, "尚未建立監控記錄"));
        long started = preferences.getLong(ChargingMonitorService.KEY_SESSION_STARTED, 0);
        File file = currentFile();
        String startText = started <= 0 ? "--" : new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(started));
        sessionView.setText("開始時間：" + startText
                + "\n記錄檔：" + (file == null ? "--" : file.getName())
                + "\n檔案大小：" + (file == null || !file.isFile()
                ? "--" : String.format(Locale.getDefault(), "%.1f KB", file.length() / 1024d)));
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        exportButton.setEnabled(file != null && file.isFile());
        startButton.setAlpha(active ? 0.5f : 1f);
        stopButton.setAlpha(active ? 1f : 0.5f);
        exportButton.setAlpha(exportButton.isEnabled() ? 1f : 0.5f);
    }

    private File currentFile() {
        String name = preferences.getString(ChargingMonitorService.KEY_SESSION_FILE, "");
        if (name.isEmpty() || !new File(name).getName().equals(name)) return null;
        return new File(ChargingMonitorService.logsDirectory(this), name);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.background(Color.WHITE, 14, this));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        return panel;
    }

    private void addPanel(LinearLayout content, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 14);
        content.addView(panel, params);
    }

    private Button actionButton(String label, String background, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 14, this));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        params.topMargin = Ui.dp(this, topMarginDp);
        return params;
    }
}
