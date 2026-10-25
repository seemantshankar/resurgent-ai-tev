package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.PassageRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackPassage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads layout blocks and table cells when the fixture extractor has no sidecar.
 * Label lines and pack-table grids become the same fact candidates a sidecar would,
 * and heading lines are copied into {@code cloud_parse_page} so passage assessment
 * can see them without a cloud parse.
 */
final class LayoutExtractBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A line that is only a figure, optionally with the unit the page states. */
    private static final Pattern FIGURE = Pattern.compile(
            "^\\s*[₹$]?\\s*\\d[\\d,]*(?:\\.\\d+)?(?:\\s+[\\p{L}./]+)?\\s*[.!?]?\\s*$");

    private static final Pattern FIGURE_NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final Pattern TRAILING_COPULA = Pattern.compile(
            "(?i)\\s+\\b(is|are|was|were|be)\\b\\s*$");

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

    /**
     * Label pass, then the key-value lines it missed, joining a sentence or a table
     * across a page break. {@code pages} null reads every page of the version; a
     * set reads only those file pages.
     */
    static LocalReading readLocal(Connection connection, long documentVersionId,
            DocTypePack pack, Set<Integer> pages) throws SQLException {
        ExtractResult labeled = fromLayout(connection, documentVersionId, pack,
                new ExtractResult(pack.code(), null, List.of(), List.of(), null), pages);
        return appendMissed(connection, documentVersionId, pack, labeled, pages);
    }

    /** Lines still unread after both local passes, in reading order. */
    record LocalReading(ExtractResult result, List<String> unread) {}

    /** Layout-derived facts, or {@code original} when the layout has nothing to read. */
    static ExtractResult fromLayout(Connection connection, long documentVersionId,
            DocTypePack pack, ExtractResult original) throws SQLException {
        return fromLayout(connection, documentVersionId, pack, original, null);
    }

    /** Layout-derived facts on {@code pages} only. Null reads every page. */
    static ExtractResult fromLayout(Connection connection, long documentVersionId,
            DocTypePack pack, ExtractResult original, Set<Integer> pages) throws SQLException {
        List<Label> labels = labels(pack);
        List<TableSpec> tables = tables(pack);
        List<FactCandidate> facts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        List<BlockLine> blocks = new ArrayList<>();
        for (BlockLine line : loadBlocks(connection, documentVersionId)) {
            if (pages == null || pages.contains(line.page())) {
                blocks.add(line);
            }
        }
        List<Grid> grids = new ArrayList<>();
        for (Grid grid : loadGrids(connection, documentVersionId)) {
            if (pages == null || pages.contains(grid.page())) {
                grids.add(grid);
            }
        }
        Set<Integer> gridPages = new HashSet<>();
        Set<Integer> blockPages = new HashSet<>();
        for (Grid grid : grids) {
            gridPages.add(grid.page());
        }
        for (BlockLine line : blocks) {
            blockPages.add(line.page());
        }
        readTextLines(blocks, gridPages, tables, labels, pack, facts, citations);
        readCloudLines(connection, documentVersionId, blockPages, gridPages, tables, labels,
                pack, facts, citations, pages);
        for (Grid grid : grids) {
            readGrid(grid, tables, labels, pack, facts, citations);
        }
        if (facts.isEmpty()) {
            return original;
        }
        return new ExtractResult(original.extractorId(), original.payload(), facts, citations,
                original.stats());
    }

    private static void readTextLines(List<BlockLine> blocks, Set<Integer> gridPages,
            List<TableSpec> tables, List<Label> labels, DocTypePack pack,
            List<FactCandidate> facts, List<Citation> citations) {
        List<List<Cell>> pipe = new ArrayList<>();
        int pipePage = 1;
        for (BlockLine line : blocks) {
            if (line.text().contains("|") && !gridPages.contains(line.page())) {
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
            if (gridPages.contains(line.page()) && line.text().contains("|")) {
                continue;
            }
            readScalar(line.text(), line.text(), line.page(), labels, pack, facts, citations);
        }
        flushPipe(pipe, pipePage, tables, labels, pack, facts, citations);
    }

    /** Markdown for pages that have no layout blocks, such as a cloud-escalated page. */
    private static void readCloudLines(Connection connection, long documentVersionId,
            Set<Integer> blockPages, Set<Integer> gridPages, List<TableSpec> tables,
            List<Label> labels, DocTypePack pack, List<FactCandidate> facts,
            List<Citation> citations, Set<Integer> onlyPages) throws SQLException {
        List<PassageRepository.ParsePage> pages = new PassageRepository(connection)
                .parsePages(documentVersionId);
        List<BlockLine> lines = new ArrayList<>();
        for (PassageRepository.ParsePage page : pages) {
            if (onlyPages != null && !onlyPages.contains(page.pageIndex())) {
                continue;
            }
            if (blockPages.contains(page.pageIndex()) || page.text() == null) {
                continue;
            }
            for (String raw : page.text().split("\\R")) {
                String line = raw.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(new BlockLine(page.pageIndex(), line));
                }
            }
        }
        readTextLines(lines, gridPages, tables, labels, pack, facts, citations);
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
        boolean anchor = false;
        String anchorColumn = table.keyColumn() != null ? table.keyColumn() : table.periodColumn();
        for (int i = 0; i < row.size(); i++) {
            String snake = norm(row.get(i).text()).replace(' ', '_');
            for (String column : table.columns()) {
                if (snake.equals(column)) {
                    mapped.put(i, column);
                    if (column.equals(anchorColumn)) {
                        anchor = true;
                    }
                }
            }
        }
        if (anchorColumn == null || !anchor
                || mapped.size() < Math.min(2, table.columns().size())) {
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

    /**
     * Key-value lines the label pass left behind, including a line with no colon.
     * A fact already stored skips only that field; the rest of the page is still read.
     */
    private static LocalReading appendMissed(Connection connection, long documentVersionId,
            DocTypePack pack, ExtractResult labeled, Set<Integer> pages) throws SQLException {
        List<Label> labels = labels(pack);
        List<FactCandidate> facts = new ArrayList<>(labeled.facts());
        List<Citation> citations = new ArrayList<>(labeled.citations());
        List<BlockLine> blocks = new ArrayList<>();
        for (BlockLine line : loadBlocks(connection, documentVersionId)) {
            if (pages == null || pages.contains(line.page())) {
                blocks.add(line);
            }
        }
        joinContinuedSentences(blocks, facts, citations);
        storeUnexplainedFigures(blocks, facts, citations);
        Set<String> consumed = quotesOf(facts);
        Set<String> repeated = new HashSet<>();
        for (BlockLine line : blocks) {
            if (consumed.contains(line.text()) || line.text().indexOf(':') >= 0) {
                continue;
            }
            if (readUnlabeled(line.text(), line.page(), labels, facts, citations, repeated)) {
                consumed.add(line.text());
            }
        }
        if (!repeated.isEmpty()) {
            List<FactCandidate> dropped = new ArrayList<>();
            facts.removeIf(fact -> {
                if (repeated.contains(fact.key())) {
                    dropped.add(fact);
                    return true;
                }
                return false;
            });
            for (FactCandidate fact : dropped) {
                citations.removeAll(fact.citations());
            }
        }
        List<TableSpec> tables = tables(pack);
        List<Grid> grids = new ArrayList<>();
        for (Grid grid : loadGrids(connection, documentVersionId)) {
            if (pages == null || pages.contains(grid.page())) {
                grids.add(grid);
            }
        }
        Set<Grid> readGrids = new HashSet<>();
        CarriedTable carried = null;
        for (Grid grid : grids) {
            Header header = headerOf(grid, tables, pack);
            if (header != null) {
                if (!header.strict()) {
                    for (int i = header.headerAt() + 1; i < grid.cells().size(); i++) {
                        expandRow(header.table(), header.columns(), grid.cells().get(i),
                                pack, facts, citations);
                    }
                }
                readGrids.add(grid);
                carried = new CarriedTable(header.table(), header.columns(), grid.page(),
                        width(grid));
                continue;
            }
            if (carried != null && grid.page() == carried.page() + 1
                    && width(grid) == carried.width()) {
                for (List<Cell> row : grid.cells()) {
                    expandRow(carried.table(), carried.columns(), row, pack, facts, citations);
                }
                readGrids.add(grid);
                carried = new CarriedTable(carried.table(), carried.columns(), grid.page(),
                        carried.width());
                continue;
            }
            carried = null;
        }
        List<String> unread = new ArrayList<>();
        Set<String> quotes = quotesOf(facts);
        for (BlockLine line : blocks) {
            if (!quotes.contains(line.text())) {
                unread.add(line.text());
            }
        }
        for (Grid grid : grids) {
            if (readGrids.contains(grid)) {
                continue;
            }
            for (List<Cell> row : grid.cells()) {
                String joined = row.stream().map(Cell::text).filter(text -> !text.isBlank())
                        .reduce((a, b) -> a + " | " + b).orElse("");
                if (!joined.isBlank() && !quotes.contains(joined)) {
                    unread.add(joined);
                }
            }
        }
        return new LocalReading(new ExtractResult(labeled.extractorId(), labeled.payload(),
                facts, citations, labeled.stats()), List.copyOf(unread));
    }

    /** Longest pack label that opens the line; the remainder is the value. */
    private static boolean readUnlabeled(String line, int page, List<Label> labels,
            List<FactCandidate> facts, List<Citation> citations, Set<String> repeated) {
        String folded = norm(line);
        for (Label label : labels) {
            String name = label.norm();
            if (name.length() < 3) {
                continue;
            }
            boolean exact = folded.equals(name);
            boolean prefix = folded.startsWith(name + " ");
            if (!exact && !prefix) {
                continue;
            }
            if (exact) {
                return false;
            }
            String value = remainderAfter(line, name.split(" ").length);
            if (value.isBlank()) {
                return false;
            }
            for (FactCandidate existing : facts) {
                if (existing.key().equals(label.key())) {
                    repeated.add(label.key());
                    return false;
                }
            }
            FactCandidate candidate = new FactCandidate(label.key(), label.kind())
                    .valueText(value)
                    .confidence(0.9)
                    .extractionMethod("deterministic");
            if (page >= 1) {
                Citation citation = Citation.of(page, line);
                candidate.addCitation(citation);
                citations.add(citation);
            }
            facts.add(candidate);
            return true;
        }
        return false;
    }

    private static String remainderAfter(String line, int tokens) {
        int index = 0;
        int seen = 0;
        while (index < line.length() && seen < tokens) {
            while (index < line.length() && !Character.isLetterOrDigit(line.charAt(index))) {
                index++;
            }
            if (index >= line.length()) {
                break;
            }
            while (index < line.length() && Character.isLetterOrDigit(line.charAt(index))) {
                index++;
            }
            seen++;
        }
        return line.substring(Math.min(index, line.length())).trim();
    }

    /**
     * The column heading on this grid, strict pack names first. A heading the
     * column keys missed is still a heading when the prompt names it.
     */
    private static Header headerOf(Grid grid, List<TableSpec> tables, DocTypePack pack) {
        List<List<Cell>> rows = grid.cells();
        for (int i = 0; i < rows.size(); i++) {
            for (TableSpec table : tables) {
                Map<Integer, String> mapped = mapHeader(rows.get(i), table);
                if (mapped != null) {
                    return new Header(table, mapped, i, true);
                }
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            for (TableSpec table : tables) {
                Map<Integer, String> mapped = mapHeaderLoose(rows.get(i), table, pack);
                if (mapped != null) {
                    return new Header(table, mapped, i, false);
                }
            }
        }
        return null;
    }

    private static int width(Grid grid) {
        int width = 0;
        for (List<Cell> row : grid.cells()) {
            width = Math.max(width, row.size());
        }
        return width;
    }

    private static Map<Integer, String> mapHeaderLoose(List<Cell> row, TableSpec table,
            DocTypePack pack) {
        Map<String, String> hints = columnHints(pack, table.name());
        Map<Integer, String> mapped = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        boolean anchor = false;
        String anchorColumn = table.keyColumn() != null ? table.keyColumn() : table.periodColumn();
        for (int i = 0; i < row.size(); i++) {
            String column = columnForHeader(row.get(i).text(), table.columns(), hints, used);
            if (column == null) {
                continue;
            }
            used.add(column);
            mapped.put(i, column);
            if (column.equals(anchorColumn)) {
                anchor = true;
            }
        }
        if (anchorColumn == null || !anchor
                || mapped.size() < Math.min(2, table.columns().size())) {
            return null;
        }
        return mapped;
    }

    private static String columnForHeader(String header, List<String> columns,
            Map<String, String> hints, Set<String> used) {
        String folded = norm(header);
        if (folded.isEmpty()) {
            return null;
        }
        for (String column : columns) {
            if (used.contains(column)) {
                continue;
            }
            String key = column.replace('_', ' ');
            if (folded.equals(key) || (key.length() >= 3 && folded.startsWith(key))) {
                return column;
            }
            String hint = hints.get(column);
            if (hint == null) {
                continue;
            }
            String hintNorm = norm(hint);
            if (hintNorm.equals(folded) || (folded.length() >= 4 && hintNorm.startsWith(folded + " "))) {
                return column;
            }
            for (String token : hintNorm.split(" ")) {
                if (token.length() >= 5 && token.equals(folded)) {
                    return column;
                }
            }
        }
        return null;
    }

    private static Map<String, String> columnHints(DocTypePack pack, String tableName) {
        Map<String, String> hints = new LinkedHashMap<>();
        for (Map<String, Object> table : pack.tables()) {
            if (!tableName.equals(String.valueOf(table.get("name")))) {
                continue;
            }
            Object fields = table.get("fields");
            if (!(fields instanceof List<?> list)) {
                continue;
            }
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> column && column.get("key") != null
                        && column.get("prompt_hint") != null) {
                    hints.put(String.valueOf(column.get("key")),
                            String.valueOf(column.get("prompt_hint")));
                }
            }
        }
        return hints;
    }

    /**
     * A figure at the top of a page keeps the meaning of the sentence it continues,
     * including words on the previous page, for as long as that sentence goes on.
     * One fact cites each file page the sentence was read from.
     */
    private static void joinContinuedSentences(List<BlockLine> blocks,
            List<FactCandidate> facts, List<Citation> citations) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockLine figure = blocks.get(i);
            if (!isFigure(figure.text()) || !startsPage(blocks, i)) {
                continue;
            }
            int start = unfinishedStart(blocks, i);
            if (start < 0) {
                continue;
            }
            String meaning = meaningOf(blocks, start, i);
            if (meaning.isBlank()) {
                continue;
            }
            int end = sentenceEnd(blocks, i);
            FactCandidate candidate = new FactCandidate(meaning, ValueKind.TEXT)
                    .valueText(figure.text())
                    .confidence(0.9)
                    .extractionMethod("deterministic");
            for (int j = start; j <= end; j++) {
                BlockLine part = blocks.get(j);
                Citation citation = Citation.of(part.page(), part.text());
                candidate.addCitation(citation);
                citations.add(citation);
            }
            facts.add(candidate);
        }
    }

    /**
     * A figure whose surrounding pages never say what it is is stored as that
     * number and its page. Nothing is invented for it, including a label such
     * as a sanctioned limit.
     */
    private static void storeUnexplainedFigures(List<BlockLine> blocks,
            List<FactCandidate> facts, List<Citation> citations) {
        Set<String> quoted = quotesOf(facts);
        for (BlockLine line : blocks) {
            if (!isFigure(line.text()) || quoted.contains(line.text())) {
                continue;
            }
            String number = figureNumber(line.text());
            if (number.isBlank()) {
                continue;
            }
            FactCandidate candidate = new FactCandidate(number, ValueKind.TEXT)
                    .valueText(number)
                    .confidence(0.9)
                    .extractionMethod("deterministic");
            Citation citation = Citation.of(line.page(), line.text());
            candidate.addCitation(citation);
            citations.add(citation);
            facts.add(candidate);
            quoted.add(line.text());
        }
    }

    private static String figureNumber(String text) {
        Matcher matcher = FIGURE_NUMBER.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /** First text line on its page: a figure lower down does not continue the previous page. */
    private static boolean startsPage(List<BlockLine> blocks, int index) {
        return index == 0 || blocks.get(index - 1).page() != blocks.get(index).page();
    }

    /**
     * Lines of the unfinished sentence before the figure. {@code -1} when the
     * previous line already ended a sentence, so the figure has no continued meaning.
     */
    private static int unfinishedStart(List<BlockLine> blocks, int figureAt) {
        if (figureAt == 0 || endsSentence(blocks.get(figureAt - 1).text())) {
            return -1;
        }
        int start = figureAt - 1;
        while (start > 0 && !endsSentence(blocks.get(start - 1).text())) {
            start--;
        }
        return start;
    }

    /** Last line of the sentence that opened before the figure. */
    private static int sentenceEnd(List<BlockLine> blocks, int figureAt) {
        int end = figureAt;
        while (end + 1 < blocks.size() && !endsSentence(blocks.get(end).text())) {
            end++;
        }
        return end;
    }

    private static String meaningOf(List<BlockLine> blocks, int start, int figureAt) {
        StringBuilder words = new StringBuilder();
        for (int j = start; j < figureAt; j++) {
            if (words.length() > 0) {
                words.append(' ');
            }
            words.append(blocks.get(j).text());
        }
        return TRAILING_COPULA.matcher(words.toString()).replaceFirst("").trim();
    }

    private static boolean isFigure(String text) {
        return text != null && FIGURE.matcher(text).matches();
    }

    private static boolean endsSentence(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?");
    }

    private static Set<String> quotesOf(List<FactCandidate> facts) {
        Set<String> quotes = new HashSet<>();
        for (FactCandidate fact : facts) {
            for (Citation citation : fact.citations()) {
                if (citation.verbatim() != null && !citation.verbatim().isBlank()) {
                    quotes.add(citation.verbatim().trim());
                }
            }
        }
        return quotes;
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
            Object period = table.get("period_key");
            if (name == null || (key == null && period == null)) {
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
                tables.add(new TableSpec(String.valueOf(name), columns,
                        key == null ? null : String.valueOf(key),
                        period == null ? null : String.valueOf(period)));
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

    /** A header or footer that is only a page number is not a figure and not a sentence. */
    private static boolean barePageNumber(String kind, String text) {
        if (kind == null || text == null) {
            return false;
        }
        String folded = kind.toLowerCase(Locale.ROOT);
        if (!folded.equals("header") && !folded.equals("footer")) {
            return false;
        }
        return text.trim().matches("\\d+");
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
                    if ("table".equalsIgnoreCase(kind)) {
                        continue;
                    }
                    String text = rs.getString(3);
                    if (text == null || text.isBlank() || barePageNumber(kind, text)) {
                        continue;
                    }
                    lines.add(new BlockLine(rs.getInt(1), text.trim()));
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

    private record TableSpec(String name, List<String> columns, String keyColumn,
            String periodColumn) {}

    private record BlockLine(int page, String text) {}

    /** Column heading carried forward while the same table continues on the next page. */
    private record CarriedTable(TableSpec table, Map<Integer, String> columns, int page, int width) {}

    /** A heading row: {@code strict} means the label pass already stored its rows. */
    private record Header(TableSpec table, Map<Integer, String> columns, int headerAt, boolean strict) {}

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
