package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * LLM summaries for one document version's chapter passages: a single completion per
 * document whose {@code summaries} array maps to the chapters by index. Tolerant — a
 * missing entry, a failing or unusable reply simply leaves the deterministic fallback
 * in place for that chapter. Every call — success or failure — is reported to the
 * stage usage sink.
 */
final class LlmPassageSummaries {

    private static final String SYSTEM = """
            You summarise the chapters of ONE document.
            Reply with a single JSON object: {"summaries": ["<one-line summary>", ...]} \
            with exactly one summary per chapter, in the order given.
            Each summary is one short line, no markdown, no headings.""";

    private static final int MAX_COMPLETION_TOKENS = 700;
    private static final int MAX_CHAPTER_CHARS = 1_500;

    private LlmPassageSummaries() {}

    /**
     * Asks the model for one summary per chapter.
     *
     * @param client document LLM seam ({@code null} keeps the offline path)
     * @param chapters chapter digests (heading plus text), in order
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return summaries aligned to the chapters; entries may be null when the model
     *     did not summarise that chapter, empty when no call was made or it failed
     */
    static List<String> find(DocLlmClient client, List<String> chapters,
            Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null || chapters.isEmpty()) {
            return List.of();
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM, buildUser(chapters), MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] passage summary LLM failed: " + e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] passage summary LLM failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Interprets the reply; tolerant of prose around the JSON object. */
    static List<String> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return List.of();
        }
        JsonNode summaries = node.get("summaries");
        if (summaries == null || !summaries.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : summaries) {
            if (item == null || !item.isTextual()) {
                result.add(null);
                continue;
            }
            String summary = item.asText().strip();
            result.add(summary.isEmpty() ? null : summary);
        }
        return result;
    }

    private static String buildUser(List<String> chapters) {
        StringBuilder sb = new StringBuilder("Chapters:\n");
        for (int i = 0; i < chapters.size(); i++) {
            String digest = chapters.get(i) == null ? "" : chapters.get(i);
            if (digest.length() > MAX_CHAPTER_CHARS) {
                digest = digest.substring(0, MAX_CHAPTER_CHARS);
            }
            sb.append('[').append(i + 1).append("] ").append(digest).append("\n\n");
        }
        return sb.toString();
    }
}
