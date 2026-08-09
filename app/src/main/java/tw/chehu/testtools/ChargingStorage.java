package tw.chehu.testtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

final class ChargingStorage {
    private static final String MIGRATION_PREFS = "charging_storage_migration";
    private static final String KEY_MIGRATED = "credential_to_device_v1";

    private ChargingStorage() {}

    static Context deviceContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }

    static SharedPreferences preferences(Context context) {
        return deviceContext(context).getSharedPreferences(
                ChargingMonitorService.PREFS, Context.MODE_PRIVATE);
    }

    static File logsDirectory(Context context) {
        return new File(deviceContext(context).getFilesDir(), "charging_logs");
    }

    static void migrateIfUnlocked(Context context) {
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users == null || !users.isUserUnlocked()) return;
        Context device = deviceContext(context);
        SharedPreferences migration = device.getSharedPreferences(
                MIGRATION_PREFS, Context.MODE_PRIVATE);
        if (migration.getBoolean(KEY_MIGRATED, false)) return;

        // 一般元件 Context 預設就是 Credential Protected；只有目的地需明確建立
        // Device Protected Context。此方法只會在使用者已解鎖時執行。
        Context credential = context;
        device.moveSharedPreferencesFrom(credential, ChargingMonitorService.PREFS);
        File oldDirectory = new File(credential.getFilesDir(), "charging_logs");
        File newDirectory = new File(device.getFilesDir(), "charging_logs");
        copyMissingFiles(oldDirectory, newDirectory);
        migration.edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    private static void copyMissingFiles(File source, File destination) {
        if (!source.isDirectory()) return;
        if (!destination.exists() && !destination.mkdirs()) return;
        File[] files = source.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[8192];
        for (File sourceFile : files) {
            if (!sourceFile.isFile()) continue;
            File destinationFile = new File(destination, sourceFile.getName());
            if (destinationFile.exists()) continue;
            try (FileInputStream input = new FileInputStream(sourceFile);
                 FileOutputStream output = new FileOutputStream(destinationFile)) {
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            } catch (Exception ignored) {
                // 舊檔遷移失敗不影響新的監控工作階段，原始檔也不會被刪除。
            }
        }
    }
}
