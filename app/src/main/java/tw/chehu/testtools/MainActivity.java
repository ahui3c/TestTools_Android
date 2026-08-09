package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);

        TextView title = Ui.text(this, "測試工具箱", 30, Ui.color("#0F172A"), true);
        content.addView(title);
        TextView subtitle = Ui.text(this, "集中管理測試資源與顯示器測試功能", 15, Ui.color("#64748B"), false);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 24));
        content.addView(subtitle);

        addCard(content, "01", "常用測試素材", "從雲端資料庫清單開啟圖片、影片、音訊與其他測試檔案", () -> openLinks("常用測試素材"));
        addCard(content, "02", "常用程式", "從雲端資料庫清單前往常用工具、網站或下載頁面", () -> openLinks("常用程式"));
        addCard(content, "03", "測試亮度", "全螢幕顯示自訂比例的白色方形或圓形測試區域", () -> startActivity(new Intent(this, BrightnessSetupActivity.class)));
        addCard(content, "04", "浮動快速截圖", "在其他 App 上方顯示時間與電量，點一下立即保存螢幕截圖", () -> startActivity(new Intent(this, ScreenshotSettingsActivity.class)));
        addCard(content, "05", "影音快速備份", "將 DCIM／Pictures 備份至 LocalSend 接收端或 USB 外接磁碟", () -> startActivity(new Intent(this, tw.chehu.quicksend.MainActivity.class)));

        setContentView(scroll);
    }

    private void openLinks(String category) {
        Intent intent = new Intent(this, LinkListActivity.class);
        intent.putExtra(LinkListActivity.EXTRA_CATEGORY, category);
        startActivity(intent);
    }

    private void addCard(LinearLayout parent, String number, String title, String detail, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.background(Color.WHITE, 18, this));
        card.setElevation(Ui.dp(this, 2));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = Ui.dp(this, 14);
        parent.addView(card, cardParams);

        TextView badge = Ui.text(this, number, 13, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
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
