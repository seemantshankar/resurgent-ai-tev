package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Allowlisted SUM shapes and range expansion. A range is never a single leaf.
 */
final class SumCoverage {

    private static final Pattern SUM_SHAPE = Pattern.compile(
            "(?i)^SUM\\(\\s*(?:(?:'[^']+'|[A-Za-z0-9_]+)!)?\\$?[A-Z]+\\$?\\d+"
                    + "(?::\\$?[A-Z]+\\$?\\d+)?"
                    + "(?:\\s*,\\s*(?:(?:'[^']+'|[A-Za-z0-9_]+)!)?\\$?[A-Z]+\\$?\\d+"
                    + "(?::\\$?[A-Z]+\\$?\\d+)?)*\\s*\\)$");

    record CellRef(int row, int col) {}

    private SumCoverage() {}

    static boolean allowlisted(String formula, List<String> functionTokens) {
        if (formula == null || formula.isBlank()) {
            return false;
        }
        for (String function : functionTokens) {
            if (!"SUM".equalsIgnoreCase(function)) {
                return false;
            }
        }
        String clean = formula.startsWith("=") ? formula.substring(1) : formula;
        return SUM_SHAPE.matcher(clean).matches();
    }

    static List<CellRef> expand(String targetRange) {
        if (targetRange == null || targetRange.isBlank()) {
            return List.of();
        }
        if (!targetRange.contains(":")) {
            CellReference ref = new CellReference(targetRange);
            if (ref.getCol() < 0 || ref.getRow() < 0) {
                return List.of();
            }
            return List.of(new CellRef(ref.getRow() + 1, ref.getCol() + 1));
        }
        CellRangeAddress area = CellRangeAddress.valueOf(targetRange);
        List<CellRef> cells = new ArrayList<>();
        for (int row = area.getFirstRow(); row <= area.getLastRow(); row++) {
            for (int col = area.getFirstColumn(); col <= area.getLastColumn(); col++) {
                cells.add(new CellRef(row + 1, col + 1));
            }
        }
        return cells;
    }
}
