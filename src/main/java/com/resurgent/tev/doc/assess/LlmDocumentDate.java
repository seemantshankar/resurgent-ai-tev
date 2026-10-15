package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.extract.DateNormalizer;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * LLM assist for the document date {@link DocumentDateScanner} could not find
 * deterministically. Asks for the date and the verbatim it was read from; the reply is
 * anchored back to the layout so the stored date still carries provenance. Every call —
 * success or failure — is reported to the stage usage sink.
 */
public final class LlmDocumentDate {

    private static final String SYSTEM = """
            You read ONE date from a document: its %s date.
            Reply with a single JSON object: {"date": "<date exactly as written>", \
            "quote": "<verbatim text the date was read from>"}.
            Use the date exactly as written, e.g. "March 2025". Never invent a day.
            Reply {"date": null, "quote": null} when no such date is stated.""";

    private static final int MAX_COMPLETION_TOKENS = 300;
    private static final int MAX_TEXT_CHARS = 12_000;

    private LlmDocumentDate() {}

    /**
     * Asks the model for one stated date.
     *
     * @param client document LLM seam ({@code null} keeps the offline path)
     * @param kind pack-declared date kind ({@code execution}/…)
     * @param pages page texts for anchoring the reply back to the layout
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return the stated date, or empty when the model cannot name one
     */
    public static Optional<StatedDate> find(DocLlmClient client, String kind,
            List<PageText> pages, Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null || pages.isEmpty()) {
            return Optional.empty();
        }
        String text = joinPages(pages);
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM.formatted(kind == null ? "document" : kind),
                    "Document text:\n" + text, MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content(), pages);
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] date LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] date LLM failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static Optional<StatedDate> interpret(String content, List<PageText> pages) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        JsonNode dateNode = node.get("date");
        if (dateNode == null || dateNode.isNull()) {
            return Optional.empty();
        }
        String dateText = dateNode.asText().trim();
        if (dateText.isEmpty() || !DateNormalizer.parse(dateText).ok()) {
            return Optional.empty();
        }
        String quote = node.hasNonNull("quote") ? node.get("quote").asText().trim() : "";
        return Optional.of(anchor(quote, dateText, pages));
    }

    /**
     * Anchors the reply to the page and line it came from, so the date keeps real
     * provenance. An unanchorable reply is kept but marked page-less.
     */
    private static StatedDate anchor(String quote, String dateText, List<PageText> pages) {
        String needle = quote.isBlank() ? dateText : quote;
        String normalizedNeedle = AssessmentRepository.normalizeWhitespace(needle);
        for (PageText page : pages) {
            for (String line : page.text().split("\\R")) {
                String normalizedLine = AssessmentRepository.normalizeWhitespace(line);
                if (normalizedLine.contains(normalizedNeedle)) {
                    return new StatedDate(normalizedLine, dateText, page.pageIndex());
                }
            }
        }
        return new StatedDate(needle, dateText, null);
    }

    private static String joinPages(List<PageText> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageText page : pages) {
            if (sb.length() >= MAX_TEXT_CHARS) {
                break;
            }
            sb.append("[page ").append(page.pageIndex()).append("]\n")
                    .append(page.text()).append('\n');
        }
        return sb.length() <= MAX_TEXT_CHARS ? sb.toString() : sb.substring(0, MAX_TEXT_CHARS);
    }
}
