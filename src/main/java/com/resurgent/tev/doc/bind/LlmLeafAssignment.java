package com.resurgent.tev.doc.bind;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one model call that places the pack leaves the shared nomenclature does not know
 * (issue #153). The model sees each unknown leaf with the value and page it was read on
 * and chooses only from the paths of the living ontology; anything else is refused by
 * the caller's gate, never stored. A model that declines, fails or answers with prose is
 * treated the same way: those leaves simply stay unbound. No analyst is asked.
 */
final class LlmLeafAssignment {

    private static final String SYSTEM = """
            You place document labels on ONE shared nomenclature — the same heads the
            financial model of this mandate uses.
            A path is the full head chain without a root, e.g. "Balance Sheet > Net Worth".
            Choose the path whose meaning the label states. If the nomenclature has no head
            for it, you may add a new leaf under an existing head; the label itself becomes
            the leaf name.
            Reply with a single JSON object:
            {"assignments":[{"key":"<label key>","path":"<head > ... > leaf>","\
            reasoning":"<why, citing the page the label is on>"}]}
            Leave out a label you cannot place. Never guess.""";

    private static final int MAX_COMPLETION_TOKENS = 1200;
    private static final int MAX_CONTEXT_CHARS = 160;

    private LlmLeafAssignment() {}

    /** One placement the model made for one pack field key. */
    record Assignment(String path, String reasoning) {}

    /** The placements plus the usage of the single call that produced them. */
    record Result(Map<String, Assignment> assignments, DocLlmClient.Usage usage) {}

    /**
     * Asks the model to place every leaf the deterministic pass could not resolve.
     *
     * @param client document LLM seam
     * @param leaves one line per unknown leaf, with its value and page
     * @param paths the paths of the living ontology, the only ones on offer
     * @return the placements (empty when the reply is unusable) and the call's usage
     */
    static Result assign(DocLlmClient client, List<String> leaves, List<String> paths) {
        long started = System.nanoTime();
        String user = "Labels to place:\n" + String.join("\n", leaves)
                + "\n\nPaths of the shared nomenclature:\n" + String.join("\n", paths) + "\n";
        try {
            DocLlmClient.Completion completion = client.complete(SYSTEM, user,
                    MAX_COMPLETION_TOKENS);
            return new Result(interpret(completion.content()), completion.usage());
        } catch (RuntimeException e) {
            long ms = Math.round((System.nanoTime() - started) / 1_000_000.0);
            System.err.println("[doc-bind] leaf assignment LLM failed: " + e.getMessage());
            return new Result(Map.of(), DocLlmClient.Usage.failed(client.id(), ms));
        }
    }

    /** Placements keyed by pack field key; tolerant of prose around the JSON object. */
    static Map<String, Assignment> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Map.of();
        }
        Map<String, Assignment> byKey = new LinkedHashMap<>();
        for (JsonNode row : node.path("assignments")) {
            String key = row.path("key").asText("");
            String path = row.path("path").asText("");
            if (key.isBlank() || path.isBlank() || byKey.containsKey(key)) {
                continue;
            }
            String reasoning = row.path("reasoning").asText("");
            byKey.put(key, new Assignment(path.trim(), reasoning.isBlank()
                    ? "placed by the model" : reasoning.strip()));
        }
        return byKey;
    }

    /** One prompt line: the key, the label the pack gave it, and what was read. */
    static String describe(String fieldKey, String leaf, String valueText, Long page,
            String verbatim) {
        StringBuilder line = new StringBuilder("- ").append(fieldKey).append(": \"")
                .append(leaf).append('"');
        if (valueText != null && !valueText.isBlank()) {
            line.append(" = ").append(shorten(valueText));
        }
        if (verbatim != null && !verbatim.isBlank()) {
            line.append(" | quote: ").append(shorten(verbatim));
        }
        if (page != null) {
            line.append(" | page ").append(page);
        }
        return line.toString();
    }

    private static String shorten(String text) {
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= MAX_CONTEXT_CHARS
                ? compact : compact.substring(0, MAX_CONTEXT_CHARS) + "…";
    }
}
