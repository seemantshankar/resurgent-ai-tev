package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackPassage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads layout blocks and table cells when the fixture extractor has no sidecar.
 * Label lines and pack-table grids become the same fact candidates a sidecar would,
 * and heading lines are copied into {@code cloud_parse_page} so passage assessment
 * can see them without a cloud parse.
 */
final class LayoutExtractBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayoutExtractBridge() {}

    /** Copies layout into parse pages when this version has none yet. */
    static void materializeParseIfAbsent(Connection connection, long documentVersionId,
            DocTypePack pack) throws SQLException {
        CloudParsePageRepository pages = new CloudParsePageRepository(connection);
        if (!pages.loadTextPages(documentVersionId).isEmpty()) {
            return;
        }
        List<String> headings = headingTerms(pack);
        Map<Integer, StringBuilder> markdown = new LinkedHashMap<>();
        Map<Integer, StringBuilder> plain = new LinkedHashMap<>();
        for (BlockLine line : loadBlocks(connection, documentVersionId)) {
            StringBuilder md = markdown.computeIfAbsent(line.page(), k -> new StringBuilder());
            StringBuilder text = plain.computeIfAbsent(line.page(), k -> new StringBuilder());
            if (md.length() > 0) {
                md.append('\n');
                text.append('\n');
            }
            String heading = headingOf(line.text(), headings);
            if (heading != null) {
                md.append("# ").append(heading).append('\n');
            }
            md.append(line.text());
            text.append(line.text());
        }
        for (Grid grid : loadGrids(connection, documentVersionId)) {
            StringBuilder md = markdown.computeIfAbsent(grid.page(), k -> new StringBuilder());
            StringBuilder text = plain.computeIfAbsent(grid.page(), k -> new StringBuilder());
            for (List<Cell> row : grid.cells()) {
                if (md.length() > 0) {
                    md.append('\n');
                    text.append('\n');
                }
                for (Cell cell : row) {
                    String heading = headingOf(cell.text(), headings);
                    if (heading != null) {
                        md.append("# ").append(heading).append('\n');
                        break;
                    }
                }
                String joined = row.stream().map(Cell::text).reduce((a, b) -> a + " | " + b)
                        .orElse("");
                md.append(joined);
                text.append(joined);
            }
        }
        if (markdown.isEmpty()) {
            return;
        }
        int max = 0;
        for (int page : markdown.keySet()) {
            max = Math.max(max, page);
        }
        List<String> markdownPages = new ArrayList<>();
        List<String> textPages = new ArrayList<>();
        for (int page = 1; page <= max; page++) {
            StringBuilder md = markdown.get(page);
            StringBuilder text = plain.get(page);
            markdownPages.add(md == null ? "" : md.toString());
            textPages.add(text == null ? "" : text.toString());
        }
        pages.replacePages(documentVersionId, "local-layout", "local", markdownPages, textPages);
    }

    /** Layout-derived facts, or {@code original} when the layout has nothing to read. */
    static ExtractResult fromLayout(Connection connection, long documentVersionId,
            DocTypePack pack, ExtractResult original) throws SQLException {
        List<Label> labels = labels(pack);
        List<TableSpec> tables = tables(pack);
        List<FactCandidate> facts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        List<BlockLine> blocks = loadBlocks(connection, documentVersionId);
        List<List<Cell>> pipe = new ArrayList<>();
        int pipePage = 1;
        for (BlockLine line : blocks) {
            if (line.text().contains("|")) {
                List<String> cells = splitPipe(line.text());
                if (cells.size() >= 2) {
                    if (pipe.isEmpty()) {
                        pipePage = line.page();
                    }
                    List<Cell> row = new ArrayList<>();
                    for (String cell : cells) {
                        row.add(new Cell(cell, line.text(), line.page()));
                    }
                    pipe.add(row);
                    continue;
                }
            }
            flushPipe(pipe, pipePage, tables, labels, pack, facts, citations);
            pipe.clear();
            readScalar(line.text(), line.text(), line.page(), labels, pack, facts, citations);
        }
        flushPipe(pipe, pipePage, tables, labels, pack, facts, citations);
        for (Grid grid : loadGrids(connection, documentVersionId)) {
            readGrid(grid, tables, labels, pack, facts, citations);
        }
        if (facts.isEmpty()) {
            return original;
        }
        return new ExtractResult(original.extractorId(), original.payload(), facts, citations,
                original.stats());
    }

    private static void flushPipe(List<List<Cell>> rows, int page, List<TableSpec> tables,
            List<Label> labels, DocTypePack pack, List<FactCandidate> facts,
            List<Citation> citations) {
        if (rows.isEmpty()) {
            return;
        }
        readCells(page, rows, tables, labels, pack, facts, citations);
    }

    private static void readGrid(Grid grid, List<TableSpec> tables, List<Label> labels,
            DocTypePack pack, List<FactCandidate> facts, List<Citation> citations) {
        List<List<Cell>> rows = new ArrayList<>();
        for (List<Cell> row : grid.cells()) {
            rows.add(row);
        }
        readCells(grid.page(), rows, tables, labels, pack, facts, citations);
    }

    private static void readCells(int page, List<List<Cell>> rows, List<TableSpec> tables,
            List<Label> labels, DocTypePack pack, List<FactCandidate> facts,
            List<Citation> citations) {
        int headerAt = -1;
        TableSpec matched = null;
        Map<Integer, String> columns = Map.of();
        for (int i = 0; i < rows.size(); i++) {
            for (TableSpec table : tables) {
                Map<Integer, String> mapped = mapHeader(rows.get(i), table);
                if (mapped != null) {
                    headerAt = i;
                    matched = table;
                    columns = mapped;
                    break;
                }
            }
            if (headerAt >= 0) {
                break;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            List<Cell> row = rows.get(i);
            if (i == headerAt) {
                continue;
            }
            if (matched != null && i > headerAt) {
                expandRow(matched, columns, row, pack, facts, citations);
                continue;
            }
            if (row.size() >= 2 && !row.get(0).text().isBlank() && !row.get(1).text().isBlank()) {
                readScalar(row.get(0).text(), row.get(1).text(), page == 0 ? row.get(1).page() : page,
                        labels, pack, facts, citations);
            }
        }
    }

    private static void expandRow(TableSpec table, Map<Integer, String> columns, List<Cell> row,
            DocTypePack pack, List<FactCandidate> facts, List<Citation> citations) {
        ObjectNode node = MAPPER.createObjectNode();
        boolean any = false;
        for (Map.Entry<Integer, String> entry : columns.entrySet()) {
            int index = entry.getKey();
            if (index >= row.size()) {
                continue;
            }
            Cell cell = row.get(index);
            if (cell.text().isBlank()) {
                continue;
            }
            ObjectNode value = node.putObject(entry.getValue());
            value.put("value", cell.text());
            value.put("quote", cell.quote());
            value.put("page", cell.page());
            any = true;
        }
        if (!any) {
            return;
        }
        var itemProps = pack.toExtractSchema().path("properties").path(table.name())
                .path("items").path("properties");
        ScheduleCells.expand(table.name(), itemProps, node, facts, citations);
    }

    private static Map<Integer, String> mapHeader(List<Cell> row, TableSpec table) {
        Map<Integer, String> mapped = new LinkedHashMap<>();
        boolean key = false;
        for (int i = 0; i < row.size(); i++) {
            String snake = norm(row.get(i).text()).replace(' ', '_');
            for (String column : table.columns()) {
                if (snake.equals(column)) {
                    mapped.put(i, column);
                    if (column.equals(table.keyColumn())) {
                        key = true;
                    }
                }
            }
        }
        if (!key || mapped.size() < Math.min(2, table.columns().size())) {
            return null;
        }
        return mapped;
    }

    private static void readScalar(String labelText, String valueOrLine, int page,
            List<Label> labels, DocTypePack pack, List<FactCandidate> facts,
            List<Citation> citations) {
        String left;
        String value;
        String quote;
        if (labelText == valueOrLine) {
            int colon = labelText.indexOf(':');
            if (colon <= 2 || colon >= 50) {
                return;
            }
            left = labelText.substring(0, colon).trim();
            value = labelText.substring(colon + 1).trim();
            quote = labelText;
        } else {
            left = labelText;
            value = valueOrLine;
            quote = valueOrLine;
        }
        if (left.isBlank() || value.isBlank()) {
            return;
        }
        Label label = match(left, labels);
        if (label == null) {
            return;
        }
        for (FactCandidate existing : facts) {
            if (existing.key().equals(label.key())) {
                return;
            }
        }
        ValueKind kind = label.kind();
        FactCandidate candidate = new FactCandidate(label.key(), kind)
                .valueText(value)
                .confidence(0.9)
                .extractionMethod("deterministic");
        if (page >= 1 && quote != null && !quote.isBlank()) {
            Citation citation = Citation.of(page, quote);
            candidate.addCitation(citation);
            citations.add(citation);
        }
        facts.add(candidate);
    }

    private static Label match(String left, List<Label> labels) {
        String n = norm(left);
        if (n.isEmpty()) {
            return null;
        }
        for (Label label : labels) {
            if (n.equals(label.norm())) {
                return label;
            }
        }
        for (Label label : labels) {
            if (label.norm().length() >= 8 && n.contains(label.norm())) {
                return label;
            }
        }
        return null;
    }

    private static List<Label> labels(DocTypePack pack) {
        List<Label> labels = new ArrayList<>();
        for (PackField field : pack.fields()) {
            addLabel(labels, field.promptHint(), field);
            addLabel(labels, field.key().replace('_', ' '), field);
            int sep = field.path().lastIndexOf(" > ");
            addLabel(labels, sep < 0 ? field.path() : field.path().substring(sep + 3), field);
        }
        for (PackField field : pack.fields()) {
            for (String synonym : field.synonyms()) {
                addLabel(labels, synonym, field);
            }
        }
        labels.sort((a, b) -> Integer.compare(b.norm().length(), a.norm().length()));
        return labels;
    }

    private static void addLabel(List<Label> labels, String text, PackField field) {
        if (text == null || text.isBlank()) {
            return;
        }
        String n = norm(text);
        if (n.length() < 3) {
            return;
        }
        for (Label label : labels) {
            if (label.norm().equals(n)) {
                return;
            }
        }
        labels.add(new Label(n, field.key(), field.valueKind()));
    }

    private static List<TableSpec> tables(DocTypePack pack) {
        List<TableSpec> tables = new ArrayList<>();
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            Object key = table.get("key_column");
            if (name == null || key == null) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            Object fields = table.get("fields");
            if (fields instanceof List<?> list) {
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> map && map.get("key") != null) {
                        columns.add(String.valueOf(map.get("key")));
                    }
                }
            }
            if (!columns.isEmpty()) {
                tables.add(new TableSpec(String.valueOf(name), columns, String.valueOf(key)));
            }
        }
        return tables;
    }

    private static List<String> headingTerms(DocTypePack pack) {
        List<String> terms = new ArrayList<>();
        for (PackPassage passage : pack.passages()) {
            terms.addAll(passage.headingTerms());
        }
        return terms;
    }

    private static String headingOf(String text, List<String> headings) {
        String n = norm(text);
        for (String heading : headings) {
            if (n.equals(norm(heading))) {
                return text.trim();
            }
        }
        return null;
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static List<String> splitPipe(String line) {
        List<String> cells = new ArrayList<>();
        for (String part : line.split("\\|")) {
            if (!part.isBlank()) {
                cells.add(part.trim());
            }
        }
        return cells;
    }

    private static List<BlockLine> loadBlocks(Connection connection, long documentVersionId)
            throws SQLException {
        List<BlockLine> lines = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.page_index, b.block_kind,"
                        + " COALESCE(NULLIF(b.text_raw, ''), b.text_norm)"
                        + " FROM block b JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, b.block_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(2);
                    if ("table".equals(kind)) {
                        continue;
                    }
                    String text = rs.getString(3);
                    if (text != null && !text.isBlank()) {
                        lines.add(new BlockLine(rs.getInt(1), text.trim()));
                    }
                }
            }
        }
        return lines;
    }

    private static List<Grid> loadGrids(Connection connection, long documentVersionId)
            throws SQLException {
        Map<Long, Grid> grids = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT tg.table_grid_id, p.page_index, tc.row_index, tc.text_raw"
                        + " FROM table_cell tc"
                        + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, tg.table_grid_id, tc.row_index, tc.col_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    int page = rs.getInt(2);
                    int rowIndex = rs.getInt(3);
                    String text = rs.getString(4) == null ? "" : rs.getString(4).trim();
                    Grid grid = grids.computeIfAbsent(id, k -> new Grid(page));
                    while (grid.cells().size() <= rowIndex) {
                        grid.cells().add(new ArrayList<>());
                    }
                    grid.cells().get(rowIndex).add(new Cell(text, text, page));
                }
            }
        }
        return List.copyOf(grids.values());
    }

    private record Label(String norm, String key, ValueKind kind) {}

    private record TableSpec(String name, List<String> columns, String keyColumn) {}

    private record BlockLine(int page, String text) {}

    private record Cell(String text, String quote, int page) {}

    private static final class Grid {
        private final int page;
        private final List<List<Cell>> cells = new ArrayList<>();

        private Grid(int page) {
            this.page = page;
        }

        private int page() {
            return page;
        }

        private List<List<Cell>> cells() {
            return cells;
        }
    }
}
