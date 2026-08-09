import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const projectRoot = process.cwd();
const outputDir = path.join(projectRoot, "outputs", "testtools");
const assetDir = path.join(projectRoot, "app", "src", "main", "assets");
await fs.mkdir(outputDir, { recursive: true });
await fs.mkdir(assetDir, { recursive: true });

const workbook = Workbook.create();
const links = workbook.worksheets.add("連結清單");
links.showGridLines = false;
links.freezePanes.freezeRows(1);

links.getRange("A1:C9").values = [
  ["分類", "名稱", "網址"],
  ["常用測試素材", "EIZO 螢幕測試", "https://www.eizo.be/monitor-test/"],
  ["常用測試素材", "Lagom LCD 測試頁", "https://www.lagom.nl/lcd-test/"],
  ["常用測試素材", "Blender Sintel 測試影片", "https://durian.blender.org/download/"],
  ["常用測試素材", "Web Audio 音訊測試", "https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API/Using_Web_Audio_API"],
  ["常用程式", "Android SDK Platform Tools", "https://developer.android.com/tools/releases/platform-tools"],
  ["常用程式", "scrcpy", "https://github.com/Genymobile/scrcpy/releases"],
  ["常用程式", "CPU-Z Android", "https://play.google.com/store/apps/details?id=com.cpuid.cpu_z"],
  ["常用程式", "AIDA64 Android", "https://play.google.com/store/apps/details?id=com.finalwire.aida64"],
];

links.getRange("A1:C1").format = {
  fill: "#2563EB",
  font: { bold: true, color: "#FFFFFF" },
  verticalAlignment: "center",
};
links.getRange("A2:C9").format = {
  fill: "#FFFFFF",
  font: { color: "#0F172A" },
  verticalAlignment: "center",
  borders: { insideHorizontal: { style: "thin", color: "#E2E8F0" } },
};
links.getRange("A2:A9").format.fill = "#EFF6FF";
links.getRange("A1:A9").format.columnWidth = 20;
links.getRange("B1:B9").format.columnWidth = 28;
links.getRange("C1:C9").format.columnWidth = 78;
links.getRange("A1:C1").format.rowHeight = 28;
links.getRange("A2:C9").format.rowHeight = 25;
links.getRange("A2:A200").dataValidation = {
  rule: { type: "list", values: ["常用測試素材", "常用程式"] },
};
links.tables.add("A1:C9", true, "LinksTable").style = "TableStyleMedium2";

const guide = workbook.worksheets.add("使用說明");
guide.showGridLines = false;
guide.getRange("A1:D1").merge();
guide.getRange("A1").values = [["測試工具箱連結清單｜維護說明"]];
guide.getRange("A1:D1").format = {
  fill: "#0F172A",
  font: { bold: true, color: "#FFFFFF", size: 16 },
  verticalAlignment: "center",
};
guide.getRange("A1:D1").format.rowHeight = 36;
guide.getRange("A3:B7").values = [
  ["步驟", "操作"],
  ["1", "到「連結清單」工作表新增或修改資料。"],
  ["2", "分類只能使用「常用測試素材」或「常用程式」。"],
  ["3", "網址請填完整的 https:// 連結。"],
  ["4", "儲存後覆蓋 app/src/main/assets/links.xlsx，再重新建置 App。"],
];
guide.getRange("A3:B3").format = {
  fill: "#2563EB",
  font: { bold: true, color: "#FFFFFF" },
};
guide.getRange("A4:B7").format = {
  borders: { insideHorizontal: { style: "thin", color: "#E2E8F0" } },
  wrapText: true,
  verticalAlignment: "center",
};
guide.getRange("A3:A7").format.columnWidth = 10;
guide.getRange("B3:B7").format.columnWidth = 72;
guide.getRange("A4:B7").format.rowHeight = 34;

const preview = await workbook.render({
  sheetName: "連結清單",
  range: "A1:C9",
  scale: 1.5,
  format: "png",
});
await fs.writeFile(path.join(outputDir, "links-preview.png"), new Uint8Array(await preview.arrayBuffer()));

const inspection = await workbook.inspect({
  kind: "table",
  range: "連結清單!A1:C9",
  include: "values,formulas",
  tableMaxRows: 12,
  tableMaxCols: 4,
});
console.log(inspection.ndjson);

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
  options: { useRegex: true, maxResults: 50 },
  summary: "final formula error scan",
});
console.log(errors.ndjson);

const xlsx = await SpreadsheetFile.exportXlsx(workbook);
const outputPath = path.join(outputDir, "links.xlsx");
const assetPath = path.join(assetDir, "links.xlsx");
await xlsx.save(outputPath);
await fs.copyFile(outputPath, assetPath);
console.log(`Saved: ${outputPath}`);
console.log(`Copied: ${assetPath}`);
