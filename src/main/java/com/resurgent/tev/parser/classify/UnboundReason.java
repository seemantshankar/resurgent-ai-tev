package com.resurgent.tev.parser.classify;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Why a numeric cell carries no nomenclature binding. Coverage comes from what can
 * be proven; everything else is unbound with a reason rather than guessed at. Not a
 * database CHECK — the set is asserted by a unit test instead, so adding a reason
 * needs no migration.
 */
public enum UnboundReason {
    /** No usable input in this cell's dependency chain ever typed. */
    UNTYPABLE,
    /** The chain runs through a link to another workbook. */
    EXTERNAL_DEPENDENCY,
    /** The chain runs through a #REF! or an otherwise unresolvable reference. */
    BROKEN_DEPENDENCY,
    /** A SUM range exceeds the cell-scan cap, so its evidence is incomplete. */
    RANGE_TRUNCATED,
    /** Nothing to the left on the row names this cell. */
    NO_LABEL,
    /** The label names more than one catalog path. */
    AMBIGUOUS_LABEL,
    /** Two typed inputs disagree on kind. */
    KIND_CONFLICT,
    /** Two members of one aggregation disagree on scale. */
    SCALE_CONFLICT,
    /** The aggregation this cell belongs to is not money, so it takes no cost role. */
    NON_MONEY_GROUP,
    /** The cell is only an operand of * or /, so it is a driver and never a cost. */
    DRIVER_ONLY,
    /** The dependency chain revisits this cell. */
    CYCLE,
    /** The model answered but named nothing usable. */
    LLM_DECLINED,
    /** The model was never reached for this group. */
    LLM_UNAVAILABLE,
    /**
     * The proposed leaf is the row's own text (a supplier, rate, quantity, spec, or
     * formula error) rather than a category. The text stays on the row as evidence;
     * the amount stays unbound until a real category is known.
     */
    TRANSCRIBED_LABEL;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static UnboundReason fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static Set<String> wireNames() {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(values()).map(UnboundReason::wireName).forEach(names::add);
        return names;
    }
}
