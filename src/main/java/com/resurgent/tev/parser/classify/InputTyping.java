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

    /**
     * The extension idiom: a label such as {@code 97650 Sqft@ 600 Rs/ Sqft} states a
     * quantity and a rate. The row-label rate cue ({@code Rs/}) would otherwise type
     * the row as a rate, but a value equal to the two multiplied is the extended
     * amount. The arithmetic check is what makes this safe: a label that merely quotes
     * a rate without a matching product still types as a rate.
     */
    private static final Pattern EXTENSION_LABEL = Pattern.compile(
            "(?i)^\\s*([0-9][0-9,.]*)\\s*[^@]*@\\s*([0-9][0-9,.]*)\\b.*");

    private InputTyping() {}

    /** A typed input plus whether its kind rests on the bare money default. */
    record Reading(ResolvedUnit unit, boolean bareDefault) {}

    static ResolvedUnit of(GraphCell cell) {
        return readingOf(cell).unit();
    }

    static Reading readingOf(GraphCell cell) {
        LayerBAmountSupport.KindReading reading = LayerBAmountSupport.classifyKindReading(
                cell.rowLabel() == null ? "" : cell.rowLabel(),
                cell.columnLabel(),
                cell.displayValue(),
                true);
        NumericKind kind = applyFormat(reading.kind(), cell.numberFormat());
        // A number format that decided the kind is a stated cue, not the bare default.
        boolean formatDecided = kind != reading.kind();
        if (kind == NumericKind.RATE && statesAnExtendedAmount(cell)) {
            kind = NumericKind.MONEY;
        }
        CellKind cellKind = CellKind.from(kind);
        if (cellKind == null) {
            return new Reading(ResolvedUnit.unresolved(), false);
        }
        return new Reading(
                ResolvedUnit.of(cellKind, scaleOf(cell)),
                reading.bareDefault() && !formatDecided);
    }

    /**
     * True when the row label states a quantity and a rate and the cell's value is
     * their product, which makes it the extended amount rather than a rate.
     */
    private static boolean statesAnExtendedAmount(GraphCell cell) {
        String label = cell.rowLabel();
        if (label == null || !label.contains("@")) {
            return false;
        }
        Matcher matcher = EXTENSION_LABEL.matcher(label);
        if (!matcher.matches()) {
            return false;
        }
        Double quantity = parse(matcher.group(1));
        Double rate = parse(matcher.group(2));
        Double value = parse(cell.numericValue());
        if (quantity == null || rate == null || value == null) {
            return false;
        }
        double product = quantity * rate;
        return Math.abs(product - value) <= Math.max(1e-6, Math.abs(product) * 1e-9);
    }

    private static Double parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
