package tw.chehu.testtools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnlineUpdateActivity extends Activity {
    private static final String API_URL = "https://api.github.com/repos/ahui3c/TestTools_Android/releases/latest";
    private static final String PREFS = "online_update";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_PENDING_URI = "pending_uri";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView currentVersionView;
    private TextView latestVersionView;
    private TextView statusView;
    private TextView releaseNotesView;
    private Button updateButton;
    private Button releaseButton;
    private ReleaseInfo releaseInfo;
    private String currentVersion;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            long trackedId = preferences().getLong(KEY_DOWNLOAD_ID, -1L);
            if (completedId == trackedId) checkDownloadedApk(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().setNavigationBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        currentVersion = readCurrentVersion();
        setContentView(buildContent());
        registerDownloadReceiver();
        checkLatestRelease();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumePendingInstall();
        checkDownloadedApk(false);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.color("#F8FAFC"));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);

        TextView back = Ui.text(this, "‹  返回", 17, Ui.color("#2563EB"), true);
        back.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        content.addView(Ui.text(this, "線上更新", 28, Ui.color("#0F172A"), true));
        TextView subtitle = Ui.text(this, "從 TestTools GitHub Release 檢查並下載新版 APK", 14, Ui.color("#64748B"), false);
        subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 20));
        content.addView(subtitle);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.background(Color.WHITE, 18, this));
        card.setElevation(Ui.dp(this, 2));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));

        currentVersionView = addValue(card, "目前版本", currentVersion);
        latestVersionView = addValue(card, "線上最新版本", "檢查中…");
        statusView = Ui.text(this, "正在連線至 GitHub…", 15, Ui.color("#475569"), true);
        statusView.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 12));
        card.addView(statusView);

        updateButton = actionButton("檢查中…");
        updateButton.setEnabled(false);
        updateButton.setOnClickListener(v -> startDownload());
        card.addView(updateButton, buttonParams());

        Button refreshButton = secondaryButton("重新檢查");
        refreshButton.setOnClickListener(v -> checkLatestRelease());
        LinearLayout.LayoutParams refreshParams = buttonParams();
        refreshParams.topMargin = Ui.dp(this, 10);
        card.addView(refreshButton, refreshParams);

        releaseButton = secondaryButton("開啟 GitHub Release 頁面");
        releaseButton.setEnabled(false);
        releaseButton.setOnClickListener(v -> openReleasePage());
        LinearLayout.LayoutParams releaseParams = buttonParams();
        releaseParams.topMargin = Ui.dp(this, 10);
        card.addView(releaseButton, releaseParams);

        TextView notesTitle = Ui.text(this, "版本說明", 18, Ui.color("#0F172A"), true);
        notesTitle.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        content.addView(notesTitle);
        releaseNotesView = Ui.text(this, "載入中…", 14, Ui.color("#475569"), false);
        releaseNotesView.setBackground(Ui.background(Color.WHITE, 14, this));
        releaseNotesView.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        content.addView(releaseNotesView, new LinearLayout.LayoutParams(-1, -2));

        TextView notice = Ui.text(this,
                "為保護裝置安全，Android 會要求確認安裝，且新版 APK 必須與現有 App 使用相同簽章。",
                13, Ui.color("#64748B"), false);
        notice.setPadding(0, Ui.dp(this, 16), 0, 0);
        content.addView(notice);
        return scroll;
    }

    private TextView addValue(LinearLayout parent, String label, String value) {
        TextView view = Ui.text(this, label + "：" + value, 16, Ui.color("#0F172A"), false);
        view.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        parent.addView(view);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.background(Ui.color("#2563EB"), 12, this));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Ui.color("#1D4ED8"));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.background(Ui.color("#E8F0FE"), 12, this));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
    }

    private void checkLatestRelease() {
        statusView.setText("正在連線至 GitHub…");
        statusView.setTextColor(Ui.color("#475569"));
        latestVersionView.setText("線上最新版本：檢查中…");
        releaseNotesView.setText("載入中…");
        updateButton.setText("檢查中…");
        updateButton.setEnabled(false);
        releaseButton.setEnabled(false);
        releaseInfo = null;

        executor.execute(() -> {
            try {
                ReleaseInfo loaded = fetchLatestRelease();
                runOnUiThread(() -> showRelease(loaded));
            } catch (Exception error) {
                runOnUiThread(() -> showCheckError(error));
            }
        });
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "TestTools-Android-Updater");
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String json = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("GitHub 回應錯誤（HTTP " + code + "）");
            }
            JSONObject root = new JSONObject(json);
            String tag = root.optString("tag_name", "");
            String name = root.optString("name", tag);
            String pageUrl = root.optString("html_url", "");
            String notes = root.optString("body", "").trim();
            String publishedAt = root.optString("published_at", "");
            String apkName = "";
            String apkUrl = "";
            long apkSize = 0L;
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null) continue;
                    String candidateName = asset.optString("name", "");
                    if (candidateName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        apkName = candidateName;
                        apkUrl = asset.optString("browser_download_url", "");
                        apkSize = asset.optLong("size", 0L);
                        break;
                    }
                }
            }
            if (tag.isEmpty() || pageUrl.isEmpty()) throw new IllegalStateException("Release 資料不完整");
            return new ReleaseInfo(tag, name, pageUrl, notes, publishedAt, apkName, apkUrl, apkSize);
        } finally {
            connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void showRelease(ReleaseInfo loaded) {
        releaseInfo = loaded;
        String latest = normalizeVersion(loaded.tag);
        latestVersionView.setText("線上最新版本：" + loaded.tag);
        String details = loaded.notes.isEmpty() ? "此版本未提供說明。" : loaded.notes;
        if (!loaded.publishedAt.isEmpty()) details += "\n\n發布時間：" + loaded.publishedAt;
        if (!loaded.apkName.isEmpty()) {
            details += "\nAPK：" + loaded.apkName + formatSize(loaded.apkSize);
        }
        releaseNotesView.setText(details);
        releaseButton.setEnabled(true);

        int comparison = compareVersions(latest, normalizeVersion(currentVersion));
        if (comparison > 0) {
            statusView.setText(loaded.apkUrl.isEmpty() ? "發現新版，但 Release 沒有 APK 資產" : "發現可用的新版本");
            statusView.setTextColor(Ui.color("#15803D"));
            updateButton.setText(loaded.apkUrl.isEmpty() ? "開啟 Release 頁面" : "下載並安裝 " + loaded.tag);
            updateButton.setEnabled(true);
        } else if (comparison == 0) {
            statusView.setText("目前已是最新版本");
            statusView.setTextColor(Ui.color("#15803D"));
            updateButton.setText("目前已是最新版本");
            updateButton.setEnabled(false);
        } else {
            statusView.setText("目前安裝版本比線上 Release 更新");
            statusView.setTextColor(Ui.color("#0369A1"));
            updateButton.setText("不需要更新");
            updateButton.setEnabled(false);
        }
        // Re-entering this page while DownloadManager is still working must not
        // re-enable the button and accidentally enqueue the same APK twice.
        checkDownloadedApk(false);
    }

    private void showCheckError(Exception error) {
        statusView.setText("檢查失敗，請確認網路後重試");
        statusView.setTextColor(Ui.color("#B91C1C"));
        latestVersionView.setText("線上最新版本：無法取得");
        releaseNotesView.setText(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        updateButton.setText("暫時無法更新");
        updateButton.setEnabled(false);
    }

    private void startDownload() {
        if (releaseInfo == null) return;
        if (releaseInfo.apkUrl.isEmpty()) {
            openReleasePage();
            return;
        }
        Uri uri = Uri.parse(releaseInfo.apkUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(this, "為安全起見，只允許 HTTPS 下載", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("下載 TestTools " + releaseInfo.tag)
                    .setDescription(releaseInfo.apkName)
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false);
            long id = manager.enqueue(request);
            preferences().edit().putLong(KEY_DOWNLOAD_ID, id).remove(KEY_PENDING_URI).apply();
            statusView.setText("正在下載，完成後將開啟系統安裝畫面");
            statusView.setTextColor(Ui.color("#0369A1"));
            updateButton.setText("下載中…");
            updateButton.setEnabled(false);
            Toast.makeText(this, "已開始下載 APK", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "無法開始下載：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void checkDownloadedApk(boolean notifyFailure) {
        long id = preferences().getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0) return;
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Uri apkUri = manager.getUriForDownloadedFile(id);
                preferences().edit().remove(KEY_DOWNLOAD_ID).apply();
                if (apkUri != null) requestInstall(apkUri);
                else if (notifyFailure) Toast.makeText(this, "找不到已下載的 APK", Toast.LENGTH_LONG).show();
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                preferences().edit().remove(KEY_DOWNLOAD_ID).apply();
                if (notifyFailure) Toast.makeText(this, "APK 下載失敗（代碼 " + reason + "）", Toast.LENGTH_LONG).show();
                statusView.setText("下載失敗，請重新檢查後再試");
                if (releaseInfo != null && !releaseInfo.apkUrl.isEmpty()) {
                    updateButton.setText("重新下載");
                    updateButton.setEnabled(true);
                }
            } else if (status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED) {
                statusView.setText(status == DownloadManager.STATUS_PAUSED
                        ? "APK 下載暫停，等待網路恢復…" : "APK 正在下載中…");
                statusView.setTextColor(Ui.color("#0369A1"));
                updateButton.setText("下載中…");
                updateButton.setEnabled(false);
            }
        } catch (Exception error) {
            if (notifyFailure) Toast.makeText(this, "無法讀取下載狀態：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void requestInstall(Uri apkUri) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            preferences().edit().putString(KEY_PENDING_URI, apkUri.toString()).apply();
            Toast.makeText(this, "請允許測試工具箱安裝未知應用程式", Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }
        launchInstaller(apkUri);
    }

    private void resumePendingInstall() {
        String pending = preferences().getString(KEY_PENDING_URI, "");
        if (pending.isEmpty()) return;
        if (getPackageManager().canRequestPackageInstalls()) {
            preferences().edit().remove(KEY_PENDING_URI).apply();
            launchInstaller(Uri.parse(pending));
        }
    }

    private void launchInstaller(Uri apkUri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟安裝畫面：" + safeMessage(error), Toast.LENGTH_LONG).show();
            openReleasePage();
        }
    }

    private void openReleasePage() {
        if (releaseInfo == null || releaseInfo.pageUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.pageUrl)));
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟 GitHub 頁面", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // DownloadManager is a separate system component, so Android requires an exported
            // context receiver. The callback is still restricted to our persisted download ID.
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String readCurrentVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "未知" : info.versionName;
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private static String normalizeVersion(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        return normalized;
    }

    static int compareVersions(String left, String right) {
        List<Integer> a = numericParts(left);
        List<Integer> b = numericParts(right);
        if (a.isEmpty() || b.isEmpty()) return left.equalsIgnoreCase(right) ? 0 : -1;
        int count = Math.max(a.size(), b.size());
        for (int index = 0; index < count; index++) {
            int av = index < a.size() ? a.get(index) : 0;
            int bv = index < b.size() ? b.get(index) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static List<Integer> numericParts(String value) {
        List<Integer> values = new ArrayList<>();
        if (value == null) return values;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        while (matcher.find()) {
            try {
                values.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                values.add(Integer.MAX_VALUE);
            }
        }
        return values;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        return String.format(Locale.TAIWAN, "（%.1f MB）", bytes / 1024d / 1024d);
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class ReleaseInfo {
        final String tag;
        final String name;
        final String pageUrl;
        final String notes;
        final String publishedAt;
        final String apkName;
        final String apkUrl;
        final long apkSize;

        ReleaseInfo(String tag, String name, String pageUrl, String notes, String publishedAt,
                    String apkName, String apkUrl, long apkSize) {
            this.tag = tag;
            this.name = name;
            this.pageUrl = pageUrl;
            this.notes = notes;
            this.publishedAt = publishedAt;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }
}
