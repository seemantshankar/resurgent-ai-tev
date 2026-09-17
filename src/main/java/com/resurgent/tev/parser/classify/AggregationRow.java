package com.resurgent.tev.parser.classify;

/**
 * A persisted aggregation: the head cell whose formula sums or nets its members,
 * plus the unit the whole group resolved to. {@code relativeSignature} is the
 * R1C1-relative formula shape, which is what collapses a repeated row-series into
 * one group instead of one per period column.
 */
public record AggregationRow(
        Long aggregationId,
        long parseRunId,
        long headCellId,
        long worksheetId,
        String relativeSignature,
        String headLabel,
        CellKind resolvedKind,
        CellScale resolvedScale) {}
