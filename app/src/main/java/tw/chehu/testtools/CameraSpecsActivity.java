package tw.chehu.testtools;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CameraSpecsActivity extends Activity {
    private static final int REQUEST_EXPORT = 7801;
    private final LinkedHashMap<String, LinkedHashMap<String, String>> sections =
            new LinkedHashMap<>();
    private String exportText = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().setNavigationBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        collect();
        render();
    }

    private void collect() {
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            put(summary, "系統公開相機數量", String.valueOf(ids.length));
            put(summary, "Camera2 API", "Android 公開能力資料；實際可用組合仍受相機 App 與編碼器限制");
            sections.put("總覽", summary);
            for (String id : ids) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                String title = "相機 " + id + " · " + facing(
                        characteristics.get(CameraCharacteristics.LENS_FACING));
                sections.put(title, cameraDetails(id, characteristics));
            }
        } catch (Exception error) {
            put(summary, "讀取結果", "系統限制或相機服務錯誤：" + error.getClass().getSimpleName());
            sections.put("總覽", summary);
        }
        StringBuilder text = new StringBuilder("TestTools 相機規格檢測\n");
        text.append("裝置：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        text.append("產生時間：").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n\n");
        for (Map.Entry<String, LinkedHashMap<String, String>> section : sections.entrySet()) {
            text.append("【").append(section.getKey()).append("】\n");
            for (Map.Entry<String, String> item : section.getValue().entrySet()) {
                text.append(item.getKey()).append("：").append(item.getValue()).append('\n');
            }
            text.append('\n');
        }
        exportText = text.toString();
    }

    private LinkedHashMap<String, String> cameraDetails(String id, CameraCharacteristics c) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        put(out, "Camera ID", id);
        put(out, "鏡頭方向", facing(c.get(CameraCharacteristics.LENS_FACING)));
        put(out, "Camera2 硬體等級", hardwareLevel(c.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orientation != null) put(out, "感光元件方向", orientation + "°");

        Size pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (pixelArray != null) put(out, "感光元件像素陣列", sizeWithMp(pixelArray));
        if (active != null) put(out, "有效感光區", active.width() + " × " + active.height()
                + "（約 " + decimal(active.width() * active.height() / 1_000_000d) + " MP）");
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (physical != null) {
            double diagonal = Math.hypot(physical.getWidth(), physical.getHeight());
            put(out, "感光元件實體尺寸", decimal(physical.getWidth()) + " × "
                    + decimal(physical.getHeight()) + " mm（對角 " + decimal(diagonal) + " mm）");
        }

        float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focalLengths != null && focalLengths.length > 0) {
            put(out, "實體焦距", floatList(focalLengths, " mm"));
            if (physical != null) put(out, "估算 35mm 等效焦距",
                    equivalentFocalLengths(focalLengths, physical));
        }
        float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (apertures != null && apertures.length > 0) put(out, "光圈", prefixedFloats(apertures, "f/"));
        Float minimumFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minimumFocus != null) put(out, "最近對焦距離", minimumFocus <= 0
                ? "固定焦點／系統未提供" : "約 " + decimal(100d / minimumFocus) + " cm");
        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        put(out, "閃光燈", Boolean.TRUE.equals(flash) ? "支援" : "未回報支援");
        Float maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (maxZoom != null) put(out, "最大數位變焦", decimal(maxZoom) + "x");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) put(out, "縮放比例範圍", Api30.zoomRange(c));

        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        put(out, "主要能力", capabilityNames(capabilities));
        put(out, "RAW", contains(capabilities,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ? "支援" : "未回報支援");
        put(out, "手動感光控制", contains(capabilities,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                ? "支援" : "未回報支援");
        put(out, "連拍", contains(capabilities,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
                ? "支援" : "未回報支援");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String physicalIds = Api28.physicalCameraIds(c);
            if (!physicalIds.isEmpty()) put(out, "邏輯多鏡頭包含", physicalIds);
        }

        put(out, "OIS 光學防手震", ois(c));
        put(out, "EIS 電子防手震", eis(c));
        put(out, "自動對焦模式", afModes(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)));
        put(out, "曝光 FPS 範圍", fpsRanges(c.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            put(out, "JPEG 照片尺寸", sizeList(map.getOutputSizes(ImageFormat.JPEG), 12));
            try {
                put(out, "錄影輸出尺寸", sizeList(map.getOutputSizes(MediaRecorder.class), 12));
            } catch (RuntimeException ignored) {
                put(out, "錄影輸出尺寸", "系統未提供");
            }
            put(out, "高速錄影", highSpeed(map));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            put(out, "動態範圍／HDR Profiles", Api33.dynamicRanges(c));
        }
        return out;
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
        back.setMinHeight(Ui.dp(this, 40));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "相機規格檢測", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "讀取 Camera2 公開能力。廠商相機 App 的合成像素、AI 模式與專屬錄影功能可能不會完整公開。點選任一資料可複製。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        content.addView(intro);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = actionButton("複製全部", "#2563EB", Color.WHITE);
        Button export = actionButton("匯出 TXT", "#DCFCE7", Ui.color("#166534"));
        actions.addView(copy, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        exportParams.leftMargin = Ui.dp(this, 10);
        actions.addView(export, exportParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.bottomMargin = Ui.dp(this, 12);
        content.addView(actions, actionsParams);
        copy.setOnClickListener(v -> copyText("完整相機規格", exportText));
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
                addRow(panel, item.getKey(), item.getValue());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = Ui.dp(this, 8);
            content.addView(panel, params);
        }
        setContentView(scroll);
    }

    private void addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
        row.addView(Ui.text(this, label, 13, Ui.color("#64748B"), false));
        TextView detail = Ui.text(this, value, 15, Ui.color("#0F172A"), true);
        detail.setPadding(0, Ui.dp(this, 3), 0, 0);
        row.addView(detail);
        row.setOnClickListener(v -> copyText(label, value));
        parent.addView(row);
    }

    private void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "已複製：" + label, Toast.LENGTH_SHORT).show();
    }

    private void startExport() {
        String model = sanitize(Build.MODEL);
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, model + "_相機規格_" + time + ".txt");
        startActivityForResult(create, REQUEST_EXPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("無法開啟檔案");
            output.write(exportText.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "相機規格已匯出", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "匯出失敗：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Button actionButton(String label, String background, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 13, this));
        return button;
    }

    private static void put(LinkedHashMap<String, String> out, String key, String value) {
        out.put(key, value == null || value.trim().isEmpty() ? "未知／系統未提供" : value.trim());
    }

    private static String facing(Integer value) {
        if (value == null) return "方向未知";
        if (value == CameraCharacteristics.LENS_FACING_FRONT) return "前置";
        if (value == CameraCharacteristics.LENS_FACING_BACK) return "後置";
        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) return "外接";
        return "方向未知";
    }

    private static String hardwareLevel(Integer value) {
        if (value == null) return "未知";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "EXTERNAL";
        return String.valueOf(value);
    }

    private static String capabilityNames(int[] values) {
        if (values == null || values.length == 0) return "系統未提供";
        List<String> names = new ArrayList<>();
        for (int value : values) {
            if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) names.add("一般相機");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) names.add("手動感光");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) names.add("手動後製");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) names.add("RAW");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE) names.add("連拍");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) names.add("深度輸出");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) names.add("高速錄影");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) names.add("邏輯多鏡頭");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) names.add("超高解析度感光元件");
            else if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) names.add("10-bit 動態範圍");
            else names.add("能力 " + value);
        }
        return String.join("、", names);
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) if (value == target) return true;
        return false;
    }

    private static String ois(CameraCharacteristics c) {
        int[] values = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        return contains(values, CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
                ? "支援" : "未回報支援";
    }

    private static String eis(CameraCharacteristics c) {
        int[] values = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        return contains(values, CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                ? "支援" : "未回報支援";
    }

    private static String afModes(int[] values) {
        if (values == null || values.length == 0) return "系統未提供";
        List<String> names = new ArrayList<>();
        for (int value : values) {
            if (value == CameraCharacteristics.CONTROL_AF_MODE_OFF) names.add("關閉／手動");
            else if (value == CameraCharacteristics.CONTROL_AF_MODE_AUTO) names.add("Auto");
            else if (value == CameraCharacteristics.CONTROL_AF_MODE_MACRO) names.add("Macro");
            else if (value == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO) names.add("連續錄影");
            else if (value == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) names.add("連續拍照");
            else if (value == CameraCharacteristics.CONTROL_AF_MODE_EDOF) names.add("EDOF");
        }
        return names.isEmpty() ? "系統未提供" : String.join("、", names);
    }

    private static String fpsRanges(Range<Integer>[] values) {
        if (values == null || values.length == 0) return "系統未提供";
        List<String> ranges = new ArrayList<>();
        for (Range<Integer> value : values) ranges.add(value.getLower() + "–" + value.getUpper() + " fps");
        return String.join("、", ranges);
    }

    private static String highSpeed(StreamConfigurationMap map) {
        try {
            Size[] sizes = map.getHighSpeedVideoSizes();
            if (sizes == null || sizes.length == 0) return "未回報支援";
            Arrays.sort(sizes, Comparator.comparingLong(CameraSpecsActivity::area).reversed());
            List<String> values = new ArrayList<>();
            for (int index = 0; index < Math.min(8, sizes.length); index++) {
                Size size = sizes[index];
                Range<Integer>[] fps = map.getHighSpeedVideoFpsRangesFor(size);
                int maximum = 0;
                for (Range<Integer> range : fps) maximum = Math.max(maximum, range.getUpper());
                values.add(size.getWidth() + "×" + size.getHeight() + " @ 最高 " + maximum + " fps");
            }
            return String.join("、", values);
        } catch (RuntimeException error) {
            return "系統未提供";
        }
    }

    private static String sizeList(Size[] sizes, int limit) {
        if (sizes == null || sizes.length == 0) return "系統未提供";
        Arrays.sort(sizes, Comparator.comparingLong(CameraSpecsActivity::area).reversed());
        List<String> values = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, sizes.length); index++) {
            Size size = sizes[index];
            values.add(size.getWidth() + "×" + size.getHeight());
        }
        if (sizes.length > limit) values.add("另有 " + (sizes.length - limit) + " 種");
        return String.join("、", values);
    }

    private static String sizeWithMp(Size size) {
        return size.getWidth() + " × " + size.getHeight() + "（約 "
                + decimal(area(size) / 1_000_000d) + " MP）";
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static String equivalentFocalLengths(float[] focalLengths, SizeF sensor) {
        double diagonal = Math.hypot(sensor.getWidth(), sensor.getHeight());
        if (diagonal <= 0) return "系統未提供";
        List<String> values = new ArrayList<>();
        for (float focal : focalLengths) values.add(decimal(focal * 43.266615d / diagonal) + " mm");
        return String.join("、", values) + "（依感光元件尺寸估算）";
    }

    private static String floatList(float[] values, String suffix) {
        List<String> result = new ArrayList<>();
        for (float value : values) result.add(decimal(value) + suffix);
        return String.join("、", result);
    }

    private static String prefixedFloats(float[] values, String prefix) {
        List<String> result = new ArrayList<>();
        for (float value : values) result.add(prefix + decimal(value));
        return String.join("、", result);
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) return "Android";
        return value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static final class Api28 {
        @TargetApi(Build.VERSION_CODES.P)
        static String physicalCameraIds(CameraCharacteristics c) {
            Set<String> ids = c.getPhysicalCameraIds();
            return ids == null || ids.isEmpty() ? "" : String.join("、", ids);
        }
    }

    private static final class Api30 {
        @TargetApi(Build.VERSION_CODES.R)
        static String zoomRange(CameraCharacteristics c) {
            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            return range == null ? "系統未提供"
                    : decimal(range.getLower()) + "x–" + decimal(range.getUpper()) + "x";
        }
    }

    private static final class Api33 {
        @TargetApi(Build.VERSION_CODES.TIRAMISU)
        static String dynamicRanges(CameraCharacteristics c) {
            DynamicRangeProfiles profiles = c.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            if (profiles == null || profiles.getSupportedProfiles().isEmpty()) return "系統未提供";
            Map<Long, String> names = profileNames();
            List<String> values = new ArrayList<>();
            for (Long profile : profiles.getSupportedProfiles()) {
                values.add(names.getOrDefault(profile, "Profile " + profile));
            }
            return String.join("、", values);
        }

        @TargetApi(Build.VERSION_CODES.TIRAMISU)
        private static Map<Long, String> profileNames() {
            LinkedHashMap<Long, String> names = new LinkedHashMap<>();
            for (Field field : DynamicRangeProfiles.class.getFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == long.class) {
                        names.put(field.getLong(null), field.getName().replace('_', ' '));
                    }
                } catch (IllegalAccessException ignored) {}
            }
            return names;
        }
    }
}
