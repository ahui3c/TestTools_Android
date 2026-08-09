# TestTools Android 測試工具箱

TestTools 是一套提供手機測試與日常維護使用的 Android 工具程式，將常用測試連結、顯示器亮度測試、浮動快速截圖與影音備份整合在同一個 App 中。

目前版本：**v1.8**

最低支援：**Android 8.0（API 26）**

## 功能模組

### 1. 常用測試素材

- 從雲端資料庫清單載入圖片、影片、音訊及其他測試素材連結。
- 點選後交由瀏覽器或手機中對應的 App 開啟。
- 支援本地快取；無網路時可沿用上一次成功同步的內容。

### 2. 常用程式

- 從雲端資料庫清單載入常用工具、網站及下載頁面。
- 可在 App 內手動重新整理遠端資料。
- 遠端同步失敗時自動使用本地快取或 APK 內建備援清單。

### 3. 測試亮度

- 全螢幕顯示白色方形或圓形測試區域，其餘區域保持全黑。
- 白色區域依螢幕實際解析度動態計算面積比例。
- 內建 5%、10%、25%、50%、75%、100%，並可自由新增、刪除或勾選比例。
- 測試中可向上下左右滑動切換比例，角落會顯示目前比例。
- 可使用最高螢幕亮度，測試期間保持螢幕喚醒。
- 顯示形狀、亮度選項、比例與最後測試位置皆會自動保存。

### 4. 浮動快速截圖

- 在其他 App 上方顯示可拖曳的浮動按鈕。
- 浮動按鈕可顯示目前時間與設備電量。
- 輕點即可保存截圖至 `Pictures/TestTools Captures`。
- 截圖成功時提供白色閃光、輕震動、提示音及按鈕動畫，各項回饋可個別關閉。
- Android 11 以上使用 Accessibility 截圖；首次開啟服務後，每次截圖不需重複確認。
- Android 8～10 使用 MediaProjection；每次擷取工作階段需接受一次系統授權。

> 銀行 App、DRM 串流內容、無痕模式等受保護畫面可能禁止截圖，這是 Android 系統的安全限制。

### 5. 影音快速備份

- 整合 QuickSend，掃描 `DCIM` 與 `Pictures` 中的影音檔案。
- 可經 LocalSend 傳送至同一網路內的接收裝置。
- 可備份至 USB 外接磁碟。
- 支援增量備份紀錄、背景傳輸及失敗重試。

## 安裝

1. 前往 [Releases](../../releases) 下載最新版本 APK。
2. 在 Android 手機上開啟 APK。
3. 若系統提示，允許此來源安裝應用程式。
4. 依使用的功能授予必要權限。

## 雲端資料庫清單

目前遠端資料由 Google 試算表提供，App 對使用者統一顯示為「雲端資料庫清單」。維護文件：

[TestTools 雲端資料庫清單](https://docs.google.com/spreadsheets/d/1ZyY9DQ7WAyIDduiJHuKgYgCJu9zsXoLz5Jecargktvc/edit)

清單前三欄格式如下：

| 分類 | 名稱 | 網址 |
|---|---|---|
| 常用測試素材 | 顯示在 App 的素材名稱 | `https://...` |
| 常用程式 | 顯示在 App 的程式名稱 | `https://...` |

保留第一列欄名。更新遠端內容後，App 可直接重新同步，不需要重新編譯或安裝新版 APK。

## 主要權限用途

| 權限／服務 | 用途 |
|---|---|
| 網路 | 同步雲端資料庫清單、連線 LocalSend |
| 相片與影片／檔案 | 掃描及備份使用者選擇的影音檔案 |
| 無障礙服務 | Android 11 以上在使用者點擊浮動按鈕時擷取螢幕 |
| 顯示在其他 App 上層 | Android 8～10 顯示截圖浮動按鈕 |
| 螢幕擷取 | Android 8～10 建立 MediaProjection 擷取工作階段 |
| 震動 | 提供截圖成功回饋 |

浮動截圖只會在使用者主動點擊按鈕時執行；圖片保存在手機本機，不會由 TestTools 自動上傳。

## 開發與建置

需求：

- Android Studio
- Android SDK 36
- JDK 17

在專案根目錄執行：

```powershell
.\gradlew.bat assembleDebug
```

產生的 APK 位於：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 專案結構

```text
app/        TestTools 主程式與測試功能
quicksend/  影音快速備份 Android library
gradle/     Gradle Wrapper
```

## 授權

授權內容請參閱 [LICENSE](LICENSE)。
