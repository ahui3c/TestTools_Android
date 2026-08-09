package tw.chehu.testtools;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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
    private ScreenshotStorage() {}

    static Uri savePng(Context context, Bitmap bitmap) throws Exception {
        String fileName = "TestTools_" + new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/TestTools Captures");
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

        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "TestTools Captures");
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
}
