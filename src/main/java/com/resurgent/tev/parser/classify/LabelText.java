package com.resurgent.tev.parser.classify;

/**
 * The one rule for reading a cell as a text label, shared by the Packet helpers and
 * the cell graph so both agree on what counts as a label. A numeric cell and a
 * formula cell are never labels; a coordinate is never a label either, since the
 * coordinate fallback is exactly what fed {@code J45} into path resolution.
 */
final class LabelText {

    private LabelText() {}

    static String of(
            String textValue,
            String displayValue,
            String numericValue,
            String valueType,
            String formulaText) {
        boolean numeric = numericValue != null && !numericValue.isBlank();
        if (textValue != null && !textValue.isBlank() && !numeric) {
            return textValue.trim();
        }
        boolean formula = formulaText != null && !formulaText.isBlank();
        if (displayValue != null && !displayValue.isBlank()
                && !"number".equals(valueType) && !formula) {
            return displayValue.trim();
        }
        return null;
    }
}
