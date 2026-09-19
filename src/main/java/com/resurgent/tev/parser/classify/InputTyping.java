package com.resurgent.tev.parser.classify;

import java.util.Locale;

/**
 * Types one hardcoded cell from its own row label and displayed text. These are the
 * only cells that need judging — roughly 600 decisions on the working FM rather
 * than 8,791 — because everything else is derived and the formulas carry the type
 * forward.
 */
final class InputTyping {

    private InputTyping() {}

    static ResolvedUnit of(GraphCell cell) {
        NumericKind kind = LayerBAmountSupport.classifyKind(
                cell.rowLabel() == null ? "" : cell.rowLabel(),
                cell.columnLabel(),
                cell.displayValue(),
                true);
        kind = applyFormat(kind, cell.numberFormat());
        CellKind cellKind = CellKind.from(kind);
        if (cellKind == null) {
            return ResolvedUnit.unresolved();
        }
        return ResolvedUnit.of(cellKind, scaleOf(cell));
    }

    /**
     * Scale named by the cell's own formula divisor ({@code /10^5} → lakh), else by
     * the row label, else by the column header, else nothing. A constant-only formula
     * such as {@code 8000*300/100000} states its scale in its own arithmetic; typing
     * it from labels alone would lose it. The cell's resulting figure never implies a
     * scale (ADR 0020).
     */
    static CellScale scaleOf(GraphCell cell) {
        CellScale fromFormula = InterpretationEvidenceResolver.formulaDivisorScale(cell.formulaText());
        if (fromFormula != null) {
            return fromFormula;
        }
        CellScale fromLabel = CellScale.fromText(cell.rowLabel());
        if (fromLabel != null) {
            return fromLabel;
        }
        CellScale fromColumn = CellScale.fromText(cell.columnLabel());
        return fromColumn == null ? CellScale.UNIT : fromColumn;
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
        if (KindTokens.displayNamesCurrency(numberFormat)) {
            return NumericKind.MONEY;
        }
        return kind;
    }
}
