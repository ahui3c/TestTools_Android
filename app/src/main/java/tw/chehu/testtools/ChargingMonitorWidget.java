package tw.chehu.testtools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class ChargingMonitorWidget extends AppWidgetProvider {
    private static final String ACTION_WIDGET_START =
            "tw.chehu.testtools.WIDGET_CHARGING_START";
    private static final String ACTION_WIDGET_STOP =
            "tw.chehu.testtools.WIDGET_CHARGING_STOP";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        ChargingStorage.migrateIfUnlocked(context);
        update(context, manager, appWidgetIds);
        SharedPreferences preferences = ChargingStorage.preferences(context);
        if (preferences.getBoolean(ChargingMonitorService.KEY_ACTIVE, false)
                && !ChargingMonitorService.isRunning()) {
            try {
                context.startForegroundService(new Intent(
                        context, ChargingMonitorService.class));
            } catch (RuntimeException ignored) {
                // 開機接收器是主要恢復路徑；Widget 更新僅作第二層備援。
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_WIDGET_START.equals(action)) {
            Intent service = new Intent(context, ChargingMonitorService.class)
                    .setAction(ChargingMonitorService.ACTION_START);
            try { context.startForegroundService(service); } catch (RuntimeException ignored) {}
            updateAll(context);
        } else if (ACTION_WIDGET_STOP.equals(action)) {
            Intent service = new Intent(context, ChargingMonitorService.class)
                    .setAction(ChargingMonitorService.ACTION_STOP);
            try { context.startForegroundService(service); } catch (RuntimeException ignored) {}
            updateAll(context);
        }
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, ChargingMonitorWidget.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) return;
        update(context, manager, ids);
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences preferences = ChargingStorage.preferences(context);
        boolean active = preferences.getBoolean(ChargingMonitorService.KEY_ACTIVE, false);
        int level = preferences.getInt(ChargingMonitorService.KEY_LATEST_LEVEL, -1);
        String status = preferences.getString(
                ChargingMonitorService.KEY_LATEST_STATUS, "尚未記錄");
        String power = preferences.getString(
                ChargingMonitorService.KEY_LATEST_POWER, "--");
        String temperature = preferences.getString(
                ChargingMonitorService.KEY_LATEST_TEMPERATURE, "--");
        String time = preferences.getString(
                ChargingMonitorService.KEY_LATEST_TIME, "--:--:--");

        for (int id : ids) {
            RemoteViews views = new RemoteViews(
                    context.getPackageName(), R.layout.widget_charging_monitor);
            views.setTextViewText(R.id.widget_title,
                    (level < 0 ? "--" : level + "%") + "  " + status);
            views.setTextViewText(R.id.widget_details,
                    power + "  •  " + temperature + "  •  " + time);
            views.setTextViewText(R.id.widget_state, active ? "監控中" : "已停止");

            Intent openIntent = new Intent(context, ChargingMonitorActivity.class);
            PendingIntent open = PendingIntent.getActivity(context, 30, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_info, open);

            Intent startIntent = new Intent(context, ChargingMonitorWidget.class)
                    .setAction(ACTION_WIDGET_START);
            PendingIntent start = PendingIntent.getBroadcast(context, 31, startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_start, start);

            Intent stopIntent = new Intent(context, ChargingMonitorWidget.class)
                    .setAction(ACTION_WIDGET_STOP);
            PendingIntent stop = PendingIntent.getBroadcast(context, 32, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_stop, stop);
            manager.updateAppWidget(id, views);
        }
    }
}
