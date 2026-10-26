package com.resurgent.tev.doc.pack;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pack field instructions must name which repeated label row to read (#157).
 * Bare {@code Total}-style hints leave cloud extractors a genuine choice.
 */
public final class RowDisambiguation {

    private static final Pattern BARE_TOTAL_LABEL = Pattern.compile(
            "^(?i)(total|sub\\s*total|grand\\s*total|net\\s*payable|net\\s*amount)(\\s+amount)?\\.?$");

    private RowDisambiguation() {}

    /**
     * True when the instruction does not disambiguate among repeated rows with the same label.
     *
     * @param promptHint pack {@code prompt_hint} (may be null)
     * @param synonyms printed label aliases (may be empty)
     */
    public static boolean isAmbiguousInstruction(String promptHint, List<String> synonyms) {
        if (promptHint == null || promptHint.isBlank()) {
            return repeatsBareTotalLabel(synonyms);
        }
        String hint = promptHint.trim();
        if (BARE_TOTAL_LABEL.matcher(hint).matches()) {
            return true;
        }
        String lower = hint.toLowerCase(Locale.ROOT);
        if (lower.contains("total") && !hasDisambiguator(lower)) {
            return hint.length() <= 48 && !lower.contains("line");
        }
        return false;
    }

    private static boolean repeatsBareTotalLabel(List<String> synonyms) {
        if (synonyms == null || synonyms.isEmpty()) {
            return false;
        }
        for (String label : synonyms) {
            if (label == null || label.isBlank()) {
                continue;
            }
            String trimmed = label.trim();
            if (BARE_TOTAL_LABEL.matcher(trimmed).matches()
                    || "total".equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDisambiguator(String lowerHint) {
        return lowerHint.contains("grand total row")
                || lowerHint.contains("final grand")
                || lowerHint.contains("statement grand")
                || lowerHint.contains("section subtotal")
                || lowerHint.contains("section total")
                || lowerHint.contains("excluding")
                || lowerHint.contains("each statement")
                || lowerHint.contains("each side")
                || lowerHint.contains("line item")
                || lowerHint.contains("subtotal row")
                || lowerHint.contains("quoted gross")
                || lowerHint.contains("including tax");
    }

    /**
     * Collects ambiguous field/table column instructions from a pack.
     */
    public static List<String> ambiguousSlots(DocTypePack pack) {
        List<String> problems = new java.util.ArrayList<>();
        if (pack == null) {
            return List.of();
        }
        for (PackField field : pack.fields()) {
            if (isAmbiguousInstruction(field.promptHint(), field.synonyms())) {
                problems.add("field:" + field.key());
            }
        }
        for (var table : pack.tables()) {
            Object fieldsObj = table.get("fields");
            if (!(fieldsObj instanceof List<?> columns)) {
                continue;
            }
            String tableName = String.valueOf(table.getOrDefault("name", "table"));
            for (Object colObj : columns) {
                if (!(colObj instanceof java.util.Map<?, ?> col)) {
                    continue;
                }
                String key = String.valueOf(col.get("key"));
                String hint = col.get("prompt_hint") == null ? null : String.valueOf(col.get("prompt_hint"));
                @SuppressWarnings("unchecked")
                List<String> labels = col.get("synonyms") instanceof List<?> syns
                        ? (List<String>) syns
                        : List.of();
                if (isAmbiguousInstruction(hint, labels)) {
                    problems.add("table:" + tableName + "." + key);
                }
            }
        }
        return List.copyOf(problems);
    }
}
