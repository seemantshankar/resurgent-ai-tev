package com.resurgent.tev.doc.induce;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural fingerprint of a cluster: headings, table headers, and a short
 * layout outline taken from the full multi-page text. Client names, identifiers,
 * and amounts are removed before anything is shown to a model or a web search.
 */
public final class StructuralFingerprint {

    private StructuralFingerprint() {}

    /**
     * Sanitized web-search query for the cluster. Built from every page, not from
     * a prefix of the raw text.
     *
     * @param documents cluster documents (required)
     * @param industryTag industry words allowed in the query
     * @return lowercase query containing no sample entity, PAN, CIN, GSTIN, account, or amount
     */
    public static String webQuery(List<PackMiningClient.MiningDocument> documents, String industryTag) {
        EntityTokenFilter filter = new EntityTokenFilter(sampleTokens(documents));
        List<String> parts = new ArrayList<>();
        if (industryTag != null && !industryTag.isBlank()) {
            parts.add(filter.redact(industryTag.replace('_', ' ')));
        }
        for (String line : structureLines(documents)) {
            String redacted = filter.redact(line);
            if (!redacted.isBlank()) {
                parts.add(redacted);
            }
        }
        String joined = String.join(" ", parts).toLowerCase(java.util.Locale.ROOT);
        joined = joined.replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
        if (joined.isBlank()) {
            return "document type fields tables clauses passages";
        }
        return joined;
    }

    /**
     * Prompt text for the miner: sanitized headings and table headers from the
     * whole cluster, in document order.
     */
    public static String promptText(List<PackMiningClient.MiningDocument> documents) {
        EntityTokenFilter filter = new EntityTokenFilter(sampleTokens(documents));
        StringBuilder sb = new StringBuilder();
        for (String line : structureLines(documents)) {
            String redacted = filter.redact(line);
            if (!redacted.isBlank()) {
                sb.append(redacted).append('\n');
            }
        }
        return sb.toString();
    }

    private static Set<String> sampleTokens(List<PackMiningClient.MiningDocument> documents) {
        Set<String> tokens = new LinkedHashSet<>();
        if (documents == null) {
            return tokens;
        }
        for (PackMiningClient.MiningDocument document : documents) {
            if (document.sampleEntityTokens() != null) {
                tokens.addAll(document.sampleEntityTokens());
            }
        }
        return tokens;
    }

    /**
     * Every heading and table-header line. Long body lines are skipped so a
     * later page is not dropped by a character budget on the raw text.
     */
    static List<String> structureLines(List<PackMiningClient.MiningDocument> documents) {
        List<String> lines = new ArrayList<>();
        if (documents == null) {
            return lines;
        }
        for (PackMiningClient.MiningDocument document : documents) {
            if (document.title() != null && !document.title().isBlank()) {
                lines.add(document.title().trim());
            }
            for (String block : document.layoutText()) {
                if (block == null || block.isBlank()) {
                    continue;
                }
                for (String raw : block.split("\\R")) {
                    String line = raw.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (isTableHeader(line) || isHeading(line)) {
                        lines.add(line);
                    }
                }
            }
        }
        return lines;
    }

    private static boolean isTableHeader(String line) {
        return line.indexOf('|') >= 0 || line.indexOf('\t') >= 0;
    }

    private static boolean isHeading(String line) {
        if (line.length() < 3 || line.length() > 100) {
            return false;
        }
        int spaces = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                spaces++;
            }
        }
        return spaces <= 14;
    }
}
