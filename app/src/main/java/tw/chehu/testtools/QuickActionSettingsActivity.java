package tw.chehu.testtools;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class QuickActionSettingsActivity extends Activity {
    private static final int REQUEST_CAMERA = 6301;
    private SharedPreferences preferences;
    private TextView permissionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color("#F8FAFC"));
        getWindow().setNavigationBarColor(Ui.color("#F8FAFC"));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        preferences = getSharedPreferences(FloatingCaptureOverlay.PREFS, MODE_PRIVATE);

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
        content.addView(Ui.text(this, "系統快捷功能設定", 28, Ui.color("#0F172A"), true));
        TextView intro = Ui.text(this,
                "這裡設定浮動按鈕要開啟的 App，以及手電筒和系統靜音需要的權限。設定完成後，回到上一頁將功能指派給手勢。",
                14, Ui.color("#64748B"), false);
        intro.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 18));
        content.addView(intro);

        addAppPanel(content);
        addPermissionPanel(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void addAppPanel(LinearLayout content) {
        LinearLayout panel = panel();
        panel.addView(Ui.text(this, "指定應用程式", 17, Ui.color("#0F172A"), true));
        TextView hint = Ui.text(this,
                "選擇要由浮動按鈕快速開啟的應用程式。",
                13, Ui.color("#64748B"), false);
        hint.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 9));
        panel.addView(hint);

        List<AppChoice> apps = loadLaunchableApps();
        Spinner appSpinner = new Spinner(this);
        ArrayAdapter<AppChoice> adapter = appChoiceAdapter(apps);
        appSpinner.setAdapter(adapter);
        String savedPackage = preferences.getString(SystemQuickActions.KEY_SELECTED_APP_PACKAGE, "");
        appSpinner.setSelection(findPackage(apps, savedPackage));
        appSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putString(SystemQuickActions.KEY_SELECTED_APP_PACKAGE,
                        apps.get(position).packageName).apply();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(appSpinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        Button testApp = actionButton("測試開啟指定應用程式", "#E8F0FE", Ui.color("#1D4ED8"));
        testApp.setOnClickListener(v -> showResult(SystemQuickActions.openSelectedApp(this)));
        panel.addView(testApp, buttonParams());
        addPanel(content, panel);
    }

    private void addPermissionPanel(LinearLayout content) {
        LinearLayout panel = panel();
        panel.addView(Ui.text(this, "系統權限", 17, Ui.color("#0F172A"), true));
        permissionStatus = Ui.text(this, "", 13, Ui.color("#475569"), false);
        permissionStatus.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        panel.addView(permissionStatus);

        Button camera = actionButton("允許控制補光燈／手電筒", "#E8F0FE", Ui.color("#1D4ED8"));
        camera.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "相機權限已允許", Toast.LENGTH_SHORT).show();
                SystemQuickActions.initialize(this);
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
        });
        panel.addView(camera, buttonParams());

        Button policy = actionButton("允許切換系統靜音／勿擾模式", "#E8F0FE", Ui.color("#1D4ED8"));
        policy.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        LinearLayout.LayoutParams policyParams = buttonParams();
        policyParams.topMargin = Ui.dp(this, 8);
        panel.addView(policy, policyParams);
        addPanel(content, panel);
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        if (permissionStatus == null) return;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean policy = manager != null && manager.isNotificationPolicyAccessGranted();
        permissionStatus.setText(String.format(Locale.TAIWAN,
                "手電筒相機權限：%s\n系統靜音／勿擾模式：%s",
                camera ? "已允許" : "尚未允許",
                policy ? "已允許" : "尚未允許"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) SystemQuickActions.initialize(this);
        Toast.makeText(this, granted ? "已允許控制手電筒" : "未允許相機權限",
                Toast.LENGTH_SHORT).show();
        updatePermissionStatus();
    }

    private List<AppChoice> loadLaunchableApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(query, 0);
        Map<String, AppChoice> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String packageName = info.activityInfo.packageName;
            CharSequence labelValue = info.loadLabel(getPackageManager());
            String label = labelValue == null ? "未命名應用程式" : labelValue.toString().trim();
            if (label.isEmpty()) label = "未命名應用程式";
            Drawable icon = info.loadIcon(getPackageManager());
            unique.putIfAbsent(packageName, new AppChoice(packageName, label, icon));
        }
        String saved = preferences.getString(SystemQuickActions.KEY_SELECTED_APP_PACKAGE, "");
        if (saved != null && !saved.isEmpty() && !unique.containsKey(saved)) {
            unique.put(saved, unavailableChoice(saved));
        }
        List<AppChoice> choices = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        choices.sort((left, right) -> collator.compare(left.label, right.label));
        choices.add(0, new AppChoice("", "未指定", null));
        return choices;
    }

    private AppChoice unavailableChoice(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence value = info.loadLabel(getPackageManager());
            String label = value == null ? "先前選擇的應用程式" : value.toString().trim();
            if (label.isEmpty()) label = "先前選擇的應用程式";
            return new AppChoice(packageName, label + "（目前無法啟動）",
                    info.loadIcon(getPackageManager()));
        } catch (PackageManager.NameNotFoundException ignored) {
            return new AppChoice(packageName, "先前選擇的應用程式（目前無法啟動）",
                    getPackageManager().getDefaultActivityIcon());
        }
    }

    private ArrayAdapter<AppChoice> appChoiceAdapter(List<AppChoice> apps) {
        return new ArrayAdapter<AppChoice>(this, android.R.layout.simple_spinner_item, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return appChoiceView(apps.get(position), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return appChoiceView(apps.get(position), true);
            }
        };
    }

    private View appChoiceView(AppChoice choice, boolean dropdown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, dropdown ? 56 : 52));
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 4),
                Ui.dp(this, dropdown ? 12 : 34), Ui.dp(this, 4));
        row.setBackground(Ui.background(
                Ui.color(dropdown ? "#FFFFFF" : "#F1F5F9"), 10, this));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (choice.icon == null) icon.setVisibility(View.INVISIBLE);
        else icon.setImageDrawable(choice.icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36));
        iconParams.rightMargin = Ui.dp(this, 10);
        row.addView(icon, iconParams);

        TextView name = Ui.text(this, choice.label, 15, Ui.color("#0F172A"), false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(0, -1, 1f));
        return row;
    }

    private int findPackage(List<AppChoice> apps, String packageName) {
        if (packageName == null) return 0;
        for (int index = 0; index < apps.size(); index++) {
            if (packageName.equals(apps.get(index).packageName)) return index;
        }
        return 0;
    }

    private void showResult(SystemQuickActions.Result result) {
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.background(Color.WHITE, 14, this));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return panel;
    }

    private void addPanel(LinearLayout content, LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        content.addView(panel, params);
    }

    private Button actionButton(String label, String background, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setBackground(Ui.background(Ui.color(background), 12, this));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
    }

    private static final class AppChoice {
        final String packageName;
        final String label;
        final Drawable icon;

        AppChoice(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }

        @Override public String toString() {
            return label;
        }
    }

}
