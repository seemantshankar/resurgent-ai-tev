package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * AI judgement for one conflict no precedence rule covers (issue #145, ADR 0026
 * tier 2): asks the model which side's value to use, with written reasoning citing
 * both pages, and keeps both values either way. The model may decline (it does not
 * have to be confident) — then the conflict stays open. Every call — success or
 * failure — is reported to the stage usage sink. No human action follows a
 * settlement: it is flagged as settled by AI and ends there.
 */
final class LlmConflictSettlement {

    private static final String SYSTEM = """
            You settle ONE disagreement between two sources of a mandate.
            A source is a document page or a financial model cell. The model is read only.
            Both values are kept either way; you only say which one to use.
            Reply with a single JSON object: \
            {"settle": true, "chosen_side": "left" | "right", "reasoning": "<why, citing each document page or model sheet and cell>"}.
            Decline with {"settle": false, "reasoning": "<why you cannot tell>"} whenever you are \
            not confident which value is right. Never guess.""";

    private static final int MAX_COMPLETION_TOKENS = 600;
    private static final int MAX_QUOTE_CHARS = 300;

    private LlmConflictSettlement() {}

    /**
     * Asks the model to settle one disagreement.
     *
     * @param client document LLM seam ({@code null} keeps the offline path)
     * @param dataPoint the data point disagreed on
     * @param leftDescription left side description (document, page, value, quote)
     * @param rightDescription right side description (document, page, value, quote)
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return the verdict, or empty when the model declined or gave nothing usable
     */
    static Optional<Verdict> settle(DocLlmClient client, String dataPoint,
            String leftDescription, String rightDescription,
            Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null) {
            return Optional.empty();
        }
        String user = "Data point: " + dataPoint + "\n"
                + "Left: " + shorten(leftDescription) + "\n"
                + "Right: " + shorten(rightDescription) + "\n";
        try {
            DocLlmClient.Completion completion = client.complete(SYSTEM, user,
                    MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] conflict settlement LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] conflict settlement LLM failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Interprets the model reply; tolerant of prose around the JSON object. A
     * confident settlement needs a valid side and written reasoning — anything less
     * is treated as a decline.
     */
    static Optional<Verdict> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        if (!node.path("settle").asBoolean(false)) {
            return Optional.empty();
        }
        String side = node.path("chosen_side").asText("");
        String reasoning = node.path("reasoning").asText("");
        if (!"left".equals(side) && !"right".equals(side)) {
            return Optional.empty();
        }
        if (reasoning == null || reasoning.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Verdict("left".equals(side), reasoning.strip()));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= MAX_QUOTE_CHARS
                ? compact
                : compact.substring(0, MAX_QUOTE_CHARS) + "…";
    }

    /**
     * One settlement decision.
     *
     * @param choseLeft true when the left side's value is to be used
     * @param reasoning the model's written reasoning (never blank)
     */
    record Verdict(boolean choseLeft, String reasoning) {}
}
