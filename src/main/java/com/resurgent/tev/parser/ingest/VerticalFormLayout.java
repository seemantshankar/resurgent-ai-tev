package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Layout signature of a vertical key-value form: numbered stubs, a spacer column,
 * values, optional amounts, and no column-header row. Sheet names are not consulted.
 */
final class VerticalFormLayout {
    private static final Pattern NUMBERED_STUB = Pattern.compile("^\\d+[).]");
    private static final int MIN_NUMBERED_STUBS = 3;
    private static final int MIN_STUB_VALUE_ROWS = 3;

    record Cell(int row, int col, String text, boolean numeric, boolean formula) {
        boolean colon() {
            return text != null && text.strip().equals(":");
        }

        boolean numberedStub() {
            return text != null && NUMBERED_STUB.matcher(text.strip()).lookingAt();
        }

        boolean stubText() {
            return text != null && !text.isBlank() && !colon() && !numeric && !formula;
        }

        boolean valueLike() {
            return !colon() && (numeric || formula || (text != null && !text.isBlank()));
        }
    }

    static boolean isNumberedKeyValueForm(List<Cell> cells) {
        if (cells.isEmpty() || hasColumnHeaderRow(cells)) {
            return false;
        }
        Map<Integer, Integer> numberedStubCols = new HashMap<>();
        int stubValueRows = 0;
        for (RowStats stats : rowStats(cells)) {
            if (stats.numberedStub) {
                numberedStubCols.merge(stats.stubCol, 1, Integer::sum);
            }
            if (stats.stubValue) {
                stubValueRows++;
            }
        }
        int numbered = numberedStubCols.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return numbered >= MIN_NUMBERED_STUBS && stubValueRows >= MIN_STUB_VALUE_ROWS;
    }

    static boolean isStubValueForm(List<Cell> cells) {
        if (cells.isEmpty() || hasColumnHeaderRow(cells)) {
            return false;
        }
        return stubValueRowCount(cells) >= MIN_STUB_VALUE_ROWS;
    }

    static boolean isTitleFragment(List<Cell> cells) {
        if (cells.isEmpty() || cells.stream().anyMatch(cell -> cell.numeric() || cell.formula())) {
            return false;
        }
        return stubValueRowCount(cells) == 0;
    }

    static boolean isBodyFragment(List<Cell> cells) {
        return stubValueRowCount(cells) >= 1
                || cells.stream().anyMatch(cell -> cell.numeric() || cell.formula());
    }

    private static int stubValueRowCount(List<Cell> cells) {
        int count = 0;
        for (RowStats stats : rowStats(cells)) {
            if (stats.stubValue) {
                count++;
            }
        }
        return count;
    }

    private record RowStats(int stubCol, boolean numberedStub, boolean stubValue) {}

    private static List<RowStats> rowStats(List<Cell> cells) {
        List<RowStats> result = new ArrayList<>();
        for (List<Cell> row : byRow(cells).values()) {
            List<Cell> occupied = new ArrayList<>(row);
            occupied.sort(Comparator.comparingInt(Cell::col));
            Cell stub = leftmostNonColon(occupied);
            if (stub == null || !stub.stubText()) {
                continue;
            }
            result.add(new RowStats(
                    stub.col(),
                    stub.numberedStub(),
                    firstValueRightOf(occupied, stub.col() + 2) != null));
        }
        return result;
    }

    static boolean hasColumnHeaderRow(List<Cell> cells) {
        for (List<Cell> row : byRow(cells).values()) {
            if (isColumnHeaderRow(row)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isColumnHeaderRow(List<Cell> row) {
        if (row.isEmpty() || row.stream().anyMatch(cell -> cell.numeric() || cell.formula())) {
            return false;
        }
        Cell leftmost = leftmostNonColon(row.stream()
                .sorted(Comparator.comparingInt(Cell::col))
                .toList());
        // Numbered stub rows are form data (`1) | NAME | : | value`), not a schema header.
        if (leftmost != null && leftmost.numberedStub()) {
            return false;
        }
        List<String> labels = new ArrayList<>();
        for (Cell cell : row) {
            if (cell.text() != null && !cell.text().isBlank() && !cell.colon()) {
                labels.add(cell.text().strip());
            }
        }
        boolean period = labels.stream().anyMatch(RegionHeaderAnalyzer::isColumnHeaderPeriodLabel);
        // A merged title repeats one label across columns; a schema has distinct headers.
        if (Set.copyOf(labels).size() < 2 && !period) {
            return false;
        }
        return labels.size() >= 3 || period;
    }

    private static Cell leftmostNonColon(List<Cell> occupied) {
        for (Cell cell : occupied) {
            if (!cell.colon()) {
                return cell;
            }
        }
        return null;
    }

    private static Cell firstValueRightOf(List<Cell> occupied, int minCol) {
        for (Cell cell : occupied) {
            if (cell.col() >= minCol && cell.valueLike()) {
                return cell;
            }
        }
        return null;
    }

    private static Map<Integer, List<Cell>> byRow(List<Cell> cells) {
        Map<Integer, List<Cell>> rows = new TreeMap<>();
        for (Cell cell : cells) {
            rows.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell);
        }
        return rows;
    }

    private VerticalFormLayout() {}
}
