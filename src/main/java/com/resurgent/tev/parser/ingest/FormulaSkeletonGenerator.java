package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits canonical, region-free formula skeletons where coordinate references are replaced with {@code $ABS$}.
 */
public final class FormulaSkeletonGenerator {

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

            String replacement;
            if (token.targetSheetName() != null && !token.targetSheetName().isBlank() && token.targetRange() != null) {
                replacement = rawToken.replace(token.targetRange(), "$ABS$");
            } else if (token.targetRange() != null) {
                replacement = "$ABS$";
            } else {
                replacement = "$ABS$";
            }

            skeleton = skeleton.replace(rawToken, replacement);
        }

        return skeleton;
    }
}
