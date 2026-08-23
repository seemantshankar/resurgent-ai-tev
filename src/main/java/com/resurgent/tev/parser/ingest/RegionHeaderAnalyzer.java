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
 * <p>This is deliberately independent of persistence and region detection. It gives their
 * integration point one deterministic source for header rows, period ordering, and the labels
 * which can be denormalized onto cells later in the ingestion pipeline.
 */
final class RegionHeaderAnalyzer {

    private static final Pattern PERIOD = Pattern.compile(
            "(?i)^(?:year\\s*\\d+|yr\\s*\\d+|fy\\s*'?\\d{2,4}(?:\\s*[-/]\\s*'?\\d{2,4})?|"
                    + "q[1-4]|quarter\\s*[1-4]|jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|"
                    + "jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|"
                    + "dec(?:ember)?|(?:19|20)\\d{2}(?:\\s*[-/]\\s*'?\\d{2,4})?)$");

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

        List<Integer> headerRows = cellsByRow.entrySet().stream()
                .filter(entry -> isHeaderRow(entry.getKey(), entry.getValue(), cellsByRow))
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
                String label = text(cell);
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
            entry.getValue().stream()
                    .map(this::text)
                    .filter(value -> value != null)
                    .findFirst()
                    .ifPresent(label -> result.put(entry.getKey(), label));
        }
        return result;
    }

    private boolean isPeriodHeader(NormalizedCell cell) {
        String value = text(cell);
        return value != null && PERIOD.matcher(value).matches();
    }

    private boolean isHeaderRow(int row, List<NormalizedCell> cells,
            Map<Integer, List<NormalizedCell>> cellsByRow) {
        if (cells.stream().anyMatch(this::isPeriodHeader)) {
            return true;
        }
        long textCells = cells.stream().map(this::text).filter(value -> value != null).count();
        if (textCells < 2) {
            return false;
        }
        // An ordinary table header is a text-led row immediately followed by table data. This
        // intentionally does not depend on a period label: cost and vendor tables often have none.
        return cellsByRow.entrySet().stream()
                .filter(entry -> entry.getKey() > row)
                .findFirst()
                .map(entry -> entry.getValue().stream().anyMatch(this::isDataCell))
                .orElse(false);
    }

    private boolean isDataCell(NormalizedCell cell) {
        return cell.numericValue() != null || (cell.formulaText() != null && !cell.formulaText().isBlank());
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
