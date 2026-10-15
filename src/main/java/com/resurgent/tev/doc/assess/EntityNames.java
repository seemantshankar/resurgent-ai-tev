package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.compare.Comparisons;
import java.util.List;
import java.util.Locale;

/**
 * Pure name helpers of the entity scan (issue #136). Entity identity and
 * spelling-variant merging reuse the #135 comparison module
 * ({@link Comparisons#name}); containment adds the one thing equality cannot
 * answer — whether a fact's quoted text states an entity's name.
 */
public final class EntityNames {

    private EntityNames() {}

    /**
     * The stored wire role of an identification, or null when the stated role is
     * not one of the three mandate roles (borrower, promoter, group company) —
     * an unknown role is dropped, never forced into a nearby one.
     *
     * @param raw role as stated by the model
     * @return {@code borrower} | {@code promoter} | {@code group_company}, or null
     */
    public static String normaliseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{Alnum}]+", " ").trim();
        return switch (cleaned) {
            case "borrower", "borrowers" -> "borrower";
            case "promoter", "promoters" -> "promoter";
            case "group company", "group companies" -> "group_company";
            default -> null;
        };
    }

    /**
     * Whether two names are one entity under the deterministic loose rules of
     * {@link Comparisons#name} — case, punctuation, whitespace and legal-suffix
     * equivalences. Names that are nothing but a legal form never match.
     *
     * @param left first name as stated
     * @param right second name as stated
     * @return true when the names are spellings of one entity
     */
    public static boolean sameName(String left, String right) {
        return Comparisons.name(left, right).agrees();
    }

    /**
     * Whether {@code haystack} states {@code name} as a run of tokens, ignoring
     * case and punctuation. Every token of the name must appear consecutively;
     * a partial name never matches. Used to anchor a fact to the entity its
     * quoted text names.
     *
     * @param haystack text to search (a quote, a subject wording), may be null
     * @param name the name as stated, may be null
     * @return true when the whole name appears in the haystack
     */
    public static boolean containsName(String haystack, String name) {
        List<String> nameTokens = tokens(name);
        if (nameTokens.isEmpty()) {
            return false;
        }
        List<String> hayTokens = tokens(haystack);
        if (hayTokens.size() < nameTokens.size()) {
            return false;
        }
        outer:
        for (int start = 0; start <= hayTokens.size() - nameTokens.size(); start++) {
            for (int i = 0; i < nameTokens.size(); i++) {
                if (!hayTokens.get(start + i).equals(nameTokens.get(i))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Lower-cased alphanumeric tokens; runs of anything else separate. */
    private static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.of(cleaned.split(" "));
    }
}
