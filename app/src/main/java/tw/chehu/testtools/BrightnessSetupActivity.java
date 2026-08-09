package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BrightnessSetupActivity extends Activity {
    private static final String PREFS = "brightness_settings";
    private static final String KEY_AVAILABLE = "available_percentages";
    private static final String KEY_SELECTED = "selected_percentages";
    private static final String KEY_CIRCLE = "circle";
    private static final String KEY_MAX_BRIGHTNESS = "max_brightness";
    private static final String KEY_CUSTOM_VALUE = "custom_value";
    private final ArrayList<Integer> available = new ArrayList<>();
    private final ArrayList<CheckBox> percentageChecks = new ArrayList<>();
    private LinearLayout percentageRow;
    private EditText customInput;
    private CheckBox maximumBrightness;
    private RadioGroup shapeGroup;
    private int circleButtonId;
    private SharedPreferences preferences;
    private boolean settingsReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<Integer> savedSelected = loadSettings();

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
        content.addView(Ui.text(this, "亮度測試設定", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this, "測試期間會保持螢幕喚醒。向任一方向滑動可切換比例；點選比例旁的 × 可刪除。", 14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 24));
        content.addView(intro);

        addSectionTitle(content, "顯示形狀");
        shapeGroup = new RadioGroup(this);
        shapeGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton square = new RadioButton(this);
        square.setText("方形");
        square.setId(View.generateViewId());
        RadioButton circle = new RadioButton(this);
        circle.setText("圓形");
        circleButtonId = View.generateViewId();
        circle.setId(circleButtonId);
        boolean useCircle = preferences.getBoolean(KEY_CIRCLE, false);
        square.setChecked(!useCircle);
        circle.setChecked(useCircle);
        shapeGroup.addView(square, new RadioGroup.LayoutParams(0, -2, 1));
        shapeGroup.addView(circle, new RadioGroup.LayoutParams(0, -2, 1));
        shapeGroup.setOnCheckedChangeListener((group, checkedId) -> saveSettings());
        addPanel(content, shapeGroup);

        addSectionTitle(content, "白色顯示比例");
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        percentageRow = new LinearLayout(this);
        percentageRow.setOrientation(LinearLayout.HORIZONTAL);
        horizontal.addView(percentageRow);
        addPanel(content, horizontal);
        rebuildPercentageChecks(savedSelected);

        LinearLayout custom = new LinearLayout(this);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = new Button(this);
        minus.setText("−");
        custom.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 52)));
        customInput = new EditText(this);
        customInput.setText(String.valueOf(preferences.getInt(KEY_CUSTOM_VALUE, 15)));
        customInput.setGravity(Gravity.CENTER);
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1);
        inputParams.leftMargin = Ui.dp(this, 8);
        inputParams.rightMargin = Ui.dp(this, 8);
        custom.addView(customInput, inputParams);
        Button plus = new Button(this);
        plus.setText("＋");
        custom.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 52)));
        Button add = new Button(this);
        add.setText("加入比例");
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 52));
        addParams.leftMargin = Ui.dp(this, 8);
        custom.addView(add, addParams);
        addPanel(content, custom);

        minus.setOnClickListener(v -> changeCustom(-1));
        plus.setOnClickListener(v -> changeCustom(1));
        add.setOnClickListener(v -> addCustom());

        maximumBrightness = new CheckBox(this);
        maximumBrightness.setText("使用最高螢幕亮度");
        maximumBrightness.setTextSize(16);
        maximumBrightness.setChecked(preferences.getBoolean(KEY_MAX_BRIGHTNESS, true));
        maximumBrightness.setOnCheckedChangeListener((buttonView, isChecked) -> saveSettings());
        maximumBrightness.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        addPanel(content, maximumBrightness);

        Button start = new Button(this);
        start.setText("開始全螢幕測試");
        start.setTextSize(17);
        start.setTextColor(Color.WHITE);
        start.setAllCaps(false);
        start.setBackground(Ui.background(Ui.color("#2563EB"), 14, this));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        startParams.topMargin = Ui.dp(this, 14);
        content.addView(start, startParams);
        start.setOnClickListener(v -> startTest());

        setContentView(scroll);
        settingsReady = true;
        saveSettings();
    }

    @Override
    protected void onPause() {
        saveSettings();
        super.onPause();
    }

    private void addSectionTitle(LinearLayout content, String title) {
        TextView view = Ui.text(this, title, 15, Ui.color("#334155"), true);
        view.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        content.addView(view);
    }

    private void addPanel(LinearLayout content, View child) {
        LinearLayout panel = new LinearLayout(this);
        panel.setBackground(Ui.background(Color.WHITE, 14, this));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        panel.addView(child, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 14);
        content.addView(panel, params);
    }

    private void rebuildPercentageChecks(Set<Integer> checkedValues) {
        percentageRow.removeAllViews();
        percentageChecks.clear();
        Collections.sort(available);
        for (Integer value : available) {
            LinearLayout option = new LinearLayout(this);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setBackground(Ui.background(Ui.color("#EFF6FF"), 12, this));
            option.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 3), 0);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
            optionParams.rightMargin = Ui.dp(this, 8);
            percentageRow.addView(option, optionParams);

            CheckBox check = new CheckBox(this);
            check.setText(value + "%");
            check.setTextSize(15);
            check.setChecked(checkedValues == null || checkedValues.contains(value));
            check.setTag(value);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> saveSettings());
            percentageChecks.add(check);
            option.addView(check, new LinearLayout.LayoutParams(-2, -1));

            TextView remove = Ui.text(this, "×", 22, Ui.color("#64748B"), false);
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("刪除 " + value + "%");
            option.addView(remove, new LinearLayout.LayoutParams(Ui.dp(this, 38), -1));
            remove.setOnClickListener(v -> removePercentage(value));
        }
    }

    private Set<Integer> checkedValues() {
        Set<Integer> result = new HashSet<>();
        for (CheckBox check : percentageChecks) {
            if (check.isChecked()) result.add((Integer) check.getTag());
        }
        return result;
    }

    private void removePercentage(int value) {
        Set<Integer> checked = checkedValues();
        checked.remove(value);
        available.remove(Integer.valueOf(value));
        rebuildPercentageChecks(checked);
        saveSettings();
    }

    private void changeCustom(int delta) {
        int value = parseCustom();
        customInput.setText(String.valueOf(Math.max(1, Math.min(100, value + delta))));
        saveSettings();
    }

    private int parseCustom() {
        try { return Integer.parseInt(customInput.getText().toString()); }
        catch (NumberFormatException ignored) { return 15; }
    }

    private void addCustom() {
        int value = parseCustom();
        if (value < 1 || value > 100) {
            Toast.makeText(this, "比例必須介於 1% 到 100%", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!available.contains(value)) {
            Set<Integer> checked = checkedValues();
            available.add(value);
            checked.add(value);
            rebuildPercentageChecks(checked);
            saveSettings();
        } else {
            Toast.makeText(this, value + "% 已經在選項中", Toast.LENGTH_SHORT).show();
        }
    }

    private void startTest() {
        ArrayList<Integer> selected = new ArrayList<>();
        for (CheckBox check : percentageChecks) if (check.isChecked()) selected.add((Integer) check.getTag());
        if (selected.isEmpty()) {
            Toast.makeText(this, "請至少選擇一個比例", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, BrightnessTestActivity.class);
        intent.putIntegerArrayListExtra(BrightnessTestActivity.EXTRA_PERCENTAGES, selected);
        intent.putExtra(BrightnessTestActivity.EXTRA_CIRCLE,
                shapeGroup.getCheckedRadioButtonId() == circleButtonId);
        intent.putExtra(BrightnessTestActivity.EXTRA_MAX_BRIGHTNESS, maximumBrightness.isChecked());
        startActivity(intent);
    }

    private Set<Integer> loadSettings() {
        String availableValue = preferences.getString(KEY_AVAILABLE, null);
        if (availableValue == null) {
            Collections.addAll(available, 5, 10, 25, 50, 75, 100);
        } else {
            available.addAll(parseValues(availableValue));
        }

        if (!preferences.contains(KEY_SELECTED)) return null;
        return new HashSet<>(parseValues(preferences.getString(KEY_SELECTED, "")));
    }

    private ArrayList<Integer> parseValues(String stored) {
        ArrayList<Integer> result = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String item : stored.split(",")) {
            try {
                int value = Integer.parseInt(item.trim());
                if (value >= 1 && value <= 100 && !result.contains(value)) result.add(value);
            } catch (NumberFormatException ignored) {
                // 略過損毀的單一設定，其餘設定仍可正常載入。
            }
        }
        return result;
    }

    private String joinValues(Iterable<Integer> values) {
        StringBuilder result = new StringBuilder();
        for (Integer value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private void saveSettings() {
        if (!settingsReady || preferences == null) return;
        Collections.sort(available);
        preferences.edit()
                .putString(KEY_AVAILABLE, joinValues(available))
                .putString(KEY_SELECTED, joinValues(checkedValues()))
                .putBoolean(KEY_CIRCLE,
                        shapeGroup != null && shapeGroup.getCheckedRadioButtonId() == circleButtonId)
                .putBoolean(KEY_MAX_BRIGHTNESS, maximumBrightness == null || maximumBrightness.isChecked())
                .putInt(KEY_CUSTOM_VALUE, customInput == null ? 15 : parseCustom())
                .apply();
    }
}
