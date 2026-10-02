package com.resurgent.tev.parser.classify;

/**
 * A settled reading before it is stored. {@code refusal} and {@code kind} are
 * mutually exclusive. An untyped input has both empty. Money whose scale nothing states
 * has no {@code scale} and an unknown in {@link UnstatedScales} instead.
 */
final class ReadingOutcome {

    static final String UNTYPABLE = "untypable";
    static final String KIND_CONFLICT = "kind_conflict";
    static final String CYCLE = "cycle";
    static final String EXTERNAL = "external_dependency";

    static final String MONEY = "money";
    static final String QUANTITY = "quantity";
    static final String RATE = "rate";
    static final String PERCENT = "percent";
    static final String COUNT = "count";
    static final String RATIO = "ratio";

    static final String INPUT = "input";
    static final String DERIVED = "derived";

    final String kind;
    final CellScale scale;
    final String unit;
    final String currency;
    final String refusal;
    final String typeSource;
    /** The {@link UnstatedScales} unknown standing in for {@code scale}, or -1 when the scale is known. */
    final int scaleUnknown;
    /** The scale came from the sheets that read this money, not from anything this cell or its sheet says. */
    final boolean scaleInferred;

    private ReadingOutcome(
            String kind,
            CellScale scale,
            String unit,
            String currency,
            String refusal,
            String typeSource,
            int scaleUnknown,
            boolean scaleInferred) {
        this.kind = kind;
        this.scale = scale;
        this.unit = unit == null ? "" : unit;
        this.currency = currency == null ? "" : currency;
        this.refusal = refusal;
        this.typeSource = typeSource;
        this.scaleUnknown = scaleUnknown;
        this.scaleInferred = scaleInferred;
    }

    static ReadingOutcome typed(
            String kind, CellScale scale, String unit, String currency, String typeSource) {
        return new ReadingOutcome(
                kind, scale == null ? CellScale.UNIT : scale, unit, currency, null, typeSource, -1, false);
    }

    /** Money whose scale nothing states; {@code unknown} is its {@link UnstatedScales} unknown. */
    static ReadingOutcome unstated(String unit, String currency, String typeSource, int unknown) {
        return new ReadingOutcome(MONEY, null, unit, currency, null, typeSource, unknown, false);
    }

    static ReadingOutcome refused(String refusal) {
        return new ReadingOutcome(null, null, "", "", refusal, null, -1, false);
    }

    static ReadingOutcome untyped() {
        return new ReadingOutcome(null, null, "", "", null, null, -1, false);
    }

    /** This reading with its unknown scale settled: stated by the cell's own sheet, or inferred. */
    ReadingOutcome withScale(CellScale known, boolean inferred) {
        return new ReadingOutcome(kind, known, unit, currency, null, typeSource, -1, inferred);
    }

    /** The same reading, attributed to {@code source}; an unknown scale stays unknown. */
    ReadingOutcome as(String source) {
        return new ReadingOutcome(kind, scale, unit, currency, refusal, source, scaleUnknown, scaleInferred);
    }

    boolean scaleUnstated() {
        return scaleUnknown >= 0;
    }

    boolean typed() {
        return kind != null && refusal == null;
    }
}
