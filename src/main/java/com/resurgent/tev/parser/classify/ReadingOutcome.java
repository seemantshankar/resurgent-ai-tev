package com.resurgent.tev.parser.classify;

/**
 * A settled reading before it is stored. {@code refusal} and {@code kind} are
 * mutually exclusive. An untyped input has both empty.
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

    private ReadingOutcome(
            String kind,
            CellScale scale,
            String unit,
            String currency,
            String refusal,
            String typeSource) {
        this.kind = kind;
        this.scale = scale;
        this.unit = unit == null ? "" : unit;
        this.currency = currency == null ? "" : currency;
        this.refusal = refusal;
        this.typeSource = typeSource;
    }

    static ReadingOutcome typed(
            String kind, CellScale scale, String unit, String currency, String typeSource) {
        return new ReadingOutcome(kind, scale == null ? CellScale.UNIT : scale, unit, currency, null, typeSource);
    }

    static ReadingOutcome refused(String refusal) {
        return new ReadingOutcome(null, null, "", "", refusal, null);
    }

    static ReadingOutcome untyped() {
        return new ReadingOutcome(null, null, "", "", null, null);
    }

    boolean typed() {
        return kind != null && refusal == null;
    }
}
