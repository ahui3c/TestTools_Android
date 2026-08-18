package tw.chehu.testtools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DeviceInfoActivity extends Activity {
    private static final int REQUEST_EXPORT = 7401;
    private final LinkedHashMap<String, LinkedHashMap<String, String>> sections =
            new LinkedHashMap<>();
    private String exportText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().setNavigationBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        collectInformation();
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 16, Ui.color("#2563EB"), true);
        back.setPadding(0, 0, 0, Ui.dp(this, 16));
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "手機資訊", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "點選任一資料即可複製該項內容。部分序號、IMEI、電池設計容量及相機細節受 Android 或廠商限制，無法保證取得。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 16));
        content.addView(intro);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copyAll = actionButton("複製全部", "#2563EB", Color.WHITE);
        Button export = actionButton("匯出 TXT", "#DCFCE7", Ui.color("#166534"));
        actions.addView(copyAll, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1);
        exportParams.leftMargin = Ui.dp(this, 10);
        actions.addView(export, exportParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.bottomMargin = Ui.dp(this, 16);
        content.addView(actions, actionsParams);
        copyAll.setOnClickListener(v -> copy("完整手機資訊", exportText));
        export.setOnClickListener(v -> startExport());

        for (Map.Entry<String, LinkedHashMap<String, String>> section : sections.entrySet()) {
            TextView heading = Ui.text(this, section.getKey(), 17, Ui.color("#0F172A"), true);
            heading.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, Ui.dp(this, 8));
            content.addView(heading);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(Ui.background(Color.WHITE, 15, this));
            panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
            for (Map.Entry<String, String> item : section.getValue().entrySet()) {
                addInfoRow(panel, item.getKey(), item.getValue());
            }
            LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
            panelParams.bottomMargin = Ui.dp(this, 8);
            content.addView(panel, panelParams);
        }
        setContentView(scroll);
    }

    private void addInfoRow(LinearLayout panel, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        TextView name = Ui.text(this, label, 13, Ui.color("#64748B"), false);
        TextView detail = Ui.text(this, value, 15, Ui.color("#0F172A"), true);
        detail.setTextIsSelectable(false);
        detail.setPadding(0, Ui.dp(this, 3), 0, 0);
        row.addView(name);
        row.addView(detail);
        row.setBackground(Ui.background(Ui.color("#FFFFFFFF"), 8, this));
        row.setOnClickListener(v -> copy(label, value));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void collectInformation() {
        section("裝置與系統", deviceAndSystem());
        section("處理器", processor());
        section("記憶體與儲存空間", memoryAndStorage());
        section("螢幕", screen());
        section("電池", battery());
        section("相機", cameras());
        section("識別資訊與限制", identifiers());
        StringBuilder text = new StringBuilder();
        text.append("TestTools 手機資訊\n")
                .append("產生時間：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
                .append("\n\n");
        for (Map.Entry<String, LinkedHashMap<String, String>> section : sections.entrySet()) {
            text.append("【").append(section.getKey()).append("】\n");
            for (Map.Entry<String, String> item : section.getValue().entrySet()) {
                text.append(item.getKey()).append("：").append(item.getValue()).append('\n');
            }
            text.append('\n');
        }
        exportText = text.toString();
    }

    private void section(String title, LinkedHashMap<String, String> values) {
        sections.put(title, values);
    }

    private LinkedHashMap<String, String> deviceAndSystem() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        put(out, "品牌", Build.BRAND);
        put(out, "製造商", Build.MANUFACTURER);
        put(out, "型號", Build.MODEL);
        put(out, "產品名稱", Build.PRODUCT);
        put(out, "裝置代號", Build.DEVICE);
        put(out, "主機板", Build.BOARD);
        put(out, "硬體代號", Build.HARDWARE);
        put(out, "Android 版本", Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）");
        put(out, "安全性更新", Build.VERSION.SECURITY_PATCH);
        put(out, "建置版本", Build.DISPLAY);
        put(out, "Build ID", Build.ID);
        put(out, "Build 類型", Build.TYPE + " / " + Build.TAGS);
        put(out, "系統指紋", Build.FINGERPRINT);
        put(out, "核心版本", System.getProperty("os.version"));
        put(out, "語言／地區", Locale.getDefault().toLanguageTag());
        return out;
    }

    private LinkedHashMap<String, String> processor() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put(out, "SoC 製造商", Build.SOC_MANUFACTURER);
            put(out, "SoC 型號", Build.SOC_MODEL);
        }
        put(out, "CPU 硬體資訊", cpuInfo());
        put(out, "核心數量", String.valueOf(Runtime.getRuntime().availableProcessors()));
        put(out, "最高時脈", cpuMaxFrequency());
        put(out, "支援 ABI", join(Build.SUPPORTED_ABIS));
        put(out, "64 位元 ABI", join(Build.SUPPORTED_64_BIT_ABIS));
        put(out, "32 位元 ABI", join(Build.SUPPORTED_32_BIT_ABIS));
        return out;
    }

    private LinkedHashMap<String, String> memoryAndStorage() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        put(out, "實體記憶體總量", bytes(info.totalMem));
        put(out, "目前可用記憶體", bytes(info.availMem));
        put(out, "系統低記憶體門檻", bytes(info.threshold));
        StatFs internal = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        put(out, "內部儲存空間總量", bytes(internal.getTotalBytes()));
        put(out, "內部儲存空間可用", bytes(internal.getAvailableBytes()));
        return out;
    }

    @SuppressWarnings("deprecation")
    private LinkedHashMap<String, String> screen() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        put(out, "實際解析度", metrics.widthPixels + " × " + metrics.heightPixels + " px");
        put(out, "像素密度", metrics.densityDpi + " dpi（" + format(metrics.density) + "x）");
        put(out, "邏輯顯示尺寸", format(metrics.widthPixels / metrics.density) + " × " +
                format(metrics.heightPixels / metrics.density) + " dp");
        double widthInches = metrics.xdpi > 0 ? metrics.widthPixels / metrics.xdpi : 0;
        double heightInches = metrics.ydpi > 0 ? metrics.heightPixels / metrics.ydpi : 0;
        if (widthInches > 0 && heightInches > 0)
            put(out, "估算對角線", format(Math.hypot(widthInches, heightInches)) + " 吋（依系統 DPI 估算）");
        Display.Mode mode = display.getMode();
        put(out, "目前更新率", format(mode.getRefreshRate()) + " Hz");
        List<String> modes = new ArrayList<>();
        for (Display.Mode supported : display.getSupportedModes()) {
            String value = supported.getPhysicalWidth() + "×" + supported.getPhysicalHeight() + " @ " +
                    format(supported.getRefreshRate()) + "Hz";
            if (!modes.contains(value)) modes.add(value);
        }
        put(out, "支援顯示模式", join(modes));
        return out;
    }

    private LinkedHashMap<String, String> battery() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Intent value = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (value == null) {
            put(out, "電池資訊", "系統未提供");
            return out;
        }
        int level = value.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = value.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        float percent = scale > 0 ? level * 100f / scale : -1;
        put(out, "目前電量", percent < 0 ? "未知" : Math.round(percent) + "%");
        put(out, "狀態", batteryStatus(value.getIntExtra(BatteryManager.EXTRA_STATUS, 1)));
        put(out, "健康度", batteryHealth(value.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)));
        put(out, "供電來源", plugged(value.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));
        put(out, "技術", value.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
        put(out, "溫度", format(value.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f) + " °C");
        put(out, "電壓", value.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV");
        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (chargeCounter > 0) {
            put(out, "目前剩餘容量", format(chargeCounter / 1000f) + " mAh");
            if (percent > 1) put(out, "估算完整容量", format(chargeCounter / 10f / percent) +
                    " mAh（依目前電量估算，非原廠設計值）");
        } else {
            put(out, "電池容量", "Android 公開 API／此裝置未提供");
        }
        return out;
    }

    private LinkedHashMap<String, String> cameras() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            put(out, "相機數量", String.valueOf(ids.length));
            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String prefix = "相機 " + id + "（" + cameraFacing(facing) + "）";
                Rect sensor = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (sensor != null) {
                    double mp = sensor.width() * sensor.height() / 1_000_000d;
                    put(out, prefix + "感光區", sensor.width() + " × " + sensor.height() + "（約 " + format(mp) + " MP）");
                }
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focal != null) put(out, prefix + "焦距", join(focal) + " mm");
                float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                if (apertures != null) put(out, prefix + "光圈", "f/" + join(apertures));
                Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) put(out, prefix + "感光元件方向", orientation + "°");
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
                    if (jpeg != null && jpeg.length > 0) put(out, prefix + "最大照片輸出", largest(jpeg));
                }
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level != null) put(out, prefix + "Camera2 等級", cameraLevel(level));
            }
        } catch (Exception error) {
            put(out, "相機資訊", "系統限制無法讀取：" + error.getClass().getSimpleName());
        }
        return out;
    }

    private LinkedHashMap<String, String> identifiers() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        put(out, "Android ID（此 App／使用者）", androidId);
        put(out, "裝置序號", deviceSerial());
        put(out, "IMEI／MEID", "受 Android 電話權限與裝置管理限制，本工具預設不讀取");
        put(out, "Wi-Fi MAC 位址", "Android 會使用隨機化／受限 MAC，本工具不顯示不可靠值");
        return out;
    }

    @SuppressLint("MissingPermission")
    private static String deviceSerial() {
        try {
            String serial = Build.getSerial();
            if (serial != null && !serial.trim().isEmpty() &&
                    !Build.UNKNOWN.equalsIgnoreCase(serial)) return serial;
        } catch (SecurityException ignored) {
            // Android 10+ 通常只允許系統、電信業者或裝置管理程式取得硬體序號。
        }
        return "系統限制無法取得（Android 10 以上通常僅系統／裝置管理程式可讀取）";
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "已複製：" + label, Toast.LENGTH_SHORT).show();
    }

    private void startExport() {
        String model = sanitize(Build.MODEL);
        String date = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, model + "_手機資訊_" + date + ".txt");
        startActivityForResult(create, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("無法開啟檔案");
            output.write(exportText.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "手機資訊已匯出", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "匯出失敗：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Button actionButton(String text, String background, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.background(Ui.color(background), 13, this));
        return button;
    }

    private static void put(LinkedHashMap<String, String> target, String key, String value) {
        target.put(key, value == null || value.trim().isEmpty() ? "未知／系統未提供" : value.trim());
    }

    private static String cpuInfo() {
        File file = new File("/proc/cpuinfo");
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith("hardware") || lower.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) return line.substring(colon + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return Build.HARDWARE;
    }

    private static String cpuMaxFrequency() {
        long maximum = 0;
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            File file = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                maximum = Math.max(maximum, Long.parseLong(reader.readLine().trim()));
            } catch (Exception ignored) {}
        }
        return maximum > 0 ? format(maximum / 1_000_000d) + " GHz（系統回報）" : "系統未提供";
    }

    private static String largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size size : sizes) if ((long) size.getWidth() * size.getHeight() >
                (long) best.getWidth() * best.getHeight()) best = size;
        return best.getWidth() + " × " + best.getHeight() + "（約 " +
                format(best.getWidth() * best.getHeight() / 1_000_000d) + " MP）";
    }

    private static String cameraFacing(Integer value) {
        if (value == null) return "方向未知";
        if (value == CameraCharacteristics.LENS_FACING_FRONT) return "前置";
        if (value == CameraCharacteristics.LENS_FACING_BACK) return "後置";
        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) return "外接";
        return "方向未知";
    }

    private static String cameraLevel(int value) {
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "EXTERNAL";
        return String.valueOf(value);
    }

    private static String batteryStatus(int value) {
        if (value == BatteryManager.BATTERY_STATUS_CHARGING) return "充電中";
        if (value == BatteryManager.BATTERY_STATUS_DISCHARGING) return "放電中";
        if (value == BatteryManager.BATTERY_STATUS_FULL) return "已充滿";
        if (value == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "未充電";
        return "未知";
    }

    private static String batteryHealth(int value) {
        if (value == BatteryManager.BATTERY_HEALTH_GOOD) return "良好";
        if (value == BatteryManager.BATTERY_HEALTH_OVERHEAT) return "過熱";
        if (value == BatteryManager.BATTERY_HEALTH_COLD) return "溫度過低";
        if (value == BatteryManager.BATTERY_HEALTH_DEAD) return "失效";
        if (value == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) return "過電壓";
        if (value == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE) return "未指定故障";
        return "未知";
    }

    private static String plugged(int value) {
        List<String> values = new ArrayList<>();
        if ((value & BatteryManager.BATTERY_PLUGGED_AC) != 0) values.add("AC");
        if ((value & BatteryManager.BATTERY_PLUGGED_USB) != 0) values.add("USB");
        if ((value & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) values.add("無線充電");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (value & BatteryManager.BATTERY_PLUGGED_DOCK) != 0) values.add("底座");
        return values.isEmpty() ? "未連接外部電源" : join(values);
    }

    private static String bytes(long value) {
        double gb = value / 1_073_741_824d;
        return format(gb) + " GB（" + value + " bytes）";
    }

    private static String join(String[] values) { return join(Arrays.asList(values)); }
    private static String join(List<String> values) { return android.text.TextUtils.join("、", values); }
    private static String join(float[] values) {
        List<String> out = new ArrayList<>();
        for (float value : values) out.add(format(value));
        return join(out);
    }
    private static String format(double value) { return String.format(Locale.US, "%.2f", value).replaceAll("\\.?0+$", ""); }
    private static String sanitize(String value) { return (value == null ? "Android" : value).replaceAll("[\\\\/:*?\"<>|]", "_"); }
}
