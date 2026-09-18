package com.resurgent.tev.parser.classify;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Types one hardcoded cell from its own row label and displayed text. These are the
 * only cells that need judging — roughly 600 decisions on the working FM rather
 * than 8,791 — because everything else is derived and the formulas carry the type
 * forward.
 */
final class InputTyping {

    private static final Pattern SCALE_TOKEN = Pattern.compile(
            "(?i)\\b(lakhs?|lacs?|crores?|millions?|billions?|thousands?|000s?)\\b");

    private InputTyping() {}

    static ResolvedUnit of(GraphCell cell) {
        if (cell.rowLabel() == null || cell.rowLabel().isBlank()) {
            NumericKind fromFormat = applyFormat(NumericKind.UNKNOWN, cell.numberFormat());
            CellKind formatted = CellKind.from(fromFormat);
            if (formatted == null) {
                return ResolvedUnit.unresolved();
            }
            return ResolvedUnit.of(formatted, scaleOf(cell));
        }
        NumericKind kind = LayerBAmountSupport.classifyKind(
                cell.rowLabel(), null, cell.displayValue(), true);
        kind = applyFormat(kind, cell.numberFormat());
        CellKind cellKind = CellKind.from(kind);
        if (cellKind == null) {
            return ResolvedUnit.unresolved();
        }
        return ResolvedUnit.of(cellKind, scaleOf(cell));
    }

    /** Scale named in the row label; the cell's own figure never implies one. */
    static CellScale scaleOf(GraphCell cell) {
        CellScale fromLabel = scaleIn(cell.rowLabel());
        return fromLabel == null ? CellScale.UNIT : fromLabel;
    }

    static CellScale scaleIn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = SCALE_TOKEN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group().toLowerCase(Locale.ROOT);
        if (token.startsWith("lakh") || token.startsWith("lac")) {
            return CellScale.LAKH;
        }
        if (token.startsWith("crore")) {
            return CellScale.CRORE;
        }
        if (token.startsWith("million")) {
            return CellScale.MILLION;
        }
        if (token.startsWith("billion")) {
            return CellScale.BILLION;
        }
        return CellScale.THOUSAND;
    }

    /**
     * Number format is unambiguous for percent and for an explicit currency mask.
     * A labelled kind still wins over a generic {@code General} / {@code 0.00} mask.
     */
    static NumericKind applyFormat(NumericKind kind, String numberFormat) {
        if (numberFormat == null || numberFormat.isBlank()) {
            return kind;
        }
        String format = numberFormat.toLowerCase(Locale.ROOT);
        if (format.contains("%")) {
            return NumericKind.PERCENT;
        }
        if (kind != NumericKind.UNKNOWN && kind != null) {
            return kind;
        }
        if (format.contains("₹")
                || format.contains("$")
                || format.contains("rs")
                || format.contains("inr")) {
            return NumericKind.MONEY;
        }
        return kind;
    }
}
