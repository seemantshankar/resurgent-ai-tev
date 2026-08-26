package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Derives header facts from cells that belong to a single detected region.
 *
 * <p>Period-label matching is shared with region cutting so a column-header row
 * means the same thing in both places. Persistence still consumes this analyzer
 * only after detection.
 */
final class RegionHeaderAnalyzer {

    private static final Pattern PERIOD = Pattern.compile(
            "(?i)^(?:year\\s*\\d+|yr\\s*\\d+|fy\\s*'?\\d{2,4}(?:\\s*[-/]\\s*'?\\d{2,4})?|"
                    + "q[1-4]|quarter\\s*[1-4]|jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|"
                    + "jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|"
                    + "dec(?:ember)?|(?:19|20)\\d{2}(?:\\s*[-/]\\s*'?\\d{2,4})?|"
                    + "opening(?:\\s+balance)?|closing(?:\\s+balance)?|"
                    + "alternative(?:\\s*\\d+)?|scenario(?:\\s*\\d+)?|projection(?:s)?)$");

    RegionHeaderContext analyze(List<NormalizedCell> cells, Bounds bounds) {
        List<NormalizedCell> regionCells = cells.stream()
                .filter(bounds::contains)
                .sorted(Comparator.comparingInt(NormalizedCell::rowNum)
                        .thenComparingInt(NormalizedCell::colNum))
                .toList();

        Map<Integer, List<NormalizedCell>> cellsByRow = new LinkedHashMap<>();
        for (NormalizedCell cell : regionCells) {
            cellsByRow.computeIfAbsent(cell.rowNum(), ignored -> new ArrayList<>()).add(cell);
        }

        Integer firstDataRow = cellsByRow.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(this::isDataCell))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        List<Integer> headerRows = cellsByRow.entrySet().stream()
                .filter(entry -> isHeaderRow(entry.getKey(), entry.getValue(), firstDataRow))
                .map(Map.Entry::getKey)
                .toList();
        Map<Integer, String> columnLabels = columnLabels(headerRows, cellsByRow, bounds);
        Map<String, Integer> periodAxis = periodAxis(headerRows, cellsByRow, bounds);
        Map<Integer, String> rowLabels = rowLabels(headerRows, cellsByRow);
        return new RegionHeaderContext(headerRows, periodAxis, rowLabels, columnLabels);
    }

    private Map<Integer, String> columnLabels(List<Integer> headerRows,
            Map<Integer, List<NormalizedCell>> cellsByRow, Bounds bounds) {
        Map<Integer, List<String>> labels = new LinkedHashMap<>();
        for (int row : headerRows) {
            for (NormalizedCell cell : distinctMergedCells(cellsByRow.get(row))) {
                String label = headerText(cell);
                if (label != null) {
                    for (int column : coveredColumns(cell, bounds)) {
                        labels.computeIfAbsent(column, ignored -> new ArrayList<>()).add(label);
                    }
                }
            }
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        labels.forEach((column, parts) -> result.put(column, String.join(" / ", parts)));
        return result;
    }

    private Map<String, Integer> periodAxis(List<Integer> headerRows,
            Map<Integer, List<NormalizedCell>> cellsByRow, Bounds bounds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int row : headerRows) {
            for (NormalizedCell cell : distinctMergedCells(cellsByRow.get(row))) {
                if (isPeriodHeader(cell)) {
                    for (int column : coveredColumns(cell, bounds)) {
                        result.putIfAbsent(columnName(column), result.size() + 1);
                    }
                }
            }
        }
        return result;
    }

    private Map<Integer, String> rowLabels(List<Integer> headerRows,
            Map<Integer, List<NormalizedCell>> cellsByRow) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<NormalizedCell>> entry : cellsByRow.entrySet()) {
            if (headerRows.contains(entry.getKey())) {
                continue;
            }
            String label = RowLabelComposer.compose(entry.getValue());
            if (label != null) {
                result.put(entry.getKey(), label);
            }
        }
        return result;
    }

    static boolean isPeriodLikeLabel(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return PERIOD.matcher(trimmed).matches() || trimmed.equalsIgnoreCase("construction");
    }

    /** Period axis labels that identify a column-header row, not opening/closing stubs. */
    static boolean isColumnHeaderPeriodLabel(String value) {
        if (!isPeriodLikeLabel(value)) {
            return false;
        }
        return !value.trim().matches("(?i)opening(?:\\s+balance)?|closing(?:\\s+balance)?");
    }

    private boolean isPeriodHeader(NormalizedCell cell) {
        String value = headerText(cell);
        return value != null && isPeriodLikeLabel(value);
    }

    private boolean isHeaderRow(int row, List<NormalizedCell> cells, Integer firstDataRow) {
        if (cells.stream().anyMatch(this::isPeriodHeader)) {
            return true;
        }
        if (cells.stream().anyMatch(this::isDataCell)) {
            return false;
        }
        if (cells.stream().map(this::headerText).noneMatch(value -> value != null)) {
            return false;
        }
        // Text-only rows above the first amount or formula are headers, including
        // stacked unit lines such as Amount / Rs. Lakh. Line items and totals sit
        // on or after that first data row and must not join into col_label.
        return firstDataRow != null && row < firstDataRow;
    }

    private boolean isDataCell(NormalizedCell cell) {
        return cell.numericValue() != null || (cell.formulaText() != null && !cell.formulaText().isBlank());
    }

    private String headerText(NormalizedCell cell) {
        if (!"text".equals(cell.valueType())) {
            return null;
        }
        return text(cell);
    }

    private String text(NormalizedCell cell) {
        if (cell.displayValue() == null) {
            return null;
        }
        String value = cell.displayValue().trim().replaceAll("\\s+", " ");
        return value.isEmpty() ? null : value;
    }

    /** Project a merged header's one label across its columns without duplicating participants. */
    private static List<NormalizedCell> distinctMergedCells(List<NormalizedCell> cells) {
        Map<String, NormalizedCell> byMerge = new LinkedHashMap<>();
        for (NormalizedCell cell : cells) {
            String key = cell.mergedRange() == null ? "cell:" + cell.coord() : "merge:" + cell.mergedRange();
            NormalizedCell existing = byMerge.get(key);
            if (existing == null || (cell.isMergedAnchor() && !existing.isMergedAnchor())) {
                byMerge.put(key, cell);
            }
        }
        return List.copyOf(byMerge.values());
    }

    private static List<Integer> coveredColumns(NormalizedCell cell, Bounds bounds) {
        if (cell.mergedRange() == null) {
            return bounds.startCol <= cell.colNum() && cell.colNum() <= bounds.endCol
                    ? List.of(cell.colNum()) : List.of();
        }
        String[] endpoints = cell.mergedRange().split(":", -1);
        if (endpoints.length != 2) {
            return List.of(cell.colNum());
        }
        int start = columnFromCoordinate(endpoints[0]);
        int end = columnFromCoordinate(endpoints[1]);
        if (start < 1 || end < start) {
            return List.of(cell.colNum());
        }
        start = Math.max(start, bounds.startCol);
        end = Math.min(end, bounds.endCol);
        List<Integer> columns = new ArrayList<>(Math.max(0, end - start + 1));
        for (int column = start; column <= end; column++) {
            columns.add(column);
        }
        return columns;
    }

    private static int columnFromCoordinate(String coordinate) {
        int result = 0;
        for (int index = 0; index < coordinate.length(); index++) {
            char character = coordinate.charAt(index);
            if (!Character.isLetter(character)) {
                break;
            }
            result = result * 26 + Character.toUpperCase(character) - 'A' + 1;
        }
        return result;
    }

    private static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        for (int value = column; value > 0; value = (value - 1) / 26) {
            name.append((char) ('A' + (value - 1) % 26));
        }
        return name.reverse().toString();
    }

    record Bounds(int startRow, int endRow, int startCol, int endCol) {
        Bounds {
            if (startRow < 1 || startCol < 1 || endRow < startRow || endCol < startCol) {
                throw new IllegalArgumentException("Invalid region bounds");
            }
        }

        boolean contains(NormalizedCell cell) {
            return cell.rowNum() >= startRow && cell.rowNum() <= endRow
                    && cell.colNum() >= startCol && cell.colNum() <= endCol;
        }
    }
}
