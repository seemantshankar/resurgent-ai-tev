package com.resurgent.tev.parser.classify;

/**
 * One cell as the graph sees it: where it is, what it says, and the nearest label
 * to its left on the same row. No presentation facts — bold, borders and blank
 * rows are deliberately absent, because they are author-specific and optional.
 */
record GraphCell(
        long cellId,
        long worksheetId,
        String coord,
        int rowNum,
        int colNum,
        String formulaText,
        boolean numeric,
        boolean error,
        String rowLabel,
        String displayValue,
        String numericValue,
        String valueType,
        String numberFormat) {

    boolean isFormula() {
        return formulaText != null && !formulaText.isBlank();
    }
}
