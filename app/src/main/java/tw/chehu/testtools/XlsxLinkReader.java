package tw.chehu.testtools;

import android.content.Context;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

final class XlsxLinkReader {
    private XlsxLinkReader() {}

    static List<LinkItem> read(Context context) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (InputStream input = context.getAssets().open("links.xlsx");
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/sharedStrings.xml") || entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                    files.put(entry.getName(), out.toByteArray());
                }
            }
        }

        List<String> shared = parseSharedStrings(files.get("xl/sharedStrings.xml"));
        byte[] sheetBytes = files.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) throw new IllegalStateException("找不到 Excel 第一個工作表");

        NodeList rows = document(sheetBytes).getElementsByTagName("row");
        List<LinkItem> items = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.getLength(); rowIndex++) {
            NodeList cells = ((Element) rows.item(rowIndex)).getElementsByTagName("c");
            String[] values = new String[] {"", "", "", "", ""};
            for (int i = 0; i < cells.getLength(); i++) {
                Element cell = (Element) cells.item(i);
                int column = columnIndex(cell.getAttribute("r"));
                if (column >= 0 && column < values.length) values[column] = cellValue(cell, shared);
            }
            if (!values[0].trim().isEmpty() && !values[1].trim().isEmpty() && !values[2].trim().isEmpty()) {
                items.add(new LinkItem(
                        values[0].trim(), values[1].trim(), values[2].trim(),
                        values[3].trim(), values[4].trim()));
            }
        }
        return items;
    }

    private static List<String> parseSharedStrings(byte[] bytes) throws Exception {
        List<String> result = new ArrayList<>();
        if (bytes == null) return result;
        NodeList entries = document(bytes).getElementsByTagName("si");
        for (int i = 0; i < entries.getLength(); i++) {
            NodeList texts = ((Element) entries.item(i)).getElementsByTagName("t");
            StringBuilder joined = new StringBuilder();
            for (int j = 0; j < texts.getLength(); j++) joined.append(texts.item(j).getTextContent());
            result.add(joined.toString());
        }
        return result;
    }

    private static String cellValue(Element cell, List<String> shared) {
        String type = cell.getAttribute("t");
        NodeList inline = cell.getElementsByTagName("t");
        if ("inlineStr".equals(type) && inline.getLength() > 0) return inline.item(0).getTextContent();
        NodeList raw = cell.getElementsByTagName("v");
        if (raw.getLength() == 0) return inline.getLength() > 0 ? inline.item(0).getTextContent() : "";
        String value = raw.item(0).getTextContent();
        if ("s".equals(type)) {
            int index = Integer.parseInt(value);
            return index >= 0 && index < shared.size() ? shared.get(index) : "";
        }
        return value;
    }

    private static org.w3c.dom.Document document(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 部分 Android 廠商的 XML 實作不認得 Apache feature URI。
        // XLSX 是 App 內建的可信任資產；支援時仍停用 DOCTYPE，不支援時則安全略過。
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | AbstractMethodError | UnsupportedOperationException ignored) {
            // 不讓廠商自訂 XML parser 的功能差異阻止讀取 App 內建 Excel。
        }
    }

    private static int columnIndex(String reference) {
        int result = 0;
        int letters = 0;
        while (letters < reference.length() && Character.isLetter(reference.charAt(letters))) {
            result = result * 26 + (Character.toUpperCase(reference.charAt(letters)) - 'A' + 1);
            letters++;
        }
        return result - 1;
    }
}
