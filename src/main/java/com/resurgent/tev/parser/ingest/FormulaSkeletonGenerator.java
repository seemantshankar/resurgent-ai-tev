package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emits canonical, position-insensitive formula skeletons (§7.4.1): a formula with the
 * same shape, regardless of which columns/rows/sheets it actually references, produces
 * the same skeleton.
 *
 * <p>Token substitution rules:
 * <ul>
 *   <li>A both-absolute reference ({@code $A$1}) &rarr; {@code $ABS$}.</li>
 *   <li>Any other (relative) reference &rarr; {@code R}.</li>
 *   <li>A range whose endpoints share a column &rarr; {@code RANGE_VERTICAL}; sharing a
 *       row &rarr; {@code RANGE_HORIZONTAL}; a genuine 2D block (rare) defaults to
 *       {@code RANGE_VERTICAL}.</li>
 *   <li>An external-workbook reference &rarr; {@code EXT}.</li>
 *   <li>A defined-name reference &rarr; {@code NAME}.</li>
 * </ul>
 * Numeric/text literals and operators are untouched. Quoted string literals are masked
 * out before substitution so a coincidental text match inside a string (e.g. {@code ="A  B"})
 * is never rewritten, and each token's *entire* raw span (including any {@code 'sheet'!}
 * prefix) is replaced — never just the coordinate portion — so skeletons are insensitive
 * to which sheet is referenced, not just which cell.
 */
public final class FormulaSkeletonGenerator {

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"]|\"\")*\"");
    private static final Pattern CONSTANT_GRAMMAR = Pattern.compile("^[0-9+\\-*/().\\s]+$");
    private static final String PLACEHOLDER_MARK = "\u0000";

    private FormulaSkeletonGenerator() {}

    public static String generate(String formulaText, List<FormulaToken> tokens) {
        if (formulaText == null || formulaText.isBlank()) {
            return null;
        }

        boolean hasEquals = formulaText.startsWith("=");
        String clean = hasEquals ? formulaText.substring(1) : formulaText;

        if (tokens == null || tokens.isEmpty()) {
            // A zero-reference formula is either a constant-formula (numeric-literal
            // arithmetic only — same grammar ConstantFormulaEvaluator accepts) or
            // something else entirely (e.g. text-only); only the former collapses to
            // a fixed skeleton token.
            if (CONSTANT_GRAMMAR.matcher(clean.trim()).matches() && clean.trim().matches(".*\\d.*")) {
                // Formula adapters disagree on whether formula_text carries its leading '=';
                // the sentinel is canonical either way so region scoring can reliably exclude it.
                return "=CONST";
            }
            return hasEquals ? "=" + clean : clean;
        }

        // Mask out quoted string literals so token substitution never rewrites text
        // that merely happens to contain a matching substring.
        List<String> literals = new ArrayList<>();
        StringBuilder maskedBuilder = new StringBuilder();
        Matcher m = STRING_LITERAL.matcher(clean);
        int last = 0;
        while (m.find()) {
            maskedBuilder.append(clean, last, m.start());
            maskedBuilder.append(PLACEHOLDER_MARK).append(literals.size()).append(PLACEHOLDER_MARK);
            literals.add(m.group());
            last = m.end();
        }
        maskedBuilder.append(clean.substring(last));
        String masked = maskedBuilder.toString();

        // Sort tokens by rawToken length descending to avoid a short token's raw span
        // being a substring of a longer token's raw span and getting replaced first.
        List<FormulaToken> sorted = new ArrayList<>(tokens);
        sorted.sort(Comparator.comparingInt((FormulaToken t) -> t.rawToken() == null ? 0 : t.rawToken().length()).reversed());

        for (FormulaToken token : sorted) {
            String rawToken = token.rawToken();
            if (rawToken == null || rawToken.isBlank()) {
                continue;
            }
            masked = masked.replace(rawToken, classify(token));
        }

        // Restore the original literal spans verbatim.
        for (int i = 0; i < literals.size(); i++) {
            masked = masked.replace(PLACEHOLDER_MARK + i + PLACEHOLDER_MARK, literals.get(i));
        }

        return (hasEquals ? "=" : "") + masked;
    }

    private static String classify(FormulaToken token) {
        String kind = token.refKind();
        if ("external".equals(kind)) {
            return "EXT";
        }
        if ("defined_name".equals(kind)) {
            return "NAME";
        }
        boolean isRange = "local_range".equals(kind) || "cross_sheet_range".equals(kind)
                || (token.targetRange() != null && token.targetRange().contains(":"));
        if (isRange) {
            return classifyRange(token.targetRange());
        }
        boolean bothAbsolute = Boolean.TRUE.equals(token.absRow()) && Boolean.TRUE.equals(token.absCol());
        return bothAbsolute ? "$ABS$" : "R";
    }

    private static String classifyRange(String targetRange) {
        if (targetRange == null || !targetRange.contains(":")) {
            return "RANGE_VERTICAL";
        }
        String[] parts = targetRange.split(":", 2);
        String[] first = splitColRow(parts[0]);
        String[] second = splitColRow(parts[1]);
        if (first == null || second == null) {
            return "RANGE_VERTICAL";
        }
        boolean sameCol = first[0].equals(second[0]);
        boolean sameRow = first[1].equals(second[1]);
        if (sameCol && !sameRow) {
            return "RANGE_VERTICAL";
        }
        if (sameRow && !sameCol) {
            return "RANGE_HORIZONTAL";
        }
        // Genuine 2D block (rare): no single natural orientation, default to vertical.
        return "RANGE_VERTICAL";
    }

    private static final Pattern CELL_REF = Pattern.compile("^\\$?([A-Z]+)\\$?(\\d+)$");

    private static String[] splitColRow(String cellRef) {
        Matcher m = CELL_REF.matcher(cellRef.trim());
        if (!m.matches()) {
            return null;
        }
        return new String[] { m.group(1), m.group(2) };
    }
}
