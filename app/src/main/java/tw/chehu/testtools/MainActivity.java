package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
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
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);

        TextView title = Ui.text(this, "測試工具箱", 30, Ui.color("#0F172A"), true);
        content.addView(title);
        TextView subtitle = Ui.text(this, "集中管理手機測試、硬體檢測與維護功能", 15, Ui.color("#64748B"), false);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 24));
        content.addView(subtitle);

        addCard(content, R.drawable.ic_module_resources, "常用資源", "在測試素材與常用程式頁簽間切換，快速開啟檔案、工具與網站", this::openLinks);
        addCard(content, R.drawable.ic_module_brightness, "測試亮度", "全螢幕顯示自訂比例的白色方形或圓形測試區域", () -> startActivity(new Intent(this, BrightnessSetupActivity.class)));
        addCard(content, R.drawable.ic_module_screen_test, "螢幕綜合測試", "檢查純色、灰階、漸層、色條、像素細節與多點觸控", () -> startActivity(new Intent(this, ScreenDiagnosticsActivity.class)));
        addCard(content, R.drawable.ic_module_screenshot, "浮動快速截圖", "自訂單擊、雙擊與四方向滑動功能，長按後可拖曳移動", () -> startActivity(new Intent(this, ScreenshotSettingsActivity.class)));
        addCard(content, R.drawable.ic_module_backup, "影音快速備份", "將 DCIM／Pictures 備份至 LocalSend 接收端或 USB 外接磁碟", () -> startActivity(new Intent(this, tw.chehu.quicksend.MainActivity.class)));
        addCard(content, R.drawable.ic_module_battery, "充電數據監控", "每分鐘低負載記錄電量、充電狀態與電池端估算功率，可匯出 CSV", () -> startActivity(new Intent(this, ChargingMonitorActivity.class)));
        addCard(content, R.drawable.ic_module_device_info, "手機資訊", "查看並快速複製硬體、系統、螢幕、電池與相機資訊，可匯出 TXT", () -> startActivity(new Intent(this, DeviceInfoActivity.class)));
        addCard(content, R.drawable.ic_module_camera_specs, "相機規格檢測", "逐顆鏡頭查看感光元件、焦段、光圈、防手震、錄影與 RAW 能力", () -> startActivity(new Intent(this, CameraSpecsActivity.class)));
        addCard(content, R.drawable.ic_module_update, "線上更新", "檢查 GitHub Release，快速下載並安裝最新版 APK", () -> startActivity(new Intent(this, OnlineUpdateActivity.class)));

        setContentView(scroll);
    }

    private void openLinks() {
        startActivity(new Intent(this, LinkListActivity.class));
    }

    private void addCard(LinearLayout parent, int iconResource, String title, String detail, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.background(Color.WHITE, 18, this));
        card.setElevation(Ui.dp(this, 2));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = Ui.dp(this, 14);
        parent.addView(card, cardParams);

        ImageView badge = new ImageView(this);
        badge.setImageResource(iconResource);
        badge.setContentDescription(title);
        badge.setScaleType(ImageView.ScaleType.CENTER);
        badge.setPadding(Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11));
        badge.setBackground(Ui.background(Ui.color("#2563EB"), 12, this));
        card.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = Ui.dp(this, 16);
        card.addView(copy, copyParams);
        copy.addView(Ui.text(this, title, 19, Ui.color("#0F172A"), true));
        TextView description = Ui.text(this, detail, 14, Ui.color("#64748B"), false);
        description.setPadding(0, Ui.dp(this, 5), 0, 0);
        copy.addView(description);

        TextView arrow = Ui.text(this, "›", 32, Ui.color("#94A3B8"), false);
        card.addView(arrow);
        card.setOnClickListener(v -> action.run());
        card.setFocusable(true);
        card.setClickable(true);
    }
}
