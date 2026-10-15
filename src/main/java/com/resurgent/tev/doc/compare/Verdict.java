package com.resurgent.tev.doc.compare;

import java.util.Locale;

/**
 * The outcome of comparing two values (issue #135). Closed set, stored by
 * later M3 stages in {@code workspace.db} as {@link #wireName()}.
 *
 * <ul>
 *   <li>{@link #AGREE} — the two values are the same statement (within the
 *       type's tolerance); no conflict.</li>
 *   <li>{@link #DISAGREE} — the two values are comparable and differ.</li>
 *   <li>{@link #DIFFERENT_READINGS} — both are valid statements about
 *       different as-on dates, periods or units of meaning; never a
 *       conflict (ADR 0026 §1).</li>
 *   <li>{@link #PRECISION_MISMATCH} — the two values are consistent but
 *       stated at different precisions (a month and a day within it); the
 *       coarser side must not be read as a disagreement.</li>
 *   <li>{@link #NOT_COMPARABLE} — the comparison refused: unparseable text,
 *       differing stated currencies, or differing / unstated units. Never
 *       record a disagreement from this outcome.</li>
 * </ul>
 */
public enum Verdict {
    AGREE,
    DISAGREE,
    DIFFERENT_READINGS,
    PRECISION_MISMATCH,
    NOT_COMPARABLE;

    /** Lower-case wire name for {@code workspace.db} columns and JSON. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
