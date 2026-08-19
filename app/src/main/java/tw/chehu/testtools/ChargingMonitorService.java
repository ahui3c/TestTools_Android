package tw.chehu.testtools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChargingMonitorService extends Service {
    static final String PREFS = "charging_monitor";
    static final String KEY_ACTIVE = "active";
    static final String KEY_SESSION_FILE = "session_file";
    static final String KEY_SESSION_STARTED = "session_started";
    static final String KEY_SAMPLE_COUNT = "sample_count";
    static final String KEY_LATEST_SUMMARY = "latest_summary";
    static final String KEY_LATEST_LEVEL = "latest_level";
    static final String KEY_LATEST_STATUS = "latest_status";
    static final String KEY_LATEST_POWER = "latest_power";
    static final String KEY_LATEST_TEMPERATURE = "latest_temperature";
    static final String KEY_LATEST_TIME = "latest_time";
    static final String ACTION_START = "tw.chehu.testtools.CHARGING_MONITOR_START";
    static final String ACTION_STOP = "tw.chehu.testtools.CHARGING_MONITOR_STOP";
    static final String ACTION_BOOT_RESUME = "tw.chehu.testtools.CHARGING_MONITOR_BOOT_RESUME";
    static final Object FILE_LOCK = new Object();

    private static final String CHANNEL_ID = "charging_monitor";
    private static final int NOTIFICATION_ID = 9206;
    private static final long SAMPLE_INTERVAL_MS = 60_000L;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private BatteryManager batteryManager;
    private boolean scheduled;
    private boolean powerReceiverRegistered;

    private final BroadcastReceiver powerEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !preferences.getBoolean(KEY_ACTIVE, false)) return;
            ensureCurrentSchema();
            if (!preferences.getBoolean(KEY_ACTIVE, false)) return;
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                record("POWER_CONNECTED");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                record("POWER_DISCONNECTED");
            } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                record("BATTERY_LOW");
            }
        }
    };

    static boolean isRunning() {
        return running;
    }

    static File logsDirectory(Context context) {
        return ChargingStorage.logsDirectory(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ChargingStorage.migrateIfUnlocked(this);
        preferences = ChargingStorage.preferences(this);
        batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        registerPowerEvents();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground(preferences.getString(KEY_LATEST_SUMMARY, "準備記錄充電數據…"));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (preferences.getBoolean(KEY_ACTIVE, false)) {
                ensureCurrentSchema();
                if (preferences.getBoolean(KEY_ACTIVE, false)) record("STOP");
            }
            preferences.edit().putBoolean(KEY_ACTIVE, false).apply();
            ChargingMonitorWidget.updateAll(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean startedNew = false;
        if (ACTION_START.equals(action) && !preferences.getBoolean(KEY_ACTIVE, false)) {
            startedNew = createSession();
        }
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureCurrentSchema();
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean bootResume = ACTION_BOOT_RESUME.equals(action);
        if (startedNew) {
            record("START");
        } else if (bootResume) {
            record("BOOT_RESUME");
        } else if (!scheduled) {
            record("SAMPLE");
        }
        if (!scheduled) {
            scheduled = true;
            scheduleNext();
        }
        return START_STICKY;
    }

    private void registerPowerEvents() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerEvents, filter);
        }
        powerReceiverRegistered = true;
    }

    private boolean createSession() {
        File directory = logsDirectory(this);
        if (!directory.exists() && !directory.mkdirs()) return sessionFailure();
        String name = "TestTools_Charging_" + new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".csv";
        File file = new File(directory, name);
        String header = "時間,事件,經過分鐘,電量百分比,電量變化百分點,電池狀態,外部供電,供電類型,"
                + "電池健康度,溫度_C,電壓_V,即時電流_mA,平均電流_mA,電池端估算功率_W,"
                + "剩餘電荷_mAh,剩餘能量_mWh,循環次數\n";
        try {
            synchronized (FILE_LOCK) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    writer.write('\ufeff');
                    writer.write(header);
                }
            }
            long now = System.currentTimeMillis();
            preferences.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putString(KEY_SESSION_FILE, name)
                    .putLong(KEY_SESSION_STARTED, now)
                    .putInt(KEY_SAMPLE_COUNT, 0)
                    .remove("last_level")
                    .putString(KEY_LATEST_SUMMARY, "監控已開始，等待第一筆資料")
                    .apply();
            return true;
        } catch (Exception error) {
            return sessionFailure();
        }
    }

    private boolean sessionFailure() {
        preferences.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_LATEST_SUMMARY, "無法建立 Device Protected 記錄檔")
                .apply();
        ChargingMonitorWidget.updateAll(this);
        return false;
    }

    private void ensureCurrentSchema() {
        File file = sessionFile();
        if (!file.isFile() || !hasEventColumn(file)) createSession();
    }

    private boolean hasEventColumn(File file) {
        synchronized (FILE_LOCK) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                return header != null && header.contains("事件");
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private void scheduleNext() {
        long now = SystemClock.uptimeMillis();
        long next = ((now / SAMPLE_INTERVAL_MS) + 1) * SAMPLE_INTERVAL_MS;
        handler.postAtTime(() -> {
            if (!preferences.getBoolean(KEY_ACTIVE, false)) return;
            record("SAMPLE");
            scheduleNext();
        }, next);
    }

    private void record(String event) {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            updateFailure("系統沒有提供電池資料");
            return;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int temperatureTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
        Integer currentNowUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        Integer currentAverageUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        Integer chargeCounterUah = intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        Long energyNwh = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        Integer cycleCount = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? nullableExtra(battery, BatteryManager.EXTRA_CYCLE_COUNT) : null;

        int oldLevel = preferences.getInt("last_level", Integer.MIN_VALUE);
        Integer delta = percent < 0 || oldLevel == Integer.MIN_VALUE ? null : percent - oldLevel;
        boolean external = plugged != 0;
        Double powerW = null;
        if (external && currentNowUa != null && voltageMv != Integer.MIN_VALUE) {
            powerW = Math.abs((double) currentNowUa) * voltageMv / 1_000_000_000d;
        }
        long now = System.currentTimeMillis();
        long started = preferences.getLong(KEY_SESSION_STARTED, now);
        long elapsedMinutes = Math.max(0L, now - started) / 60_000L;

        String row = String.join(",",
                csv(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(now))),
                csv(event),
                String.valueOf(elapsedMinutes),
                percent < 0 ? "" : percent + "%",
                delta == null ? "" : String.valueOf(delta),
                csv(statusName(status)),
                external ? "是" : "否",
                csv(plugName(plugged)),
                csv(healthName(health)),
                temperatureTenths == Integer.MIN_VALUE ? "" : number(temperatureTenths / 10d, 1),
                voltageMv == Integer.MIN_VALUE ? "" : number(voltageMv / 1000d, 3),
                currentNowUa == null ? "" : number(currentNowUa / 1000d, 1),
                currentAverageUa == null ? "" : number(currentAverageUa / 1000d, 1),
                powerW == null ? "" : number(powerW, 3),
                chargeCounterUah == null ? "" : number(chargeCounterUah / 1000d, 1),
                energyNwh == null ? "" : number(energyNwh / 1_000_000d, 2),
                cycleCount == null ? "" : String.valueOf(cycleCount)) + "\n";

        try {
            synchronized (FILE_LOCK) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(sessionFile(), true), StandardCharsets.UTF_8))) {
                    writer.write(row);
                }
            }
            int count = preferences.getInt(KEY_SAMPLE_COUNT, 0) + 1;
            String power = powerW == null ? "不支援" : number(powerW, 2) + " W";
            String temperature = temperatureTenths == Integer.MIN_VALUE
                    ? "--" : number(temperatureTenths / 10d, 1) + "°C";
            String statusText = statusName(status) + "／" + plugName(plugged);
            String timeText = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(now));
            String summary = "電量 " + (percent < 0 ? "--" : percent + "%")
                    + "｜" + statusText
                    + "\n電池端估算功率 " + power + "｜溫度 " + temperature
                    + "\n事件 " + event + "｜已記錄 " + count + " 筆";
            SharedPreferences.Editor editor = preferences.edit()
                    .putInt(KEY_SAMPLE_COUNT, count)
                    .putString(KEY_LATEST_SUMMARY, summary)
                    .putInt(KEY_LATEST_LEVEL, percent)
                    .putString(KEY_LATEST_STATUS, statusText)
                    .putString(KEY_LATEST_POWER, power)
                    .putString(KEY_LATEST_TEMPERATURE, temperature)
                    .putString(KEY_LATEST_TIME, timeText);
            if (percent >= 0) editor.putInt("last_level", percent);
            editor.apply();
            updateNotification(summary.replace('\n', ' '));
            ChargingMonitorWidget.updateAll(this);
        } catch (Exception error) {
            updateFailure("寫入記錄檔失敗");
        }
    }

    private void updateFailure(String message) {
        preferences.edit().putString(KEY_LATEST_SUMMARY, message).apply();
        updateNotification(message);
        ChargingMonitorWidget.updateAll(this);
    }

    private File sessionFile() {
        String name = preferences.getString(KEY_SESSION_FILE, "");
        return new File(logsDirectory(this), new File(name).getName());
    }

    private Integer intProperty(int property) {
        if (batteryManager == null) return null;
        int value = batteryManager.getIntProperty(property);
        return value == Integer.MIN_VALUE ? null : value;
    }

    private Long longProperty(int property) {
        if (batteryManager == null) return null;
        long value = batteryManager.getLongProperty(property);
        return value == Long.MIN_VALUE ? null : value;
    }

    private Integer nullableExtra(Intent intent, String key) {
        if (!intent.hasExtra(key)) return null;
        int value = intent.getIntExtra(key, -1);
        return value < 0 ? null : value;
    }

    private void startInForeground(String text) {
        Notification notification = notification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "充電數據監控", NotificationManager.IMPORTANCE_LOW));
        Intent openIntent = new Intent(this, ChargingMonitorActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 20, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ChargingMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 21, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TestTools 正在監控充電")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止監控", stop).build())
                .build();
    }

    private String statusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "充電中";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "放電中";
            case BatteryManager.BATTERY_STATUS_FULL: return "已充滿";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "未充電";
            default: return "未知";
        }
    }

    private String plugName(int plugged) {
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) return "交流充電器";
        if (plugged == BatteryManager.BATTERY_PLUGGED_USB) return "USB";
        if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) return "無線充電";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && plugged == BatteryManager.BATTERY_PLUGGED_DOCK) return "底座";
        return plugged == 0 ? "未連接" : "其他";
    }

    private String healthName(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "良好";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "過熱";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "故障";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "電壓過高";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "未指定故障";
            case BatteryManager.BATTERY_HEALTH_COLD: return "溫度過低";
            default: return "未知";
        }
    }

    private String number(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        scheduled = false;
        handler.removeCallbacksAndMessages(null);
        if (powerReceiverRegistered) {
            try { unregisterReceiver(powerEvents); } catch (IllegalArgumentException ignored) {}
        }
        powerReceiverRegistered = false;
        running = false;
        ChargingMonitorWidget.updateAll(this);
        super.onDestroy();
    }
}
