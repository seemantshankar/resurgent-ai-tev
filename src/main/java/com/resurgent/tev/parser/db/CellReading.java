package com.resurgent.tev.parser.db;

/**
 * What one numeric cell is for a parse run: kind, scale, unit, currency, and,
 * for money, the absolute amount. A refusal leaves kind empty. An untyped
 * number states nothing, so kind and refusal are both empty. Money whose scale
 * nothing states, and that no stated sheet reads, has no scale and no absolute
 * amount. {@code scaleBasis} says where a money scale came from.
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
        String refusal,
        String scaleBasis) {

    /** The cell's labels, formula, region or sheet say the scale, or it follows from cells that do. */
    public static final String SCALE_STATED = "stated";
    /** Nothing about the cell says; the stated sheets that read it agree on the scale. */
    public static final String SCALE_INFERRED = "inferred";
}
