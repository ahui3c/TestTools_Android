package tw.chehu.testtools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

public class ChargingMonitorBootReceiver extends BroadcastReceiver {
    private static final String KEY_LAST_BOOT_MARKER = "last_boot_marker";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        ChargingStorage.migrateIfUnlocked(context);
        SharedPreferences preferences = ChargingStorage.preferences(context);
        ChargingMonitorWidget.updateAll(context);
        if (!preferences.getBoolean(ChargingMonitorService.KEY_ACTIVE, false)) return;

        boolean packageUpdated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        long bootMarker = currentBootMarker(context);
        boolean firstResumeForBoot = !packageUpdated
                && preferences.getLong(KEY_LAST_BOOT_MARKER, Long.MIN_VALUE) != bootMarker;
        preferences.edit().putString(
                ChargingMonitorService.KEY_LATEST_SUMMARY,
                packageUpdated
                        ? "TestTools 已更新，正在自動恢復監控…"
                        : "手機已重新啟動，正在自動恢復監控…")
                .apply();
        Intent service = new Intent(context, ChargingMonitorService.class);
        if (firstResumeForBoot) service.setAction(ChargingMonitorService.ACTION_BOOT_RESUME);
        try {
            context.startForegroundService(service);
            if (firstResumeForBoot) {
                preferences.edit().putLong(KEY_LAST_BOOT_MARKER, bootMarker).apply();
            }
        } catch (RuntimeException error) {
            preferences.edit().putString(
                    ChargingMonitorService.KEY_LATEST_SUMMARY,
                    "系統未允許自動恢復；請使用 Widget 或開啟 TestTools 繼續監控")
                    .apply();
            ChargingMonitorWidget.updateAll(context);
        }
    }

    private long currentBootMarker(Context context) {
        int bootCount = Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bootCount >= 0) return bootCount;
        long bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return -(bootEpoch / 60_000L);
    }
}
