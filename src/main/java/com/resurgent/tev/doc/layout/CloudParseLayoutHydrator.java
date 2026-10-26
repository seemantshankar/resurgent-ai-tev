package com.resurgent.tev.doc.layout;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * When local OCR yields no blocks but {@code cloud_parse_page} has text (LlamaParse),
 * writes one paragraph block per non-empty line so M3 layout consumers see content.
 */
public final class CloudParseLayoutHydrator {

    public static final String EXTRACTOR_ID = "cloud-parse-hydrate";

    public record HydrateCounts(int blocks, int spans) {}

    private CloudParseLayoutHydrator() {}

    public static boolean hasCloudText(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM cloud_parse_page WHERE document_version_id = ?"
                        + " AND (TRIM(COALESCE(page_markdown, '')) != ''"
                        + " OR TRIM(COALESCE(page_text, '')) != '') LIMIT 1")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Fills pages that have cloud text but zero layout blocks. Idempotent for pages
     * that already have blocks.
     */
    public static HydrateCounts hydratePagesWithoutBlocks(DocumentRepository docs,
            long documentVersionId, Connection connection) throws SQLException {
        CloudParsePageRepository cloud = new CloudParsePageRepository(connection);
        int blocks = 0;
        int spans = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_index FROM cloud_parse_page WHERE document_version_id = ?"
                        + " ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pageIndex = rs.getInt(1);
                    String text = cloud.loadPageText(documentVersionId, pageIndex);
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    Optional<Long> pageId = docs.findPageId(documentVersionId, pageIndex);
                    if (pageId.isEmpty()) {
                        continue;
                    }
                    if (countBlocksOnPage(connection, pageId.get()) > 0) {
                        continue;
                    }
                    int[] written = writeLines(docs, pageId.get(), text);
                    blocks += written[0];
                    spans += written[1];
                    markCloudSource(connection, pageId.get());
                }
            }
        }
        return new HydrateCounts(blocks, spans);
    }

    private static int[] writeLines(DocumentRepository docs, long pageId, String pageText)
            throws SQLException {
        int blocks = 0;
        int spanCount = 0;
        int blockIndex = 0;
        for (String raw : pageText.split("\\R")) {
            String line = stripMarkdownLine(raw);
            if (line.isBlank()) {
                continue;
            }
            long blockId = docs.insertBlock(pageId, blockIndex, "paragraph",
                    null, null, null, null,
                    line, PdfLayoutExtractor.normalizeWhitespace(line), blockIndex,
                    EXTRACTOR_ID);
            docs.insertSpan(blockId, 0, line, null, null, null, null, 1.0);
            blocks++;
            spanCount++;
            blockIndex++;
        }
        return new int[] {blocks, spanCount};
    }

    private static String stripMarkdownLine(String raw) {
        if (raw == null) {
            return "";
        }
        String line = raw.trim();
        while (line.startsWith("#")) {
            line = line.substring(1).trim();
        }
        return line.trim();
    }

    private static int countBlocksOnPage(Connection connection, long pageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM block WHERE page_id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void markCloudSource(Connection connection, long pageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page SET parse_source = ? WHERE page_id = ?"
                        + " AND (parse_source IS NULL OR parse_source = ?)")) {
            ps.setString(1, "llama_parse");
            ps.setLong(2, pageId);
            ps.setString(3, "local_ocr");
            ps.executeUpdate();
        }
    }

    /** Pages in cloud storage (may be fewer than layout pages when some are blank). */
    public static List<Integer> cloudPageIndexes(Connection connection, long documentVersionId)
            throws SQLException {
        List<Integer> indexes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_index FROM cloud_parse_page WHERE document_version_id = ?"
                        + " ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getInt(1));
                }
            }
        }
        return indexes;
    }

    /** Human-readable summary for CLI progress. */
    public static String summary(Locale locale, HydrateCounts counts, int cloudPages) {
        return String.format(locale,
                "hydrated %d block(s) / %d span(s) from cloud parse (%d page(s) in store)",
                counts.blocks(), counts.spans(), cloudPages);
    }
}
