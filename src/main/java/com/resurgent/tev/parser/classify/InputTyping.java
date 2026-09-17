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
            // Nothing names this number. Assuming money for a bare cell is exactly
            // how a power factor of 0.8 came to be read as a cost.
            return ResolvedUnit.unresolved();
        }
        NumericKind kind = LayerBAmountSupport.classifyKind(
                cell.rowLabel(), null, cell.displayValue(), true);
        CellKind cellKind = CellKind.from(kind);
        if (cellKind == null) {
            return ResolvedUnit.unresolved();
        }
        return ResolvedUnit.of(cellKind, scaleOf(cell));
    }

    /** Scale named in the row label; the cell's own figure never implies one. */
    static CellScale scaleOf(GraphCell cell) {
        CellScale fromLabel = scaleIn(cell.rowLabel());
        if (fromLabel != null) {
            return fromLabel;
        }
        CellScale fromDisplay = scaleIn(cell.displayValue());
        return fromDisplay == null ? CellScale.UNIT : fromDisplay;
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
}
