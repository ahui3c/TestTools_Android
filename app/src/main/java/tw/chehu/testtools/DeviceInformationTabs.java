package tw.chehu.testtools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DeviceInformationTabs {
    enum Page { DEVICE, CAMERA }

    private DeviceInformationTabs() {}

    static View create(Activity activity, Page activePage) {
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(Ui.dp(activity, 4), Ui.dp(activity, 4),
                Ui.dp(activity, 4), Ui.dp(activity, 4));
        tabs.setBackground(Ui.background(Ui.color("#E8F0FE"), 14, activity));

        tabs.addView(tab(activity, "手機資訊", Page.DEVICE, activePage),
                new LinearLayout.LayoutParams(0, Ui.dp(activity, 46), 1));
        tabs.addView(tab(activity, "相機規格", Page.CAMERA, activePage),
                new LinearLayout.LayoutParams(0, Ui.dp(activity, 46), 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(activity, 12);
        params.bottomMargin = Ui.dp(activity, 12);
        tabs.setLayoutParams(params);
        return tabs;
    }

    private static TextView tab(Activity activity, String label, Page page, Page activePage) {
        boolean selected = page == activePage;
        TextView tab = Ui.text(activity, label, 15,
                selected ? Color.WHITE : Ui.color("#334155"), true);
        tab.setGravity(Gravity.CENTER);
        tab.setSelected(selected);
        tab.setBackground(Ui.background(
                selected ? Ui.color("#2563EB") : Color.TRANSPARENT, 11, activity));
        tab.setContentDescription(label + (selected ? "，目前頁面" : ""));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setOnClickListener(view -> {
            if (selected) return;
            Class<? extends Activity> target = page == Page.DEVICE
                    ? DeviceInfoActivity.class : CameraSpecsActivity.class;
            activity.startActivity(new Intent(activity, target));
            activity.overridePendingTransition(0, 0);
            activity.finish();
        });
        return tab;
    }
}
