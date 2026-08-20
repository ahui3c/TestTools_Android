package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int COLOR_BACKGROUND = 0xFFF5F8FC;
    private static final int COLOR_INK = 0xFF0F172A;
    private static final int COLOR_MUTED = 0xFF64748B;
    private static final int COLOR_ACCENT = 0xFF2563EB;
    private static final int COLOR_ACCENT_SOFT = 0xFFE8F0FE;
    private static final int COLOR_BORDER = 0xFFDCE6F2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 24));
        scroll.addView(content);

        addHeader(content);

        addSection(content, "顯示與硬體", new ModuleItem[] {
                new ModuleItem(R.drawable.ic_module_brightness, "測試亮度", "白色面積與亮度",
                        () -> startActivity(new Intent(this, BrightnessSetupActivity.class))),
                new ModuleItem(R.drawable.ic_module_screen_test, "螢幕測試", "色彩、壞點與觸控",
                        () -> startActivity(new Intent(this, ScreenDiagnosticsActivity.class))),
                new ModuleItem(R.drawable.ic_module_device_info, "手機資訊", "硬體與系統資料",
                        () -> startActivity(new Intent(this, DeviceInfoActivity.class))),
                new ModuleItem(R.drawable.ic_module_camera_specs, "相機規格", "鏡頭與錄影能力",
                        () -> startActivity(new Intent(this, CameraSpecsActivity.class)))
        });

        addSection(content, "工具與資料", new ModuleItem[] {
                new ModuleItem(R.drawable.ic_module_resources, "常用資源", "素材與常用程式", this::openLinks),
                new ModuleItem(R.drawable.ic_module_screenshot, "快速截圖", "浮動按鈕與手勢",
                        () -> startActivity(new Intent(this, ScreenshotSettingsActivity.class))),
                new ModuleItem(R.drawable.ic_module_backup, "影音備份", "LocalSend／USB",
                        () -> startActivity(new Intent(this, tw.chehu.quicksend.MainActivity.class))),
                new ModuleItem(R.drawable.ic_module_battery, "充電監控", "記錄數據與 CSV",
                        () -> startActivity(new Intent(this, ChargingMonitorActivity.class)))
        });

        addUpdateEntry(content);
        setContentView(scroll);
    }

    private void addHeader(LinearLayout parent) {
        TextView eyebrow = Ui.text(this,
                "TESTTOOLS  /  QUICK ACCESS", 11, COLOR_ACCENT, true);
        eyebrow.setLetterSpacing(0.08f);
        parent.addView(eyebrow);

        TextView title = Ui.text(this, "測試工具箱", 25, COLOR_INK, true);
        title.setPadding(0, Ui.dp(this, 3), 0, 0);
        parent.addView(title);

        TextView subtitle = Ui.text(this, "測試、檢測與維護，一頁快速進入", 13, COLOR_MUTED, false);
        subtitle.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 12));
        parent.addView(subtitle);
    }

    private void addSection(LinearLayout parent, String label, ModuleItem[] items) {
        TextView heading = Ui.text(this, label, 13, COLOR_INK, true);
        heading.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, Ui.dp(this, 6));
        parent.addView(heading);

        GridLayout grid = new GridLayout(this);
        int columns = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 4 : 2;
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        for (ModuleItem item : items) addCompactCard(grid, item);
    }

    private void addCompactCard(GridLayout grid, ModuleItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.START);
        card.setMinimumHeight(Ui.dp(this, 112));
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13), Ui.dp(this, 11));
        card.setBackground(cardRipple(Color.WHITE));
        card.setElevation(Ui.dp(this, 1));

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = -2;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        params.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        grid.addView(card, params);

        ImageView icon = new ImageView(this);
        icon.setImageResource(item.icon);
        icon.setColorFilter(COLOR_ACCENT);
        icon.setContentDescription(null);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        icon.setBackground(Ui.background(COLOR_ACCENT_SOFT, 10, this));
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        TextView title = Ui.text(this, item.title, 15, COLOR_INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, Ui.dp(this, 9), 0, 0);
        card.addView(title);

        TextView detail = Ui.text(this, item.detail, 11.5f, COLOR_MUTED, false);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setPadding(0, Ui.dp(this, 2), 0, 0);
        card.addView(detail);

        card.setContentDescription(item.title + "，" + item.detail);
        card.setOnClickListener(v -> item.action.run());
        card.setFocusable(true);
        card.setClickable(true);
    }

    private void addUpdateEntry(LinearLayout parent) {
        TextView heading = Ui.text(this, "系統", 13, COLOR_INK, true);
        heading.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), 0, Ui.dp(this, 6));
        parent.addView(heading);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.setBackground(cardRipple(COLOR_ACCENT_SOFT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        parent.addView(row, rowParams);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_module_update);
        icon.setColorFilter(COLOR_ACCENT);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = Ui.dp(this, 10);
        row.addView(copy, copyParams);
        copy.addView(Ui.text(this, "線上更新", 15, COLOR_INK, true));
        copy.addView(Ui.text(this, "檢查並安裝最新版本", 11.5f, COLOR_MUTED, false));

        TextView arrow = Ui.text(this, "›", 25, COLOR_ACCENT, false);
        row.addView(arrow);
        row.setContentDescription("線上更新，檢查並安裝最新版本");
        row.setOnClickListener(v -> startActivity(new Intent(this, OnlineUpdateActivity.class)));
        row.setFocusable(true);
        row.setClickable(true);
    }

    private RippleDrawable cardRipple(int color) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(Ui.dp(this, 14));
        content.setStroke(Ui.dp(this, 1), COLOR_BORDER);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(Ui.dp(this, 14));
        return new RippleDrawable(ColorStateList.valueOf(0x182563EB), content, mask);
    }

    private void openLinks() {
        startActivity(new Intent(this, LinkListActivity.class));
    }

    private static final class ModuleItem {
        final int icon;
        final String title;
        final String detail;
        final Runnable action;

        ModuleItem(int icon, String title, String detail, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.detail = detail;
            this.action = action;
        }
    }
}
