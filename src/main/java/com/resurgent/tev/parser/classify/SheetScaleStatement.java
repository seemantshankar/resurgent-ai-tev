package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The scale a sheet states once in its title area ({@code Rs. In Lacs}, {@code Amount in
 * Rs}), found deterministically. It is the fallback for money inputs whose own labels carry
 * no scale word, and the check against which an LLM-proposed region scale is verified.
 *
 * <p>A sheet that states two different scales yields none: the claim is then ambiguous and
 * region-level statements (Layer A) decide instead.
 */
final class SheetScaleStatement {

    /** Statements live in the title rows; deeper text is data or prose. */
    static final int TITLE_ROWS = 15;

    private static final int MAX_STATEMENT_LENGTH = 80;
    private static final Pattern MONEY_CUE = Pattern.compile(
            "(?i)(?:\\brs\\b\\.?|\\binr\\b|₹|\\bamt\\b\\.?|\\bamount\\b|\\brupees?\\b)");
    private static final Pattern IN_RUPEES = Pattern.compile("(?i)\\bin\\s+rs\\b\\.?");
    /**
     * A cell's own label in plain rupees: {@code in Rs.}, or a bracketed {@code (Rs.)}, {@code (INR)},
     * {@code (₹)}. Too weak for a sheet title ({@code Rate (Rs.)} is per unit), enough for the line it labels.
     */
    private static final Pattern LABEL_RUPEES = Pattern.compile(
            "(?i)(?:\\bin\\s+rs\\b\\.?|\\(\\s*(?:rs\\.?|inr|₹)\\s*\\))");

    private SheetScaleStatement() {}

    /**
     * The scale one piece of text states for money: a scale word next to a money cue, or
     * plain rupees ({@code in Rs.}, which is {@link CellScale#UNIT}). {@code null} when the text
     * states none, or is prose rather than a short heading.
     */
    static CellScale statedBy(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_STATEMENT_LENGTH || !MONEY_CUE.matcher(trimmed).find()) {
            return null;
        }
        CellScale named = CellScale.fromText(trimmed);
        if (named != null) {
            return named;
        }
        return IN_RUPEES.matcher(trimmed).find() ? CellScale.UNIT : null;
    }

    /** A cell label says its money is in plain rupees ({@code in Rs.}, {@code (Rs.)}), with no scale word. */
    static boolean statesRupees(String text) {
        return text != null && LABEL_RUPEES.matcher(text).find();
    }

    /** Worksheet id to its single stated scale; sheets with none or conflicting statements are absent. */
    static Map<Long, CellScale> byWorksheet(List<InterpretationCellView> cells) {
        Map<Long, Set<CellScale>> found = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            if (cell.rowNum() > TITLE_ROWS || !"text".equals(cell.valueType())) {
                continue;
            }
            CellScale stated = statedBy(cell.textValue());
            if (stated != null) {
                found.computeIfAbsent(cell.worksheetId(), id -> new HashSet<>()).add(stated);
            }
        }
        Map<Long, CellScale> result = new HashMap<>();
        found.forEach((sheet, scales) -> {
            if (scales.size() == 1) {
                result.put(sheet, scales.iterator().next());
            }
        });
        return result;
    }
}
