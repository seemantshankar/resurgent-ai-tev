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
 * Layout features of vertical key-value blocks. Sheet names are never consulted.
 *
 * <p>Swallowing of a neighbour block is not a header-membership bug. Occupied-cell
 * skip-1 used to treat {@code number | blank | label} as stub-to-value. That glued a
 * schema grid's amount column to a KPI stub one spacer to its right. Stub-to-value is
 * therefore directional: on a row the stub is left of the value; on a column, above it.
 *
 * <p>Features any later sheet can share with CAPITAL COST:
 * <ul>
 *   <li>a schema grid with a distinct column-header row (membership is column-bounded)
 *   <li>unlabeled stub/value columns with no header and no {@code 1)} serials
 *   <li>stacked unlabeled forms, each introduced by a title after a valued body
 *   <li>a title or preamble immediately above its body or grid, never across a grid
 * </ul>
 *
 * <p>Skip-1 already joins across one empty cell, so fragments that survive geometry are
 * at least two empty rows apart. Item spacers inside one unlabeled form are two or three
 * empty rows. A larger empty band, or a title after a body, starts a new form.
 */
final class VerticalFormLayout {
    private static final Pattern NUMBERED_STUB = Pattern.compile("^\\d+[).]");
    private static final int MIN_NUMBERED_STUBS = 3;
    private static final int MIN_STUB_VALUE_ROWS = 3;

    /**
     * Empty rows that still belong to one unlabeled form. Larger than skip-1 (one empty
     * cell) and smaller than the band that separates stacked forms.
     */
    static final int FORM_INTERNAL_MAX_EMPTY_ROWS = 3;

    static boolean startsNewUnlabeledForm(boolean titleAfterBody, int emptyRowsBetween) {
        return titleAfterBody || emptyRowsBetween > FORM_INTERNAL_MAX_EMPTY_ROWS;
    }

    static boolean withinFormInternalGap(int emptyRowsBetween) {
        return emptyRowsBetween <= FORM_INTERNAL_MAX_EMPTY_ROWS;
    }

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

    /** Section heading: text only. Multi-column merged titles still qualify. */
    static boolean isTitleFragment(List<Cell> cells) {
        return !cells.isEmpty() && cells.stream().noneMatch(cell -> cell.numeric() || cell.formula());
    }

    static boolean isBodyFragment(List<Cell> cells) {
        return cells.stream().anyMatch(cell -> cell.numeric() || cell.formula());
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

    static boolean isColumnHeaderRow(List<Cell> row) {
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
