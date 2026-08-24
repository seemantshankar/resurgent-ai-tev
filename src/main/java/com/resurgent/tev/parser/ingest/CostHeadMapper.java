package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.resurgent.tev.parser.db.Jsonb;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps region labels onto locked cost-head codes. Exact unique aliases become
 * calculated mappings; collisions and fuzzy ranks stay pending for review.
 */
final class CostHeadMapper {

    static final String EXACT_ALIAS = "exact_alias";
    static final String FUZZY_PROPOSAL = "fuzzy_proposal";
    static final String CARRIED = "carried";

    private static final Pattern NON_TOKEN = Pattern.compile("[^a-z0-9]+");
    private static final double FUZZY_FLOOR = 0.82;

    record Proposal(
            long regionId,
            String regionKey,
            String sourceLabel,
            String code,
            String method,
            double score,
            double runnerUpMargin,
            double confidence,
            String reasonsJson,
            boolean pending) {}

    List<Proposal> map(String label, long regionId, String regionKey, String carriedCode) {
        if (carriedCode != null && !carriedCode.isBlank()) {
            return List.of(new Proposal(regionId, regionKey, label, carriedCode, CARRIED,
                    1.0, 1.0, 1.0, reasons("CARRIED_DECISION"), false));
        }
        List<String> exact = CostHeadVocabulary.exactMatches(label);
        if (exact.size() == 1) {
            return List.of(new Proposal(regionId, regionKey, label, exact.getFirst(), EXACT_ALIAS,
                    1.0, 1.0, 1.0, reasons("EXACT_ALIAS"), false));
        }
        if (exact.size() > 1) {
            List<Proposal> proposals = new ArrayList<>();
            for (String code : exact) {
                proposals.add(new Proposal(regionId, regionKey, label, code, EXACT_ALIAS,
                        1.0, 0.0, 0.4, reasons("AMBIGUOUS_EXACT_ALIAS"), true));
            }
            return proposals;
        }
        Ranked fuzzy = bestFuzzy(label);
        if (fuzzy == null) {
            return List.of();
        }
        return List.of(new Proposal(regionId, regionKey, label, fuzzy.code(), FUZZY_PROPOSAL,
                fuzzy.score(), fuzzy.margin(), Math.min(0.7, fuzzy.score()),
                reasons("FUZZY_RANK"), true));
    }

    /** Codes in descending similarity order. Tests assert order, not a score threshold. */
    static List<String> rankedCodes(String label) {
        String normalized = CostHeadVocabulary.normalizeLabel(label);
        List<Ranked> ranked = scoreAll(normalized);
        List<String> codes = new ArrayList<>();
        for (Ranked rankedCode : ranked) {
            codes.add(rankedCode.code());
        }
        return codes;
    }

    private static Ranked bestFuzzy(String label) {
        String normalized = CostHeadVocabulary.normalizeLabel(label);
        if (normalized.isBlank()) {
            return null;
        }
        List<Ranked> ranked = scoreAll(normalized);
        if (ranked.isEmpty() || ranked.getFirst().score() < FUZZY_FLOOR) {
            return null;
        }
        double margin = ranked.size() == 1 ? ranked.getFirst().score()
                : ranked.getFirst().score() - ranked.get(1).score();
        return new Ranked(ranked.getFirst().code(), ranked.getFirst().score(), margin);
    }

    private static List<Ranked> scoreAll(String normalized) {
        List<Ranked> ranked = new ArrayList<>();
        for (String code : CostHeadVocabulary.codes()) {
            double best = 0;
            for (String alias : CostHeadVocabulary.aliasesFor(code)) {
                double jw = TokenSimilarity.jaroWinkler(normalized, alias);
                double tokens = TokenSimilarity.tokenSetRatio(normalized, alias);
                best = Math.max(best, Math.max(jw, 0.6 * jw + 0.4 * tokens));
            }
            ranked.add(new Ranked(code, best, 0));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked;
    }

    private record Ranked(String code, double score, double margin) {}

    private static String reasons(String... codes) {
        try {
            return Jsonb.toJson(List.of(codes));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

final class TokenSimilarity {

    private static final Pattern NON_TOKEN = Pattern.compile("[^a-z0-9]+");

    private TokenSimilarity() {}

    static double tokenSetRatio(String a, String b) {
        Set<String> left = new LinkedHashSet<>(List.of(NON_TOKEN.split(a)));
        Set<String> right = new LinkedHashSet<>(List.of(NON_TOKEN.split(b)));
        left.remove("");
        right.remove("");
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String token : left) {
            if (right.contains(token)) {
                inter++;
            }
        }
        int union = left.size() + right.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1;
        }
        int matchDistance = Math.max(s1.length(), s2.length()) / 2 - 1;
        boolean[] s1Matches = new boolean[s1.length()];
        boolean[] s2Matches = new boolean[s2.length()];
        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, s2.length());
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0;
        }
        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double jaro = ((double) matches / s1.length()
                + (double) matches / s2.length()
                + (matches - transpositions / 2) / matches) / 3;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
