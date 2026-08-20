package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ScreenDiagnosticsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().setNavigationBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 30));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 16, Ui.color("#2563EB"), true);
        back.setMinHeight(Ui.dp(this, 40));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "螢幕綜合測試", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "檢查壞點、亮暗階、色彩、漸層、銳利度、觸控死角與多點觸控，並顯示裝置回報的更新率與 HDR 能力。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        content.addView(intro);

        addDisplayInfo(content);

        LinearLayout options = panel();
        options.addView(Ui.text(this, "測試選項", 17, Ui.color("#0F172A"), true));
        CheckBox maxBrightness = option("圖樣測試使用最高亮度", true);
        CheckBox keepAwake = option("測試期間保持螢幕開啟", true);
        options.addView(maxBrightness);
        options.addView(keepAwake);
        addPanel(content, options);

        Button patterns = actionButton("開始全螢幕圖樣測試", "#2563EB", Color.WHITE);
        patterns.setOnClickListener(v -> startActivity(new Intent(this, ScreenPatternActivity.class)
                .putExtra(ScreenPatternActivity.EXTRA_MAX_BRIGHTNESS, maxBrightness.isChecked())
                .putExtra(ScreenPatternActivity.EXTRA_KEEP_AWAKE, keepAwake.isChecked())));
        content.addView(patterns, buttonParams());

        Button touch = actionButton("開始觸控與多點觸控測試", "#E8F0FE", Ui.color("#1D4ED8"));
        LinearLayout.LayoutParams touchParams = buttonParams();
        touchParams.topMargin = Ui.dp(this, 10);
        content.addView(touch, touchParams);
        touch.setOnClickListener(v -> startActivity(new Intent(this, TouchDiagnosticsActivity.class)
                .putExtra(TouchDiagnosticsActivity.EXTRA_KEEP_AWAKE, keepAwake.isChecked())));

        TextView help = Ui.text(this,
                "圖樣測試：點一下或向左滑切換下一張，向右滑返回上一張。\n"
                        + "觸控測試：用手指塗滿網格；雙擊左上角清除，雙擊右上角離開。",
                13, Ui.color("#64748B"), false);
        help.setBackground(Ui.background(Color.WHITE, 14, this));
        Ui.setPadding(help, 14, 12);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-1, -2);
        helpParams.topMargin = Ui.dp(this, 12);
        content.addView(help, helpParams);
        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private void addDisplayInfo(LinearLayout content) {
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = manager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Display.Mode active = display.getMode();

        LinearLayout panel = panel();
        panel.addView(Ui.text(this, "顯示器能力", 17, Ui.color("#0F172A"), true));
        addInfo(panel, "實際解析度", metrics.widthPixels + " × " + metrics.heightPixels + " px");
        addInfo(panel, "目前模式", active.getPhysicalWidth() + " × " + active.getPhysicalHeight()
                + " @ " + decimal(active.getRefreshRate()) + " Hz");
        addInfo(panel, "像素密度", metrics.densityDpi + " dpi／" + decimal(metrics.density) + "x");
        addInfo(panel, "支援更新率", supportedRefreshRates(display));
        addInfo(panel, "HDR", hdrTypes(display));
        addInfo(panel, "廣色域", display.isWideColorGamut() ? "支援" : "未回報支援");
        addInfo(panel, "顯示模式數量", String.valueOf(display.getSupportedModes().length));
        addPanel(content, panel);
    }

    private String supportedRefreshRates(Display display) {
        List<String> rates = new ArrayList<>();
        for (Display.Mode mode : display.getSupportedModes()) {
            String rate = decimal(mode.getRefreshRate()) + " Hz";
            if (!rates.contains(rate)) rates.add(rate);
        }
        return rates.isEmpty() ? "系統未提供" : String.join("、", rates);
    }

    private String hdrTypes(Display display) {
        Display.HdrCapabilities capabilities = display.getHdrCapabilities();
        if (capabilities == null) return "系統未提供";
        List<String> values = new ArrayList<>();
        for (int type : capabilities.getSupportedHdrTypes()) {
            if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) values.add("Dolby Vision");
            else if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) values.add("HDR10");
            else if (type == Display.HdrCapabilities.HDR_TYPE_HLG) values.add("HLG");
            else if (type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) values.add("HDR10+");
            else values.add("類型 " + type);
        }
        return values.isEmpty() ? "未回報支援" : String.join("、", values);
    }

    private void addInfo(LinearLayout parent, String label, String value) {
        TextView item = Ui.text(this, label + "\n" + value, 14, Ui.color("#334155"), false);
        item.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        parent.addView(item);
    }

    private CheckBox option(String label, boolean checked) {
        CheckBox option = new CheckBox(this);
        option.setText(label);
        option.setTextSize(15);
        option.setTextColor(Ui.color("#334155"));
        option.setChecked(checked);
        option.setMinHeight(Ui.dp(this, 46));
        return option;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.background(Color.WHITE, 15, this));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return panel;
    }

    private void addPanel(LinearLayout parent, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        parent.addView(panel, params);
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
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
