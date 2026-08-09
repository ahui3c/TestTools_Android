package tw.chehu.testtools;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static GradientDrawable background(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, (int) radiusDp));
        return drawable;
    }

    static void setPadding(View view, int horizontalDp, int verticalDp) {
        int h = dp(view.getContext(), horizontalDp);
        int v = dp(view.getContext(), verticalDp);
        view.setPadding(h, v, h, v);
    }

    static int color(String value) {
        return Color.parseColor(value);
    }
}
