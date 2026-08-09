package tw.chehu.testtools;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ScreenshotStorage {
    private static final String PICTURES_SCREENSHOTS =
            Environment.DIRECTORY_PICTURES + "/Screenshots";
    private static final String DCIM_SCREENSHOTS =
            Environment.DIRECTORY_DCIM + "/Screenshots";

    private ScreenshotStorage() {}

    static Uri savePng(Context context, Bitmap bitmap) throws Exception {
        String fileName = "TestTools_" + new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
        String screenshotPath = resolveScreenshotPath(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, screenshotPath);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("無法建立截圖檔案");
            try {
                try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException("無法寫入截圖");
                    }
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
                return uri;
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            }
        }

        File directory = publicDirectory(screenshotPath);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("無法建立截圖資料夾");
        }
        File file = new File(directory, fileName);
        try (OutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("無法寫入截圖");
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        return Uri.fromFile(file);
    }

    private static String resolveScreenshotPath(Context context) {
        String mediaPath = findExistingMediaPath(context);
        if (mediaPath != null) return mediaPath;

        File dcim = publicDirectory(DCIM_SCREENSHOTS);
        File pictures = publicDirectory(PICTURES_SCREENSHOTS);
        boolean hasDcim = dcim.isDirectory();
        boolean hasPictures = pictures.isDirectory();
        if (hasDcim && !hasPictures) return DCIM_SCREENSHOTS;
        if (hasDcim && hasPictures && dcim.lastModified() > pictures.lastModified()) {
            return DCIM_SCREENSHOTS;
        }
        return PICTURES_SCREENSHOTS;
    }

    private static String findExistingMediaPath(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String column = MediaStore.Images.Media.RELATIVE_PATH;
        String selection = column + " IN (?, ?, ?, ?)";
        String[] arguments = {
                PICTURES_SCREENSHOTS, PICTURES_SCREENSHOTS + "/",
                DCIM_SCREENSHOTS, DCIM_SCREENSHOTS + "/"
        };
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{column, MediaStore.Images.Media.DATE_ADDED},
                selection, arguments,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String path = cursor.getString(cursor.getColumnIndexOrThrow(column));
            if (path == null) return null;
            String normalized = path.replace('\\', '/');
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.equalsIgnoreCase(DCIM_SCREENSHOTS)) return DCIM_SCREENSHOTS;
            if (normalized.equalsIgnoreCase(PICTURES_SCREENSHOTS)) return PICTURES_SCREENSHOTS;
        } catch (RuntimeException ignored) {
            // 部分 Android 版本會限制未授權 App 查詢其他程式建立的媒體。
        }
        return null;
    }

    private static File publicDirectory(String relativePath) {
        int slash = relativePath.indexOf('/');
        String topLevel = slash < 0 ? relativePath : relativePath.substring(0, slash);
        String child = slash < 0 ? "" : relativePath.substring(slash + 1);
        return new File(Environment.getExternalStoragePublicDirectory(topLevel), child);
    }
}
