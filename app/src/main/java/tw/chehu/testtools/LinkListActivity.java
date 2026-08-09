package tw.chehu.testtools;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LinkListActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    private static final String CATEGORY_MATERIALS = "常用測試素材";
    private static final String CATEGORY_APPS = "常用程式";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String category;
    private List<LinkItem> currentItems;
    private LinearLayout listContainer;
    private TextView syncStatus;
    private TextView materialsTab;
    private TextView appsTab;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (!CATEGORY_APPS.equals(category)) category = CATEGORY_MATERIALS;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 16, Ui.color("#2563EB"), true);
        back.setPadding(0, 0, 0, Ui.dp(this, 18));
        back.setOnClickListener(v -> finish());
        content.addView(back);
        content.addView(Ui.text(this, "常用資源", 28, Ui.color("#0F172A"), true));
        TextView note = Ui.text(this, "資料來源：雲端資料庫清單｜離線時使用本地快取", 13, Ui.color("#64748B"), false);
        note.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 14));
        content.addView(note);

        addCategoryTabs(content);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        syncStatus = Ui.text(this, localStatusText(), 12, Ui.color("#64748B"), false);
        syncRow.addView(syncStatus, new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = new Button(this);
        refreshButton.setText("重新整理");
        refreshButton.setTextSize(13);
        refreshButton.setAllCaps(false);
        syncRow.addView(refreshButton, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(-1, -2);
        syncParams.bottomMargin = Ui.dp(this, 14);
        content.addView(syncRow, syncParams);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(listContainer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);

        try {
            renderLinks(LinkRepository.readLocal(this));
        } catch (Exception error) {
            showLoadError("尚無可用的本地清單");
        }
        refreshButton.setOnClickListener(v -> syncLinks(true));
        syncLinks(false);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void syncLinks(boolean requestedByUser) {
        refreshButton.setEnabled(false);
        syncStatus.setText("正在同步雲端資料庫清單…");
        executor.execute(() -> {
            try {
                LinkRepository.SyncResult result = LinkRepository.sync(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderLinks(result.items);
                    syncStatus.setText((result.downloaded ? "同步完成：" : "已是最新：") + formatTime(result.timestamp));
                    refreshButton.setEnabled(true);
                    if (requestedByUser) Toast.makeText(this, "連結清單已更新", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    syncStatus.setText("同步失敗，使用本地快取");
                    refreshButton.setEnabled(true);
                    if (requestedByUser) {
                        Toast.makeText(this, "無法連線至雲端資料庫清單", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String localStatusText() {
        long lastSync = LinkRepository.lastSyncTime(this);
        return lastSync == 0L ? "使用內建離線資料" : "本地快取：" + formatTime(lastSync);
    }

    private String formatTime(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(timestamp));
    }

    private void renderLinks(List<LinkItem> all) {
        currentItems = all;
        renderCurrentCategory();
    }

    private void renderCurrentCategory() {
        listContainer.removeAllViews();
        int visible = 0;
        if (currentItems == null) {
            addEmpty(listContainer, "正在讀取清單…");
            return;
        }
        for (LinkItem item : currentItems) {
            if (category.equals(item.category)) {
                addLink(listContainer, item);
                visible++;
            }
        }
        if (visible == 0) {
            addEmpty(listContainer, "此分類目前沒有連結。\n請在雲端資料庫清單中新增資料，分類欄填寫「" + category + "」。");
        }
    }

    private void addCategoryTabs(LinearLayout parent) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        tabs.setBackground(Ui.background(Ui.color("#E2E8F0"), 12, this));

        materialsTab = createTab("測試素材");
        appsTab = createTab("常用程式");
        tabs.addView(materialsTab, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        tabs.addView(appsTab, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 14);
        parent.addView(tabs, params);

        materialsTab.setOnClickListener(v -> selectCategory(CATEGORY_MATERIALS));
        appsTab.setOnClickListener(v -> selectCategory(CATEGORY_APPS));
        updateTabAppearance();
    }

    private TextView createTab(String label) {
        TextView tab = Ui.text(this, label, 14, Ui.color("#64748B"), true);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        return tab;
    }

    private void selectCategory(String selected) {
        if (selected.equals(category)) return;
        category = selected;
        updateTabAppearance();
        renderCurrentCategory();
    }

    private void updateTabAppearance() {
        setTabAppearance(materialsTab, CATEGORY_MATERIALS.equals(category));
        setTabAppearance(appsTab, CATEGORY_APPS.equals(category));
    }

    private void setTabAppearance(TextView tab, boolean selected) {
        tab.setTextColor(Ui.color(selected ? "#FFFFFF" : "#64748B"));
        tab.setBackground(Ui.background(
                Ui.color(selected ? "#2563EB" : "#E2E8F0"), 9, this));
        tab.setElevation(selected ? Ui.dp(this, 1) : 0);
    }

    private void showLoadError(String message) {
        listContainer.removeAllViews();
        addEmpty(listContainer, message);
    }

    private void addLink(LinearLayout parent, LinkItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.background(Color.WHITE, 14, this));
        row.setElevation(Ui.dp(this, 1));
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 17), Ui.dp(this, 18), Ui.dp(this, 17));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        parent.addView(row, params);

        row.addView(Ui.text(this, item.name, 17, Ui.color("#0F172A"), true));
        TextView url = Ui.text(this, item.url, 12, Ui.color("#64748B"), false);
        url.setMaxLines(2);
        url.setPadding(0, Ui.dp(this, 5), 0, 0);
        row.addView(url);
        row.setOnClickListener(v -> open(item.url));
    }

    private void open(String address) {
        try {
            Uri uri = Uri.parse(address);
            if (uri.getScheme() == null) uri = Uri.parse("https://" + address);
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            Toast.makeText(this, "沒有可開啟此連結的應用程式", Toast.LENGTH_LONG).show();
        }
    }

    private void addEmpty(LinearLayout parent, String message) {
        TextView empty = Ui.text(this, message, 15, Ui.color("#64748B"), false);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(Ui.background(Color.WHITE, 14, this));
        empty.setPadding(Ui.dp(this, 24), Ui.dp(this, 36), Ui.dp(this, 24), Ui.dp(this, 36));
        parent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }
}
