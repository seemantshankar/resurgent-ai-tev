package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits canonical, region-free formula skeletons in which every coordinate reference &mdash; absolute,
 * relative, or a range &mdash; is replaced with the single generic token {@code $ABS$}.
 *
 * <p>§7.4.1 additionally specifies {@code R} for relative references and
 * {@code RANGE_VERTICAL}/{@code RANGE_HORIZONTAL} for ranges. Those tokens are not emitted yet; every
 * reference collapses to {@code $ABS$}, which still makes a formula family compare equal to itself
 * but cannot distinguish a family from a total row.
 */
public final class FormulaSkeletonGenerator {

    /** Skeleton assigned to a constant-formula: one built from literals and arithmetic, referencing nothing. */
    public static final String CONSTANT_SKELETON = "=CONST";

    private FormulaSkeletonGenerator() {}

    public static String generate(String formulaText, List<FormulaToken> tokens) {
        if (formulaText == null || formulaText.isBlank()) {
            return null;
        }

        String clean = formulaText.startsWith("=") ? formulaText.substring(1) : formulaText;
        if (tokens == null || tokens.isEmpty()) {
            return clean;
        }

        // Sort tokens descending by rawToken length to prevent substring replacement collisions
        List<FormulaToken> sorted = new ArrayList<>(tokens);
        sorted.sort(Comparator.comparingInt((FormulaToken t) -> t.rawToken().length()).reversed());

        String skeleton = clean;
        for (FormulaToken token : sorted) {
            String rawToken = token.rawToken();
            if (rawToken == null || rawToken.isBlank()) {
                continue;
            }

            String replacement = abstractReferenceToken(token);
            skeleton = skeleton.replace(rawToken, replacement);
        }

        return skeleton;
    }

    private static String abstractReferenceToken(FormulaToken token) {
        if (token.targetSheetName() != null && !token.targetSheetName().isBlank() && token.targetRange() != null) {
            return token.rawToken().replace(token.targetRange(), "$ABS$");
        }
        return "$ABS$";
    }
}
