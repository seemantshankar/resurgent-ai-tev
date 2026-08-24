package com.resurgent.tev.parser.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Header-derived facts for one detected region. The period axis uses spreadsheet column names so
 * it has the same stable representation as the persisted {@code period_axis} JSON object.
 */
record RegionHeaderContext(
        List<Integer> headerRows,
        Map<String, Integer> periodAxisByColumn,
        Map<Integer, String> rowLabelsByRow,
        Map<Integer, String> columnLabelsByColumn) {

    RegionHeaderContext {
        headerRows = List.copyOf(headerRows);
        periodAxisByColumn = immutableOrderedCopy(periodAxisByColumn);
        rowLabelsByRow = immutableOrderedCopy(rowLabelsByRow);
        columnLabelsByColumn = immutableOrderedCopy(columnLabelsByColumn);
    }

    private static <K, V> Map<K, V> immutableOrderedCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
