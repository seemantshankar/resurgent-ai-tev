package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.model.ExternalLinksTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExternalBook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExternalLink;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExternalSheetName;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExternalSheetNames;

/**
 * Parses external link parts from an XLSX/XLSM OPC package.
 *
 * <p>Only package-local XML and relationship files are read; no network
 * requests are made. The returned {@link ExternalLinkIn} objects carry the
 * 1-based {@code linkIndex} that matches the {@code [n]} token in formulas.
 */
public final class ExternalLinkParser {

    private ExternalLinkParser() {}

    /**
     * Returns all external links in the workbook in index order.
     *
     * @throws IOException if the package cannot be read
     */
    public static List<ExternalLinkIn> parse(Workbook workbook) throws IOException {
        if (!(workbook instanceof XSSFWorkbook xssf)) {
            return List.of();
        }
        List<ExternalLinksTable> tables = xssf.getExternalLinksTable();
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        List<ExternalLinkIn> result = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            ExternalLinksTable table = tables.get(i);
            int linkIndex = i + 1;

            String targetUri = table.getLinkedFileName();
            // POI's generated CTExternalLink/CTExternalBook classes do not expose the
            // optional @refreshError attribute, so we infer a broken link from a missing
            // target URI. The underlying XML attribute could be read via XmlBeans if
            // needed later; see parser-strategy-v2 §10.2.
            boolean refreshError = targetUri == null || targetUri.isBlank();
            String targetDisplay = displayName(targetUri);
            String rawPartName = table.getPackagePart().getPartName().getName();
            List<String> sheetNames = parseSheetNames(table.getCTExternalLink());

            result.add(new ExternalLinkIn(linkIndex, targetUri, targetDisplay, refreshError,
                    sheetNames, rawPartName));
        }
        return result;
    }

    private static List<String> parseSheetNames(CTExternalLink link) {
        List<String> names = new ArrayList<>();
        if (link == null) {
            return names;
        }
        CTExternalBook book = link.getExternalBook();
        if (book == null) {
            return names;
        }
        CTExternalSheetNames sheetNames = book.getSheetNames();
        if (sheetNames == null) {
            return names;
        }
        for (CTExternalSheetName sheetName : sheetNames.getSheetNameArray()) {
            if (sheetName != null && sheetName.getVal() != null) {
                names.add(sheetName.getVal());
            }
        }
        return names;
    }

    private static String displayName(String targetUri) {
        if (targetUri == null || targetUri.isBlank()) {
            return null;
        }
        String path = targetUri;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            path = path.substring(slash + 1);
        }
        int backSlash = path.lastIndexOf('\\');
        if (backSlash >= 0 && backSlash < path.length() - 1) {
            path = path.substring(backSlash + 1);
        }
        return path;
    }
}
