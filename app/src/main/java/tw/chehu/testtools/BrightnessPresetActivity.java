package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BrightnessPresetActivity extends Activity {
    private static final int REQUEST_WRITE_SETTINGS = 4401;
    private TextView currentValueView;
    private TextView currentModeView;
    private EditText nameInput;
    private LinearLayout savedList;
    private BrightnessPresetStore.Preset pendingApply;
    private final ContentObserver brightnessObserver = new ContentObserver(new Handler()) {
        @Override public void onChange(boolean selfChange) { refreshCurrentBrightness(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        content.addView(Ui.text(this, "亮度快速紀錄", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this, "儲存目前系統亮度，之後可一鍵切回相同設定。AndroidADBTools 自動校正完成的結果也會出現在這裡。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 18));
        content.addView(intro);

        LinearLayout currentPanel = panel();
        TextView label = Ui.text(this, "目前系統亮度", 14, Ui.color("#64748B"), true);
        currentPanel.addView(label);
        currentValueView = Ui.text(this, "讀取中…", 30, Ui.color("#2563EB"), true);
        currentValueView.setPadding(0, Ui.dp(this, 5), 0, 0);
        currentPanel.addView(currentValueView);
        currentModeView = Ui.text(this, "", 13, Ui.color("#64748B"), false);
        currentModeView.setPadding(0, Ui.dp(this, 3), 0, 0);
        currentPanel.addView(currentModeView);
        Button refresh = button("重新讀取", "#E8F0FE", "#2563EB");
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        refreshParams.topMargin = Ui.dp(this, 12);
        currentPanel.addView(refresh, refreshParams);
        refresh.setOnClickListener(v -> refreshCurrentBrightness());
        addPanel(content, currentPanel);

        TextView saveTitle = Ui.text(this, "儲存目前設定", 16, Ui.color("#0F172A"), true);
        saveTitle.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        content.addView(saveTitle);
        LinearLayout savePanel = panel();
        nameInput = new EditText(this);
        nameInput.setHint("名稱（可留空）");
        nameInput.setSingleLine(true);
        savePanel.addView(nameInput, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        Button save = button("儲存目前亮度", "#2563EB", "#FFFFFF");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        saveParams.topMargin = Ui.dp(this, 8);
        savePanel.addView(save, saveParams);
        save.setOnClickListener(v -> saveCurrent());
        addPanel(content, savePanel);

        TextView presetsTitle = Ui.text(this, "已儲存的亮度", 16, Ui.color("#0F172A"), true);
        presetsTitle.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        content.addView(presetsTitle);
        savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        content.addView(savedList, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver);
        if (pendingApply != null && Settings.System.canWrite(this)) {
            BrightnessPresetStore.Preset preset = pendingApply;
            pendingApply = null;
            applyPreset(preset);
        }
        refreshCurrentBrightness();
        rebuildSavedList();
    }

    @Override
    protected void onPause() {
        getContentResolver().unregisterContentObserver(brightnessObserver);
        super.onPause();
    }

    private void refreshCurrentBrightness() {
        int value = readBrightness();
        int maximum = Math.max(detectBrightnessMaximum(), value);
        int percent = maximum > 0 ? Math.round(value * 100f / maximum) : 0;
        currentValueView.setText(value >= 0 ? value + "  ·  " + percent + "%" : "無法讀取");
        int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        currentModeView.setText((mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                ? "自動亮度已開啟；數值可能隨環境改變"
                : "手動亮度模式") + "  ·  系統範圍 0–" + maximum);
    }

    private void saveCurrent() {
        int value = readBrightness();
        if (value < 0) {
            Toast.makeText(this, "無法讀取目前亮度", Toast.LENGTH_SHORT).show();
            return;
        }
        BrightnessPresetStore.add(this, nameInput.getText().toString(), value,
                Math.max(detectBrightnessMaximum(), value), Double.NaN, Double.NaN, "TestTools 手動紀錄");
        nameInput.setText("");
        rebuildSavedList();
        Toast.makeText(this, "已儲存亮度 " + value, Toast.LENGTH_SHORT).show();
    }

    private void rebuildSavedList() {
        savedList.removeAllViews();
        List<BrightnessPresetStore.Preset> presets = BrightnessPresetStore.load(this);
        if (presets.isEmpty()) {
            TextView empty = Ui.text(this, "尚未儲存亮度設定", 14, Ui.color("#64748B"), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 22));
            savedList.addView(empty);
            return;
        }
        for (BrightnessPresetStore.Preset preset : presets) addPresetCard(preset);
    }

    private void addPresetCard(BrightnessPresetStore.Preset preset) {
        LinearLayout card = panel();
        card.addView(Ui.text(this, preset.name, 17, Ui.color("#0F172A"), true));
        int percent = preset.maximum > 0 ? Math.round(preset.value * 100f / preset.maximum) : 0;
        String valueLine = "亮度 " + preset.value + " / " + preset.maximum + "  ·  " + percent + "%";
        if (Double.isFinite(preset.measuredNit)) valueLine += "  ·  " + formatNit(preset.measuredNit) + " nit";
        TextView value = Ui.text(this, valueLine, 15, Ui.color("#2563EB"), true);
        value.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(value);
        String time = preset.savedAt > 0
                ? new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(new Date(preset.savedAt)) : "";
        TextView meta = Ui.text(this, preset.source + (time.isEmpty() ? "" : "  ·  " + time),
                12, Ui.color("#64748B"), false);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(this, 12), 0, 0);
        Button apply = button("套用", "#2563EB", "#FFFFFF");
        actions.addView(apply, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        Button delete = button("刪除", "#FEE2E2", "#B91C1C");
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        deleteParams.leftMargin = Ui.dp(this, 8);
        actions.addView(delete, deleteParams);
        card.addView(actions);
        apply.setOnClickListener(v -> requestApply(preset));
        delete.setOnClickListener(v -> {
            BrightnessPresetStore.remove(this, preset.id);
            rebuildSavedList();
            Toast.makeText(this, "已刪除「" + preset.name + "」", Toast.LENGTH_SHORT).show();
        });
        addPanel(savedList, card);
    }

    private void requestApply(BrightnessPresetStore.Preset preset) {
        if (!Settings.System.canWrite(this)) {
            pendingApply = preset;
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())), REQUEST_WRITE_SETTINGS);
                Toast.makeText(this, "請允許修改系統設定，返回後會自動套用", Toast.LENGTH_LONG).show();
            } catch (RuntimeException error) {
                pendingApply = null;
                Toast.makeText(this, "無法開啟修改系統設定權限頁面", Toast.LENGTH_LONG).show();
            }
            return;
        }
        applyPreset(preset);
    }

    private void applyPreset(BrightnessPresetStore.Preset preset) {
        boolean modeSaved;
        boolean brightnessSaved;
        try {
            modeSaved = Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            brightnessSaved = Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    preset.value);
        } catch (SecurityException error) {
            modeSaved = false;
            brightnessSaved = false;
        }
        refreshCurrentBrightness();
        Toast.makeText(this, modeSaved && brightnessSaved
                ? "已套用「" + preset.name + "」並切換為手動亮度"
                : "無法套用亮度設定", Toast.LENGTH_SHORT).show();
    }

    private int readBrightness() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
    }

    private int detectBrightnessMaximum() {
        int id = getResources().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
        if (id != 0) {
            try { return Math.max(1, getResources().getInteger(id)); }
            catch (RuntimeException ignored) {}
        }
        return 255;
    }

    private String formatNit(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.TAIWAN, "%.1f", value);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.background(Color.WHITE, 14, this));
        panel.setPadding(Ui.dp(this, 15), Ui.dp(this, 14), Ui.dp(this, 15), Ui.dp(this, 14));
        return panel;
    }

    private void addPanel(LinearLayout parent, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        parent.addView(panel, params);
    }

    private Button button(String text, String background, String foreground) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Ui.color(foreground));
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 12, this));
        return button;
    }
}
