package com.resurgent.tev.parser.db;

/**
 * What one numeric cell is for a parse run: kind, scale, unit, currency, and,
 * for money, the absolute amount. A refusal leaves kind empty. An untyped
 * number states nothing, so kind and refusal are both empty.
 */
public record CellReading(
        long parseRunId,
        long cellId,
        String kind,
        String scale,
        String unit,
        String currency,
        String absoluteAmount,
        String typeSource,
        String refusal) {
}
