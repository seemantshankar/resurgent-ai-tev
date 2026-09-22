package com.resurgent.tev.parser.classify;

/**
 * How a cell's scale was established. This is what decides whether a scale is a
 * claim in its own right or merely a carrier value, and therefore whether another
 * cell may adopt it.
 *
 * <p>{@code STATED} — the cell's own evidence named a scale (a formula divisor such
 * as {@code /10^5}, a scale word such as {@code Lacs}, or a currency cue such as
 * {@code Rs.}), or dimensional arithmetic carried a stated scale here from an
 * operand that named one. Only a stated scale is a source another cell may adopt:
 * the fixpoint guard, so no chain of unstated cells can invent a scale between them.
 *
 * <p>{@code ADOPTED} — nothing in this cell's own evidence named a scale, so it took
 * the one scale an additive consumer stated. This is sound for {@code +} and
 * {@code -}: every member of an additive group is expressed at the group's scale, so
 * {@code K54 = I54 - J54} proves J54 is at I54's scale. It is not sound across
 * {@code *} or {@code /}, which is why adoption is restricted to summands:
 * {@code Details!F161 = SUM(F159:F160)} with {@code F159 = D159*E159} — a quantity
 * times a rate — would otherwise push a money scale onto a count or a rate. An
 * adopted scale is a claim for output, but never a source for a further adoption.
 *
 * <p>{@code UNSTATED} — no cue named a scale and no additive consumer supplied one.
 * The unit default is then a carrier value, not a claim: a binding over it reports
 * that its scale is unstated rather than passing a confident rupees figure off as
 * meant, which is the failure the bare default used to cause.
 */
public enum ScaleProvenance {
    STATED,
    ADOPTED,
    UNSTATED;

    /** The stable token persisted in {@code cell_type.scale_provenance}. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ScaleProvenance fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        String trimmed = wire.trim();
        for (ScaleProvenance provenance : values()) {
            if (provenance.wireName().equalsIgnoreCase(trimmed)) {
                return provenance;
            }
        }
        return null;
    }

    /** True when the carried scale is a claim rather than an unearned default. */
    public boolean isClaimed() {
        return this != UNSTATED;
    }

    /** The stronger of two provenances, for combining operands in one expression. */
    public static ScaleProvenance strongest(ScaleProvenance left, ScaleProvenance right) {
        if (left == STATED || right == STATED) {
            return STATED;
        }
        if (left == ADOPTED || right == ADOPTED) {
            return ADOPTED;
        }
        return UNSTATED;
    }
}
