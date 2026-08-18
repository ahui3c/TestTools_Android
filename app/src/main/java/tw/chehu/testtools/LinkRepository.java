package tw.chehu.testtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class LinkRepository {
    private static final String REMOTE_CSV =
            "https://docs.google.com/spreadsheets/d/1ZyY9DQ7WAyIDduiJHuKgYgCJu9zsXoLz5Jecargktvc/export?format=csv&gid=0";
    private static final String CACHE_FILE = "links-cache.csv";
    private static final String FALLBACK_ASSET = "links-fallback.csv";
    private static final String PREFS = "link_sync";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_SOURCE = "source_url";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;

    private LinkRepository() {}

    static List<LinkItem> readLocal(Context context) throws Exception {
        File cache = new File(context.getFilesDir(), CACHE_FILE);
        if (cache.isFile()) {
            try (InputStream input = new FileInputStream(cache)) {
                return parseCsv(readUtf8(input));
            } catch (Exception ignored) {
                // 快取若損毀，立即退回 APK 內建資料；下次同步會重建快取。
            }
        }
        try (InputStream input = context.getAssets().open(FALLBACK_ASSET)) {
            return parseCsv(readUtf8(input));
        }
    }

    static long lastSyncTime(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L);
    }

    static SyncResult sync(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HttpURLConnection connection = (HttpURLConnection) new URL(REMOTE_CSV).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/csv,text/plain;q=0.9,*/*;q=0.1");
        connection.setRequestProperty("User-Agent", "TestTools-Android/1.3");
        boolean sameSource = REMOTE_CSV.equals(prefs.getString(KEY_SOURCE, ""));
        String etag = sameSource ? prefs.getString(KEY_ETAG, "") : "";
        String lastModified = sameSource ? prefs.getString(KEY_LAST_MODIFIED, "") : "";
        if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
        if (!lastModified.isEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified);

        try {
            int status = connection.getResponseCode();
            long checkedAt = System.currentTimeMillis();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(KEY_LAST_SYNC, checkedAt).apply();
                return new SyncResult(readLocal(context), false, checkedAt);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("雲端資料庫清單回應 HTTP " + status);
            }

            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, MAX_DOWNLOAD_BYTES);
            }
            String csv = new String(bytes, StandardCharsets.UTF_8);
            List<LinkItem> items = parseCsv(csv);
            writeCache(context, bytes);

            SharedPreferences.Editor editor = prefs.edit()
                    .putLong(KEY_LAST_SYNC, checkedAt)
                    .putString(KEY_SOURCE, REMOTE_CSV);
            String responseEtag = connection.getHeaderField("ETag");
            String responseLastModified = connection.getHeaderField("Last-Modified");
            if (responseEtag != null) editor.putString(KEY_ETAG, responseEtag);
            if (responseLastModified != null) editor.putString(KEY_LAST_MODIFIED, responseLastModified);
            editor.apply();
            return new SyncResult(items, true, checkedAt);
        } finally {
            connection.disconnect();
        }
    }

    private static void writeCache(Context context, byte[] bytes) throws IOException {
        AtomicFile atomicFile = new AtomicFile(new File(context.getFilesDir(), CACHE_FILE));
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            output.flush();
            atomicFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomicFile.failWrite(output);
            throw error;
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        return new String(readLimited(input, MAX_DOWNLOAD_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("連結清單超過 2 MB 限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static List<LinkItem> parseCsv(String csv) throws IOException {
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < csv.length(); i++) {
            char current = csv.charAt(i);
            if (quoted) {
                if (current == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(current);
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (current == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (current != '\r') {
                cell.append(current);
            }
        }
        if (quoted) throw new IOException("CSV 引號格式不完整");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        if (rows.isEmpty() || rows.get(0).size() < 3 ||
                !"分類".equals(rows.get(0).get(0).trim()) ||
                !"名稱".equals(rows.get(0).get(1).trim()) ||
                !"網址".equals(rows.get(0).get(2).trim())) {
            throw new IOException("雲端資料庫清單前三欄必須是：分類、名稱、網址");
        }

        int typeColumn = findColumn(rows.get(0), "類型");
        int folderColumn = findColumn(rows.get(0), "資料夾名稱");

        List<LinkItem> items = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> values = rows.get(i);
            if (values.size() < 3) continue;
            String category = values.get(0).trim();
            String name = values.get(1).trim();
            String url = values.get(2).trim();
            String type = valueAt(values, typeColumn);
            String folderName = valueAt(values, folderColumn);
            if (!category.isEmpty() && !name.isEmpty() && !url.isEmpty()) {
                items.add(new LinkItem(category, name, url, type, folderName));
            }
        }
        return items;
    }

    private static int findColumn(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.get(i).trim())) return i;
        }
        return -1;
    }

    private static String valueAt(List<String> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index).trim() : "";
    }

    static final class SyncResult {
        final List<LinkItem> items;
        final boolean downloaded;
        final long timestamp;

        SyncResult(List<LinkItem> items, boolean downloaded, long timestamp) {
            this.items = items;
            this.downloaded = downloaded;
            this.timestamp = timestamp;
        }
    }
}
