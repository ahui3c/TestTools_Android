package tw.chehu.testtools;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import dalvik.system.ZipPathValidator;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ResourceImportService extends Service {
    private static final String CHANNEL_ID = "resource_import";
    private static final int FOREGROUND_ID = 9301;
    private static final String ACTION_IMPORT = "tw.chehu.testtools.IMPORT_RESOURCE";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_FOLDER = "folder";
    private static final long MAX_EXTRACTED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_DRIVE_FILES = 20_000;
    private static final int MAX_DRIVE_DEPTH = 100;
    private static final String DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String DRIVE_SHORTCUT_MIME = "application/vnd.google-apps.shortcut";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();

    static Intent intentFor(Context context, LinkItem item) {
        return new Intent(context, ResourceImportService.class)
                .setAction(ACTION_IMPORT)
                .putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_URL, item.url)
                .putExtra(EXTRA_TYPE, item.type)
                .putExtra(EXTRA_FOLDER, item.folderName);
    }

    static boolean isSupportedUrl(String address) {
        try {
            String host = Uri.parse(address).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("dropbox.com") || host.endsWith(".dropbox.com");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String providerLabel(String address) {
        try {
            String host = Uri.parse(address).getHost();
            if (host != null && host.toLowerCase(Locale.US).contains("dropbox")) return "Dropbox";
        } catch (RuntimeException ignored) {}
        return "不支援";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_IMPORT.equals(intent.getAction())) return START_NOT_STICKY;
        ensureChannel();
        String name = value(intent, EXTRA_NAME, "未命名資源");
        startForeground(FOREGROUND_ID, notification("等待匯入：" + name, 0, true));
        ImportTask task = new ImportTask(
                name,
                value(intent, EXTRA_URL, ""),
                value(intent, EXTRA_TYPE, ""),
                value(intent, EXTRA_FOLDER, ""));
        pending.incrementAndGet();
        executor.execute(() -> runTask(task, startId));
        return START_NOT_STICKY;
    }

    private void runTask(ImportTask task, int startId) {
        boolean success = false;
        String result;
        try {
            importResource(task);
            success = true;
            result = "匯入完成：" + task.name;
        } catch (Exception error) {
            String reason = error.getMessage();
            result = "匯入失敗：" + task.name + (reason == null ? "" : "\n" + reason);
        }
        showFinished(result, success, task.name.hashCode());
        if (pending.decrementAndGet() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
        }
    }

    private void importResource(ImportTask task) throws Exception {
        if (!"多媒體".equals(task.type) && !"其他".equals(task.type)) {
            throw new IOException("類型必須是「多媒體」或「其他」");
        }
        if (!isSupportedUrl(task.url)) throw new IOException("僅支援 Dropbox 分享連結");
        URL downloadUrl = buildDownloadUrl(task.url);
        File tempDirectory = new File(getCacheDir(), "resource_import");
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) throw new IOException("無法建立暫存空間");
        File temp = File.createTempFile("import_", ".download", tempDirectory);
        HttpURLConnection connection = null;
        String fileName;
        String contentType;
        try {
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "TestTools-Android/1.19");
            connection.setRequestProperty("Accept", "*/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("下載回應 HTTP " + status);
            contentType = cleanMime(connection.getContentType());
            if (contentType.startsWith("text/html")) {
                throw new IOException("分享連結未開放公開下載，或需要登入帳號");
            }
            fileName = responseFileName(connection, task.name, contentType);
            long expected = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(temp)) {
                copy(input, output, expected, task.name, null);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }

        try {
            if (isZip(fileName, contentType)) extractZip(temp, task);
            else try (InputStream input = new FileInputStream(temp)) {
                save(input, fileName, "", task);
            }
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    private URL buildDownloadUrl(String address) throws Exception {
        Uri source = Uri.parse(address);
        String host = source.getHost();
        if (host == null) throw new IOException("分享網址格式不正確");
        String lowerHost = host.toLowerCase(Locale.US);
        if (!lowerHost.contains("dropbox")) throw new IOException("僅支援 Dropbox 分享連結");
        if (lowerHost.contains("dropbox")) {
            Uri.Builder builder = source.buildUpon().clearQuery();
            for (String key : source.getQueryParameterNames()) {
                if (!"dl".equalsIgnoreCase(key) && !"raw".equalsIgnoreCase(key)) {
                    for (String value : source.getQueryParameters(key)) builder.appendQueryParameter(key, value);
                }
            }
            builder.appendQueryParameter("dl", "1");
            return new URL(builder.build().toString());
        }
        if (!lowerHost.contains("google")) throw new IOException("僅支援 Google Drive 與 Dropbox");
        String path = source.getPath() == null ? "" : source.getPath();
        if (path.contains("/folders/")) throw new IOException("Google Drive 資料夾需要唯讀授權");
        String id = source.getQueryParameter("id");
        if (id == null || id.isEmpty()) id = idFromPath(path);
        if (id == null || id.isEmpty()) throw new IOException("無法辨識 Google Drive 檔案 ID");
        String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name());
        if (lowerHost.startsWith("docs.google.com")) {
            if (path.contains("/document/")) return new URL("https://docs.google.com/document/d/" + encodedId + "/export?format=docx");
            if (path.contains("/spreadsheets/")) return new URL("https://docs.google.com/spreadsheets/d/" + encodedId + "/export?format=xlsx");
            if (path.contains("/presentation/")) return new URL("https://docs.google.com/presentation/d/" + encodedId + "/export/pptx");
        }
        String resourceKey = source.getQueryParameter("resourcekey");
        String url = "https://drive.usercontent.google.com/download?id=" + encodedId
                + "&export=download&confirm=t";
        if (resourceKey != null && !resourceKey.isEmpty()) {
            url += "&resourcekey=" + URLEncoder.encode(resourceKey, StandardCharsets.UTF_8.name());
        }
        return new URL(url);
    }

    private String idFromPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("d".equals(parts[i])) return parts[i + 1];
        }
        return null;
    }

    private void importDriveFolder(ImportTask task) throws Exception {
        if (task.driveAccessToken.isEmpty()) {
            throw new IOException("缺少 Google Drive 唯讀授權，請回到資源匯入頁面重新操作");
        }
        String rootId = folderIdFromUrl(task.url);
        if (rootId == null || rootId.isEmpty()) throw new IOException("無法辨識 Google Drive 資料夾 ID");
        String rootResourceKey = Uri.parse(task.url).getQueryParameter("resourcekey");

        List<DriveEntry> files = new ArrayList<>();
        listDriveChildren(rootId, rootResourceKey, "", task.driveAccessToken,
                files, new HashSet<>(), 0);
        if (files.isEmpty()) throw new IOException("Google Drive 資料夾中沒有可下載的檔案");

        long[] transferred = {0};
        for (int index = 0; index < files.size(); index++) {
            DriveEntry entry = files.get(index);
            notifyProgress("正在匯入 " + (index + 1) + "/" + files.size() + "：" + entry.name,
                    (index * 100) / files.size(), false);
            downloadDriveEntry(entry, task, transferred);
        }
    }

    private String folderIdFromUrl(String address) {
        Uri uri = Uri.parse(address);
        String path = uri.getPath();
        if (path == null) return null;
        String[] parts = path.split("/");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("folders".equals(parts[i])) return parts[i + 1];
        }
        return uri.getQueryParameter("id");
    }

    private void listDriveChildren(String folderId, String folderResourceKey,
                                   String relativeFolder, String token, List<DriveEntry> files,
                                   Set<String> visitedFolders, int depth) throws Exception {
        if (depth > MAX_DRIVE_DEPTH) throw new IOException("Google Drive 子資料夾超過 100 層限制");
        if (!visitedFolders.add(folderId)) return;
        String pageToken = null;
        do {
            String query = "'" + folderId.replace("'", "\\'") + "' in parents and trashed=false";
            Uri.Builder uri = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                    .appendQueryParameter("q", query)
                    .appendQueryParameter("pageSize", "1000")
                    .appendQueryParameter("orderBy", "folder,name")
                    .appendQueryParameter("supportsAllDrives", "true")
                    .appendQueryParameter("includeItemsFromAllDrives", "true")
                    .appendQueryParameter("fields",
                            "nextPageToken,files(id,name,mimeType,resourceKey,shortcutDetails(targetId,targetMimeType,targetResourceKey))");
            if (pageToken != null) uri.appendQueryParameter("pageToken", pageToken);
            JSONObject response = readDriveJson(new URL(uri.build().toString()), token,
                    folderResourceKey == null || folderResourceKey.isEmpty()
                            ? null : folderId + "/" + folderResourceKey);
            JSONArray children = response.optJSONArray("files");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.getJSONObject(i);
                    String id = child.optString("id");
                    String name = sanitizeName(child.optString("name"), "未命名檔案");
                    String mime = child.optString("mimeType", "application/octet-stream");
                    String resourceKey = child.optString("resourceKey", "");
                    if (DRIVE_SHORTCUT_MIME.equals(mime)) {
                        JSONObject shortcut = child.optJSONObject("shortcutDetails");
                        if (shortcut == null) continue;
                        id = shortcut.optString("targetId");
                        mime = shortcut.optString("targetMimeType", "application/octet-stream");
                        resourceKey = shortcut.optString("targetResourceKey", resourceKey);
                    }
                    if (id.isEmpty()) continue;
                    if (DRIVE_FOLDER_MIME.equals(mime)) {
                        String nested = relativeFolder.isEmpty() ? name : relativeFolder + "/" + name;
                        listDriveChildren(id, resourceKey, nested, token,
                                files, visitedFolders, depth + 1);
                    } else {
                        files.add(new DriveEntry(id, name, mime, resourceKey, relativeFolder));
                        if (files.size() > MAX_DRIVE_FILES) {
                            throw new IOException("Google Drive 檔案超過 20,000 個安全限制");
                        }
                    }
                }
            }
            pageToken = response.optString("nextPageToken", "");
        } while (!pageToken.isEmpty());
    }

    private JSONObject readDriveJson(URL url, String token, String resourceKey) throws Exception {
        HttpURLConnection connection = openDriveConnection(url, token, resourceKey);
        try {
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("Google Drive 授權已失效或帳號無權存取此資料夾");
            }
            if (status < 200 || status >= 300) throw new IOException("Google Drive 清單回應 HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readLimited(input, 8 * 1024 * 1024);
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private void downloadDriveEntry(DriveEntry entry, ImportTask task, long[] transferred) throws Exception {
        ExportTarget export = exportTarget(entry.mimeType, entry.name);
        Uri.Builder uri;
        String outputName;
        if (export != null) {
            uri = Uri.parse("https://www.googleapis.com/drive/v3/files/" +
                    Uri.encode(entry.id) + "/export").buildUpon()
                    .appendQueryParameter("mimeType", export.mimeType);
            outputName = ensureExtension(entry.name, export.extension);
        } else if (entry.mimeType.startsWith("application/vnd.google-apps.")) {
            throw new IOException("不支援匯出 Google 文件類型：" + entry.name);
        } else {
            uri = Uri.parse("https://www.googleapis.com/drive/v3/files/" +
                    Uri.encode(entry.id)).buildUpon()
                    .appendQueryParameter("alt", "media")
                    .appendQueryParameter("supportsAllDrives", "true");
            outputName = entry.name;
        }
        HttpURLConnection connection = openDriveConnection(
                new URL(uri.build().toString()), task.driveAccessToken,
                entry.resourceKey.isEmpty() ? null : entry.id + "/" + entry.resourceKey);
        try {
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("無法讀取 Google Drive 檔案：" + entry.name);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Google Drive 檔案回應 HTTP " + status + "：" + entry.name);
            }
            try (InputStream input = connection.getInputStream()) {
                save(input, outputName, entry.relativeFolder, task, transferred);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openDriveConnection(URL url, String token, String resourceKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("User-Agent", "TestTools-Android/1.20");
        if (resourceKey != null) connection.setRequestProperty("X-Goog-Drive-Resource-Keys", resourceKey);
        return connection;
    }

    private byte[] readLimited(InputStream input, int maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("Google Drive 清單資料過大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private ExportTarget exportTarget(String mime, String name) {
        if ("application/vnd.google-apps.document".equals(mime)) {
            return new ExportTarget("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx");
        }
        if ("application/vnd.google-apps.spreadsheet".equals(mime)) {
            return new ExportTarget("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
        }
        if ("application/vnd.google-apps.presentation".equals(mime)) {
            return new ExportTarget("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
        }
        if ("application/vnd.google-apps.drawing".equals(mime)) {
            return new ExportTarget("application/pdf", ".pdf");
        }
        return null;
    }

    private String ensureExtension(String name, String extension) {
        return name.toLowerCase(Locale.US).endsWith(extension) ? name : name + extension;
    }

    private void extractZip(File archive, ImportTask task) throws Exception {
        installDropboxZipValidator();
        try (ZipFile zip = new ZipFile(archive)) {
            String commonRoot = commonRoot(zip);
            long[] extracted = {0};
            int files = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = safeZipPath(entry.getName(), commonRoot);
                if (path.isEmpty()) continue;
                int slash = path.lastIndexOf('/');
                String childFolder = slash < 0 ? "" : path.substring(0, slash);
                String fileName = slash < 0 ? path : path.substring(slash + 1);
                try (InputStream input = zip.getInputStream(entry)) {
                    save(input, fileName, childFolder, task, extracted);
                }
                files++;
                if (files > 20_000) throw new IOException("壓縮檔案項目超過 20,000 個限制");
            }
            if (files == 0) throw new IOException("ZIP 中沒有可匯入的檔案");
        }
    }

    private String commonRoot(ZipFile zip) {
        String root = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String normalized = entry.getName().replace('\\', '/');
            int slash = normalized.indexOf('/');
            if (slash <= 0) return null;
            String candidate = normalized.substring(0, slash);
            if (root == null) root = candidate;
            else if (!root.equals(candidate)) return null;
        }
        return root;
    }

    private String safeZipPath(String value, String commonRoot) throws IOException {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("ZIP 包含不安全的絕對路徑");
        }
        if (commonRoot != null && normalized.startsWith(commonRoot + "/")) {
            normalized = normalized.substring(commonRoot.length() + 1);
        }
        StringBuilder safe = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("ZIP 包含不安全的上層路徑");
            if (safe.length() > 0) safe.append('/');
            safe.append(sanitizeName(part, "file"));
        }
        return safe.toString();
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void installDropboxZipValidator() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        ZipPathValidator.setCallback(new ZipPathValidator.Callback() {
            @Override
            public void onZipEntryAccess(String path) throws ZipException {
                String normalized = path.replace('\\', '/');
                if (normalized.indexOf('\0') >= 0 || normalized.matches("^[A-Za-z]:.*")) {
                    throw new ZipException("ZIP 包含不安全的絕對路徑");
                }
                for (String part : normalized.split("/")) {
                    if ("..".equals(part)) throw new ZipException("ZIP 包含不安全的上層路徑");
                }
            }
        });
    }

    private void save(InputStream input, String fileName, String childFolder, ImportTask task) throws Exception {
        save(input, fileName, childFolder, task, null);
    }

    private void save(InputStream input, String fileName, String childFolder, ImportTask task, long[] extracted) throws Exception {
        String safeFile = sanitizeName(fileName, task.name);
        String root = "多媒體".equals(task.type) ? Environment.DIRECTORY_DCIM : Environment.DIRECTORY_DOWNLOADS;
        String configuredFolder = sanitizeFolder(task.folder);
        String relative = root;
        if (!configuredFolder.isEmpty()) relative += "/" + configuredFolder;
        if (!childFolder.isEmpty()) relative += "/" + sanitizePath(childFolder);
        String mime = mimeType(safeFile);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            Uri collection = collection(task.type, mime);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeFile);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("無法在 " + relative + " 建立檔案");
            try {
                try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("無法寫入 " + safeFile);
                    copy(input, output, -1, safeFile, extracted);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            }
            return;
        }

        File directory = new File(Environment.getExternalStorageDirectory(), relative);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("無法建立 " + relative);
        File outputFile = uniqueFile(directory, safeFile);
        try (OutputStream output = new FileOutputStream(outputFile)) {
            copy(input, output, -1, safeFile, extracted);
        }
        MediaScannerConnection.scanFile(this,
                new String[]{outputFile.getAbsolutePath()}, new String[]{mime}, null);
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private Uri collection(String type, String mime) {
        if ("其他".equals(type)) return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        if (mime.startsWith("image/")) return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        if (mime.startsWith("video/")) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private void copy(InputStream input, OutputStream output, long expected, String name, long[] extracted) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        long lastUpdate = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            written += count;
            if (extracted != null) {
                extracted[0] += count;
                if (extracted[0] > MAX_EXTRACTED_BYTES) throw new IOException("解壓縮資料超過 20 GB 安全限制");
            }
            long now = System.currentTimeMillis();
            if (now - lastUpdate > 700) {
                int progress = expected > 0 ? (int) Math.min(100, written * 100 / expected) : 0;
                notifyProgress("正在匯入：" + name, progress, expected <= 0);
                lastUpdate = now;
            }
        }
    }

    private String responseFileName(HttpURLConnection connection, String fallback, String mime) {
        String disposition = connection.getHeaderField("Content-Disposition");
        String name = null;
        if (disposition != null) {
            int encoded = disposition.toLowerCase(Locale.US).indexOf("filename*=utf-8''");
            if (encoded >= 0) {
                String value = disposition.substring(encoded + 17).split(";", 2)[0].trim();
                try { name = URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
                catch (Exception ignored) {}
            }
            if (name == null) {
                int plain = disposition.toLowerCase(Locale.US).indexOf("filename=");
                if (plain >= 0) name = disposition.substring(plain + 9).split(";", 2)[0].trim().replace("\"", "");
            }
        }
        if (name == null || name.isEmpty()) name = sanitizeName(fallback, "resource");
        if (!name.contains(".")) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (extension != null) name += "." + extension;
        }
        return sanitizeName(name, "resource");
    }

    private String cleanMime(String value) {
        if (value == null) return "application/octet-stream";
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase(Locale.US);
    }

    private String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private boolean isZip(String fileName, String mime) {
        return fileName.toLowerCase(Locale.US).endsWith(".zip")
                || "application/zip".equals(mime) || "application/x-zip-compressed".equals(mime);
    }

    private String sanitizeFolder(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String safe = sanitizeName(value.trim(), "");
        return ".".equals(safe) || "..".equals(safe) ? "" : safe;
    }

    private String sanitizePath(String value) {
        StringBuilder safe = new StringBuilder();
        for (String part : value.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            if (safe.length() > 0) safe.append('/');
            safe.append(sanitizeName(part, "folder"));
        }
        return safe.toString();
    }

    private String sanitizeName(String value, String fallback) {
        String safe = value == null ? "" : value.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "");
        if (safe.isEmpty()) safe = fallback;
        if (safe.length() > 180) safe = safe.substring(0, 180);
        return safe;
    }

    private File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        for (int i = 1; i < 10_000; i++) {
            candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, System.currentTimeMillis() + "_" + name);
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "資源匯入", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text, int progress, boolean indeterminate) {
        Intent open = new Intent(this, LinkListActivity.class)
                .putExtra(LinkListActivity.EXTRA_CATEGORY, "資源匯入");
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TestTools 資源匯入")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, progress, indeterminate)
                .build();
    }

    private void notifyProgress(String text, int progress, boolean indeterminate) {
        getSystemService(NotificationManager.class).notify(
                FOREGROUND_ID, notification(text, progress, indeterminate));
    }

    private void showFinished(String text, boolean success, int salt) {
        Notification result = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(success ? "資源匯入完成" : "資源匯入失敗")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(9400 + Math.abs(salt % 500), result);
    }

    private String value(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null ? fallback : value;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class ImportTask {
        final String name;
        final String url;
        final String type;
        final String folder;
        final String driveAccessToken;

        ImportTask(String name, String url, String type, String folder) {
            this.name = name;
            this.url = url;
            this.type = type;
            this.folder = folder;
            this.driveAccessToken = "";
        }
    }

    private static final class DriveEntry {
        final String id;
        final String name;
        final String mimeType;
        final String resourceKey;
        final String relativeFolder;

        DriveEntry(String id, String name, String mimeType, String resourceKey,
                   String relativeFolder) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.resourceKey = resourceKey;
            this.relativeFolder = relativeFolder;
        }
    }

    private static final class ExportTarget {
        final String mimeType;
        final String extension;

        ExportTarget(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }
    }
}
