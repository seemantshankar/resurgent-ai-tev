package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.ingest.BlobStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Deterministic page triage. A page fails when any of the four pixel and
 * confidence checks hold. Nothing here asks a model to grade the text.
 */
public final class PageTriage {

    /** Mean word confidence at or above this stays local. */
    public static final double MEAN_WORD_FLOOR = 0.70;

    /** Numeric and amount spans at or above this stay local. */
    public static final double NUMERIC_SPAN_FLOOR = 0.75;

    /** More than this share of numeric spans below the floor fails the page. */
    public static final double NUMERIC_FAIL_FRACTION = 0.25;

    /** Non-trivial ink with fewer readable characters than this fails the page. */
    public static final int MIN_READABLE_CHARS = 20;

    /** Share of dark pixels that counts as real ink rather than a blank sheet. */
    public static final double INK_NONTRIVIAL = 0.02;

    /** Share of symbol-heavy tokens that counts as garbled. */
    public static final double GARBLED_TOKEN_RATIO = 0.30;

    /** Handwriting, stamp, or overlapped text below this is degraded. */
    public static final double DEGRADED_FLOOR = 0.70;

    static final String DIGITAL_TEXT = "digital_text";
    static final String LOCAL_KEPT = "local_ocr_kept";
    static final String ESCALATED = "escalated_cloud";
    static final String LOCKED = "local_ocr_unverified_residency_locked";
    static final String SOURCE_DIGITAL = "digital_layout";
    static final String SOURCE_OCR = "local_ocr";
    static final String SOURCE_CLOUD = "llama_parse";

    private static final Pattern NUMERIC = Pattern.compile("\\d");
    private static final Pattern AMOUNT = Pattern.compile(
            "[-+]?(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern TOTAL = Pattern.compile("(?i)^\\s*total\\b.*");
    private static final Pattern ATTESTATION = Pattern.compile(
            "(?i).*(attest|certified|sworn|notary|witness|signature|signatory|signed).*");

    private PageTriage() {}

    /** One page after the four checks, before residency chooses the outcome. */
    public record Decision(
            long pageId,
            int pageIndex,
            boolean digital,
            boolean failed,
            String reason,
            Double inkRatio,
            String localMarkdown) {}

    public static List<Decision> evaluate(Connection connection, long documentVersionId,
            BlobStore blobs) throws SQLException {
        List<PageRow> pages = loadPages(connection, documentVersionId);
        List<Decision> decisions = new ArrayList<>();
        for (PageRow page : pages) {
            List<SpanRow> spans = loadSpans(connection, page.pageId);
            List<BlockRow> blocks = loadBlocks(connection, page.pageId);
            List<Grid> grids = loadGrids(connection, page.pageId);
            String markdown = markdown(blocks);
            Double ink = inkRatio(blobs, page.imagePath);
            String reason = reasons(page, spans, blocks, grids, ink, markdown);
            decisions.add(new Decision(page.pageId, page.pageIndex, page.digital,
                    reason != null, reason, ink, markdown));
        }
        return List.copyOf(decisions);
    }

    /**
     * Writes routing for a local-only mandate when parse has not already done it.
     * Passing pages stay {@code digital_text} or {@code local_ocr_kept}. Failing
     * pages stay {@code local_ocr_unverified_residency_locked}.
     */
    public static void applyIfUnrouted(Connection connection, long documentVersionId,
            BlobStore blobs, boolean localOnly) throws SQLException {
        if (!localOnly || alreadyRouted(connection, documentVersionId)) {
            return;
        }
        List<Decision> decisions = evaluate(connection, documentVersionId, blobs);
        for (Decision decision : decisions) {
            update(connection, decision, outcome(decision, true),
                    source(decision, false));
        }
    }

    static boolean alreadyRouted(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM page WHERE document_version_id = ?"
                        + " AND routing_outcome IS NOT NULL")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    static String outcome(Decision decision, boolean localOnly) {
        if (!decision.failed()) {
            return decision.digital() ? DIGITAL_TEXT : LOCAL_KEPT;
        }
        return localOnly ? LOCKED : ESCALATED;
    }

    static String source(Decision decision, boolean escalated) {
        if (escalated) {
            return SOURCE_CLOUD;
        }
        return decision.digital() ? SOURCE_DIGITAL : SOURCE_OCR;
    }

    static void update(Connection connection, Decision decision, String outcome, String source)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page SET routing_outcome = ?, triage_reason = ?, ink_ratio = ?,"
                        + " parse_source = ? WHERE page_id = ?")) {
            ps.setString(1, outcome);
            ps.setString(2, decision.reason());
            if (decision.inkRatio() == null) {
                ps.setNull(3, java.sql.Types.REAL);
            } else {
                ps.setDouble(3, decision.inkRatio());
            }
            ps.setString(4, source);
            ps.setLong(5, decision.pageId());
            ps.executeUpdate();
        }
    }

    private static String reasons(PageRow page, List<SpanRow> spans, List<BlockRow> blocks,
            List<Grid> grids, Double ink, String markdown) {
        Set<String> reasons = new LinkedHashSet<>();
        Double mean = meanConfidence(page, spans);
        if (mean != null && mean < MEAN_WORD_FLOOR) {
            reasons.add(String.format(Locale.ROOT,
                    "mean word ocr_confidence %.2f is below %.2f", mean, MEAN_WORD_FLOOR));
        }
        int numeric = 0;
        int numericLow = 0;
        int readable = 0;
        int tokens = 0;
        int garbled = 0;
        for (SpanRow span : spans) {
            readable += readableChars(span.text);
            if (span.text != null && !span.text.isBlank()) {
                tokens++;
                if (garbled(span.text)) {
                    garbled++;
                }
            }
            if (span.text != null && NUMERIC.matcher(span.text).find()) {
                numeric++;
                if (span.confidence != null && span.confidence < NUMERIC_SPAN_FLOOR) {
                    numericLow++;
                }
            }
        }
        if (numeric > 0 && (double) numericLow / numeric > NUMERIC_FAIL_FRACTION) {
            reasons.add(String.format(Locale.ROOT,
                    "%d of %d numeric spans have confidence below %.2f",
                    numericLow, numeric, NUMERIC_SPAN_FLOOR));
        }
        if (readable == 0) {
            readable = readableChars(markdown);
        }
        if (ink != null && ink >= INK_NONTRIVIAL && readable < MIN_READABLE_CHARS) {
            reasons.add(String.format(Locale.ROOT,
                    "ink density %.3f yielded %d alphanumeric characters", ink, readable));
        }
        if (tokens > 0 && (double) garbled / tokens > GARBLED_TOKEN_RATIO) {
            reasons.add(String.format(Locale.ROOT,
                    "garbled token ratio %.2f", (double) garbled / tokens));
        }
        for (Grid grid : grids) {
            if (inconsistentColumns(grid)) {
                reasons.add("inconsistent column counts across rows");
            }
            if (totalMismatch(grid)) {
                reasons.add("printed Total arithmetic does not match the column");
            }
        }
        String overlap = overlapReason(blocks, spans, grids);
        if (overlap != null) {
            reasons.add(overlap);
        }
        if (reasons.isEmpty()) {
            return null;
        }
        return String.join("; ", reasons);
    }

    private static Double meanConfidence(PageRow page, List<SpanRow> spans) {
        double sum = 0;
        int n = 0;
        for (SpanRow span : spans) {
            if (span.confidence != null) {
                sum += span.confidence;
                n++;
            }
        }
        if (n > 0) {
            return sum / n;
        }
        return page.ocrConfidence;
    }

    private static boolean inconsistentColumns(Grid grid) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Cell cell : grid.cells) {
            counts.merge(cell.row, 1, Integer::sum);
        }
        return counts.values().stream().distinct().count() > 1;
    }

    private static boolean totalMismatch(Grid grid) {
        Map<Integer, Map<Integer, Cell>> byRow = new LinkedHashMap<>();
        for (Cell cell : grid.cells) {
            byRow.computeIfAbsent(cell.row, key -> new LinkedHashMap<>()).put(cell.col, cell);
        }
        for (Map.Entry<Integer, Map<Integer, Cell>> row : byRow.entrySet()) {
            boolean totalRow = row.getValue().values().stream()
                    .anyMatch(cell -> cell.text != null && TOTAL.matcher(cell.text).matches());
            if (!totalRow) {
                continue;
            }
            for (Cell totalCell : row.getValue().values()) {
                Double printed = amount(totalCell.text);
                if (printed == null || (totalCell.text != null
                        && TOTAL.matcher(totalCell.text).matches())) {
                    continue;
                }
                double sum = 0;
                boolean any = false;
                for (Map.Entry<Integer, Map<Integer, Cell>> other : byRow.entrySet()) {
                    if (other.getKey().equals(row.getKey())) {
                        continue;
                    }
                    Cell above = other.getValue().get(totalCell.col);
                    if (above == null) {
                        continue;
                    }
                    Double value = amount(above.text);
                    if (value != null) {
                        sum += value;
                        any = true;
                    }
                }
                if (any && Math.abs(sum - printed) > 0.05) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String overlapReason(List<BlockRow> blocks, List<SpanRow> spans, List<Grid> grids) {
        for (BlockRow mark : blocks) {
            if (!mark.mark()) {
                continue;
            }
            String kind = mark.handwritten ? "handwriting" : "stamp";
            for (SpanRow span : spans) {
                if (span.blockId == mark.blockId || !span.numeric()) {
                    continue;
                }
                if (overlaps(mark, span.x0, span.y0, span.x1, span.y1)
                        && degraded(mark.confidence, span.confidence)) {
                    return kind + " overlaps an amount";
                }
            }
            for (Grid grid : grids) {
                for (Cell cell : grid.cells) {
                    if (cell.blockId == mark.blockId) {
                        continue;
                    }
                    if (overlaps(mark, cell.x0, cell.y0, cell.x1, cell.y1)
                            && degraded(mark.confidence, cell.confidence)) {
                        return kind + " overlaps a table cell";
                    }
                }
            }
            for (BlockRow other : blocks) {
                if (other.blockId == mark.blockId || !other.attestation()) {
                    continue;
                }
                if (overlaps(mark, other.x0, other.y0, other.x1, other.y1)
                        && degraded(mark.confidence, other.confidence)) {
                    return kind + " overlaps an attestation";
                }
            }
        }
        return null;
    }

    private static boolean degraded(Double mark, Double target) {
        return below(mark) || below(target);
    }

    private static boolean below(Double confidence) {
        return confidence == null || confidence < DEGRADED_FLOOR;
    }

    private static boolean overlaps(BlockRow mark, Double x0, Double y0, Double x1, Double y1) {
        if (mark.x0 == null || x0 == null || y0 == null || x1 == null || y1 == null) {
            return false;
        }
        return mark.x0 < x1 && x0 < mark.x1 && mark.y0 < y1 && y0 < mark.y1;
    }

    static Double amount(String text) {
        if (text == null) {
            return null;
        }
        var matcher = AMOUNT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean garbled(String text) {
        int chars = 0;
        int readable = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                chars++;
                if (Character.isLetterOrDigit(cp)) {
                    readable++;
                }
            }
            i += Character.charCount(cp);
        }
        return chars > 0 && (double) readable / chars < 0.5;
    }

    private static int readableChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    private static String markdown(List<BlockRow> blocks) {
        StringBuilder text = new StringBuilder();
        for (BlockRow block : blocks) {
            if (block.text == null || block.text.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(block.text.trim());
        }
        return text.toString();
    }

    static Double inkRatio(BlobStore blobs, String imagePath) {
        if (blobs == null || imagePath == null || imagePath.isBlank()) {
            return null;
        }
        Path png = blobs.root().resolve(imagePath);
        if (!Files.isRegularFile(png)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(png.toFile());
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            int step = Math.max(1, Math.min(width, height) / 80);
            long ink = 0;
            long samples = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff;
                    int g = (rgb >> 8) & 0xff;
                    int b = rgb & 0xff;
                    if (r + g + b < 720) {
                        ink++;
                    }
                    samples++;
                }
            }
            return samples == 0 ? null : (double) ink / samples;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<PageRow> loadPages(Connection connection, long documentVersionId)
            throws SQLException {
        List<PageRow> pages = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id, page_index, ocr_engine, ocr_confidence, image_path"
                        + " FROM page WHERE document_version_id = ? ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String engine = rs.getString("ocr_engine");
                    boolean digital = engine == null || engine.equalsIgnoreCase("none");
                    double confidence = rs.getDouble("ocr_confidence");
                    Double ocr = rs.wasNull() ? null : confidence;
                    pages.add(new PageRow(rs.getLong("page_id"), rs.getInt("page_index"),
                            digital, ocr, rs.getString("image_path")));
                }
            }
        }
        return pages;
    }

    private static List<SpanRow> loadSpans(Connection connection, long pageId) throws SQLException {
        List<SpanRow> spans = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT s.block_id, s.text_raw, s.confidence, s.bbox_x0, s.bbox_y0,"
                        + " s.bbox_x1, s.bbox_y1"
                        + " FROM span s JOIN block b ON b.block_id = s.block_id"
                        + " WHERE b.page_id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    spans.add(new SpanRow(rs.getLong("block_id"), rs.getString("text_raw"),
                            nullableDouble(rs, "confidence"),
                            nullableDouble(rs, "bbox_x0"), nullableDouble(rs, "bbox_y0"),
                            nullableDouble(rs, "bbox_x1"), nullableDouble(rs, "bbox_y1")));
                }
            }
        }
        return spans;
    }

    private static List<BlockRow> loadBlocks(Connection connection, long pageId) throws SQLException {
        List<BlockRow> blocks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT block_id, block_kind, is_handwritten, ocr_confidence, text_raw,"
                        + " bbox_x0, bbox_y0, bbox_x1, bbox_y1"
                        + " FROM block WHERE page_id = ? ORDER BY reading_order, block_index")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    blocks.add(new BlockRow(rs.getLong("block_id"), rs.getString("block_kind"),
                            rs.getInt("is_handwritten") == 1, nullableDouble(rs, "ocr_confidence"),
                            rs.getString("text_raw"),
                            nullableDouble(rs, "bbox_x0"), nullableDouble(rs, "bbox_y0"),
                            nullableDouble(rs, "bbox_x1"), nullableDouble(rs, "bbox_y1")));
                }
            }
        }
        return blocks;
    }

    private static List<Grid> loadGrids(Connection connection, long pageId) throws SQLException {
        Map<Long, Grid> grids = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT tg.table_grid_id, tg.block_id, b.ocr_confidence,"
                        + " tc.row_index, tc.col_index, tc.text_raw,"
                        + " tc.bbox_x0, tc.bbox_y0, tc.bbox_x1, tc.bbox_y1"
                        + " FROM table_cell tc"
                        + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " WHERE b.page_id = ?"
                        + " ORDER BY tg.table_grid_id, tc.row_index, tc.col_index")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long gridId = rs.getLong("table_grid_id");
                    Grid grid = grids.computeIfAbsent(gridId, id -> new Grid());
                    grid.cells.add(new Cell(rs.getLong("block_id"), rs.getInt("row_index"),
                            rs.getInt("col_index"), rs.getString("text_raw"),
                            nullableDouble(rs, "ocr_confidence"),
                            nullableDouble(rs, "bbox_x0"), nullableDouble(rs, "bbox_y0"),
                            nullableDouble(rs, "bbox_x1"), nullableDouble(rs, "bbox_y1")));
                }
            }
        }
        return List.copyOf(grids.values());
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private record PageRow(long pageId, int pageIndex, boolean digital, Double ocrConfidence,
            String imagePath) {}

    private record SpanRow(long blockId, String text, Double confidence,
            Double x0, Double y0, Double x1, Double y1) {
        boolean numeric() {
            return text != null && NUMERIC.matcher(text).find();
        }
    }

    private record BlockRow(long blockId, String kind, boolean handwritten, Double confidence,
            String text, Double x0, Double y0, Double x1, Double y1) {
        boolean mark() {
            return handwritten || "handwriting".equals(kind) || "stamp".equals(kind);
        }

        boolean attestation() {
            return "signature".equals(kind)
                    || (text != null && ATTESTATION.matcher(text).find());
        }
    }

    private static final class Grid {
        private final List<Cell> cells = new ArrayList<>();
    }

    private record Cell(long blockId, int row, int col, String text, Double confidence,
            Double x0, Double y0, Double x1, Double y1) {}
}
