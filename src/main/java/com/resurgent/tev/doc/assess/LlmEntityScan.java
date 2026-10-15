package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * LLM assist for the entity {@link EntityScan} (issue #136): asks one document for
 * the mandate's entities it names — borrower, promoters, group companies — each with
 * the name as written, its role, the verbatim the name was read from and a reason.
 * The reply is anchored back to the document's text so every identification carries
 * real page provenance. Every call — success or failure — is reported to the stage
 * usage sink.
 */
public final class LlmEntityScan {

    private static final String SYSTEM = """
            You read ONE supporting document and list the mandate's entities it names: \
            the borrower, its promoters and its group companies.
            Reply with a single JSON object: {"entities": [{"name": "<name exactly as \
            written>", "role": "<borrower|promoter|group_company>", "quote": "<verbatim \
            text the name was read from>", "reason": "<short reason for this role>"}]}.
            Use each name exactly as the document writes it; never invent a name.
            Reply {"entities": []} when the document names none of these.""";

    private static final int MAX_COMPLETION_TOKENS = 1_500;
    private static final int MAX_TEXT_CHARS = 12_000;

    private LlmEntityScan() {}

    /**
     * Asks the model which entities one document names.
     *
     * @param client document LLM seam ({@code null} keeps the offline path)
     * @param pages page texts for anchoring the reply back to the layout
     * @param documentVersionId version the identifications belong to
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return the anchored identifications, empty when none were found
     */
    public static List<EntityScan.Identification> find(DocLlmClient client, List<PageText> pages,
            long documentVersionId, Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null || pages.isEmpty()) {
            return List.of();
        }
        String text = joinPages(pages);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM, "Document text:\n" + text, MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content(), pages, documentVersionId);
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] entity LLM failed: " + e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] entity LLM failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Interprets the model reply; tolerant of prose around the JSON object. An
     * identification without a usable name or role is dropped, never guessed into
     * shape.
     */
    static List<EntityScan.Identification> interpret(String content, List<PageText> pages,
            long documentVersionId) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return List.of();
        }
        JsonNode entities = node.get("entities");
        if (entities == null || !entities.isArray()) {
            return List.of();
        }
        List<EntityScan.Identification> found = new ArrayList<>();
        for (JsonNode item : entities) {
            if (!item.isObject()) {
                continue;
            }
            String name = text(item.get("name"));
            String role = EntityNames.normaliseRole(text(item.get("role")));
            if (name == null || role == null) {
                continue;
            }
            String quote = text(item.get("quote"));
            String reason = text(item.get("reason"));
            Anchored anchored = anchor(quote, name, pages);
            found.add(new EntityScan.Identification(documentVersionId, name, role,
                    anchored.text(), anchored.pageIndex(), reason));
        }
        return found;
    }

    /** The verbatim and page an identification anchors to in the document text. */
    private record Anchored(String text, Integer pageIndex) {}

    /**
     * Anchors the reply to the page and line it came from, so the identification
     * keeps real provenance. Tries the quote first, then the name itself; an
     * unanchorable reply is kept but marked page-less.
     */
    private static Anchored anchor(String quote, String name, List<PageText> pages) {
        Anchored fromQuote = findLine(quote, pages);
        if (fromQuote != null) {
            return fromQuote;
        }
        Anchored fromName = findLine(name, pages);
        if (fromName != null) {
            return fromName;
        }
        return new Anchored(quote != null ? quote : name, null);
    }

    private static Anchored findLine(String needle, List<PageText> pages) {
        if (needle == null || needle.isBlank()) {
            return null;
        }
        String normalized = AssessmentRepository.normalizeWhitespace(needle);
        for (PageText page : pages) {
            for (String line : page.text().split("\\R")) {
                String normalizedLine = AssessmentRepository.normalizeWhitespace(line);
                if (normalizedLine.contains(normalized)) {
                    return new Anchored(normalizedLine, page.pageIndex());
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
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
