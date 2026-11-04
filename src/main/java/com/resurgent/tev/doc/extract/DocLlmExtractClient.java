package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;

/**
 * Page-backed extractor. Schema-guided fields come from layout, not a file upload.
 * Stated claims use the injected {@link DocLlmClient} when residency allows a call.
 */
public final class DocLlmExtractClient implements ExtractClient {

    /** Adapter id stored on {@code fact.extractor_id}. */
    public static final String ID = "doc-llm-extract";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STATED_CLAIM_SYSTEM = """
            You read unread sentences from ONE supporting document.
            Reply with a single JSON object:
            {"claims":[{"key":"<snake_case_label>","value":"<asserted value as written>",\
            "quote":"<the whole source sentence>","kind":"text|quantity|date|money"}]}
            Each asserted measure, date, place, status, or category is its own claim.
            The quote is the whole sentence, never a schema path.
            A place phrase is one claim. Do not emit "large" or the company/party name.
            Skip sentences that are contractual clauses.""";

    private static final int STATED_CLAIM_MAX_TOKENS = 2000;

    private final DocLlmClient llm;

    /** Offline page assembly: no stated-claim cloud call. */
    public DocLlmExtractClient() {
        this(null);
    }

    /**
     * Page assembly plus stated-claim reading through {@code llm}. Null keeps
     * unread sentences unread (tests and local-only CLI).
     */
    public DocLlmExtractClient(DocLlmClient llm) {
        this.llm = llm;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
        return new ExtractResult(ID, MAPPER.createObjectNode(), null, null, null);
    }

    @Override
    public ExtractResult extractStatedClaims(ExtractRequest req) throws ExtractException {
        if (llm == null || req == null) {
            return emptyClaims();
        }
        String sentences = req.options().get(ExtractRequest.STATED_CLAIM_SENTENCES);
        if (sentences == null || sentences.isBlank()) {
            return emptyClaims();
        }
        try {
            DocLlmClient.Completion completion = llm.complete(
                    STATED_CLAIM_SYSTEM, sentences, STATED_CLAIM_MAX_TOKENS);
            JsonNode payload = DocLlmJson.object(completion.content());
            int prompt = completion.promptTokens() == null ? 0 : completion.promptTokens();
            int completionTokens = completion.completionTokens() == null
                    ? 0 : completion.completionTokens();
            ExtractStats stats = new ExtractStats(1, 0, prompt, completionTokens,
                    completion.costUsd(), completion.latencyMs());
            return new ExtractResult(ID, payload, java.util.List.of(), java.util.List.of(), stats);
        } catch (DocLlmException e) {
            throw new ExtractException("stated-claim reading failed: " + e.getMessage(), e);
        }
    }

    private static ExtractResult emptyClaims() {
        return new ExtractResult(ID, MAPPER.createObjectNode(), java.util.List.of(),
                java.util.List.of(), ExtractStats.empty());
    }
}
