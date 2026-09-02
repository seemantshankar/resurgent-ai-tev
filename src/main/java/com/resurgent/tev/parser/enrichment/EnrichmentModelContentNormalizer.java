package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Normalizes raw model text into JSON suitable for {@link EnrichmentReportJson}. */
final class EnrichmentModelContentNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnrichmentModelContentNormalizer() {}

    static String normalize(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int openingFenceEnd = trimmed.indexOf('\n');
            if (openingFenceEnd >= 0) {
                String body = trimmed.substring(openingFenceEnd + 1);
                if (body.endsWith("```")) {
                    body = body.substring(0, body.length() - 3);
                }
                trimmed = body.strip();
            }
        }
        return coerceStringProblems(trimmed);
    }

    /**
     * Models sometimes emit {@code "problems": ["note", ...]} instead of Problem objects.
     * Rewrite those strings into the contract shape so a good region pass is not discarded.
     */
    private static String coerceStringProblems(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return json;
            }
            JsonNode problems = root.get("problems");
            if (problems == null || !problems.isArray()) {
                return json;
            }
            boolean changed = false;
            ArrayNode rewritten = MAPPER.createArrayNode();
            for (JsonNode entry : problems) {
                if (entry != null && entry.isTextual()) {
                    // Free-text notes are not partition problems; drop them.
                    changed = true;
                    continue;
                }
                rewritten.add(entry);
            }
            if (!changed) {
                return json;
            }
            ((ObjectNode) root).set("problems", rewritten);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ignored) {
            return json;
        }
    }
}
