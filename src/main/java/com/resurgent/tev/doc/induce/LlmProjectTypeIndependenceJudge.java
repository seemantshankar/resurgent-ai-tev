package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * LLM project-type judgement for spine learning (M3-15): one call asks whether
 * each candidate document type — seen in client document sets of several
 * industries — is required of every borrower in the country regardless of the
 * project type, or is specific to certain project types. The learner trusts the
 * model only for this judgement, never for document types: a type the model names
 * that was not a candidate is dropped, and a candidate the model did not judge
 * counts as not independent. On a failed call the fixture answers, so a run
 * degrades to "never promotes to the spine" instead of failing.
 */
public final class LlmProjectTypeIndependenceJudge implements ProjectTypeIndependenceJudge {

    /** Stage id for {@code llm_usage} of the checklists learning step. */
    public static final String STAGE = DocInduceChecklistsRunner.STAGE;

    private static final String SYSTEM = """
            You are the credit analyst of an infrastructure lender. You are shown document \
            types observed in the client document sets of mandates from different project \
            industries. For each document type, judge whether it is PROJECT-TYPE INDEPENDENT: \
            required of every borrower in the country whatever the project type (constitutional \
            or statutory documents, registrations, audited financials, tax filings) — as \
            opposed to a document specific to certain project types (a power purchase \
            agreement for solar, a hotel licence for hotels). A type observed in only one \
            project type is never independent.
            Reply with a single JSON object:
            {"judgements": [{"document_type": "<code judged verbatim>", \
            "independent": true|false, "reason": "<one line why>"}]}
            Judge every listed document type exactly once. Never invent document types.""";

    private static final int MAX_COMPLETION_TOKENS = 2_000;

    private final DocLlmClient client;
    private final Consumer<DocLlmClient.Usage> usageSink;

    /**
     * @param client document LLM seam (required)
     * @param usageSink receives every call's {@link DocLlmClient.Usage} (may be null)
     */
    public LlmProjectTypeIndependenceJudge(DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) {
        this.client = Objects.requireNonNull(client, "client");
        this.usageSink = usageSink;
    }

    @Override
    public String id() {
        return "llm-project-type-independence";
    }

    @Override
    public IndependenceResult judge(IndependenceRequest request) {
        if (request.candidates().isEmpty()) {
            return new IndependenceResult(List.of());
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM, userPrompt(request.candidates()), MAX_COMPLETION_TOKENS);
            if (usageSink != null) {
                usageSink.accept(completion.usage());
            }
            return interpret(completion.content(), request.candidates());
        } catch (RuntimeException e) {
            if (usageSink != null) {
                usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            }
            System.err.println("[doc-induce] project-type independence judgement failed: "
                    + e.getMessage());
            return new FixtureProjectTypeIndependenceJudge().judge(request);
        }
    }

    /**
     * Interprets the model reply: only judgements for requested candidates are kept,
     * a candidate the model omitted is judged not independent, and both flags are
     * taken from the reply verbatim.
     */
    static IndependenceResult interpret(String content, List<Candidate> candidates) {
        Map<String, JsonNode> byType = new LinkedHashMap<>();
        JsonNode judgementsNode;
        try {
            judgementsNode = DocLlmJson.object(content).get("judgements");
        } catch (DocLlmException e) {
            judgementsNode = null;
        }
        if (judgementsNode != null && judgementsNode.isArray()) {
            for (JsonNode judgement : judgementsNode) {
                if (!judgement.isObject()) {
                    continue;
                }
                String documentType = textOrNull(judgement, "document_type");
                if (documentType != null) {
                    byType.putIfAbsent(documentType, judgement);
                }
            }
        }
        List<Judgement> judgements = new ArrayList<>();
        for (Candidate candidate : candidates) {
            JsonNode judged = byType.get(candidate.documentType());
            if (judged == null) {
                judgements.add(new Judgement(candidate.documentType(), false,
                        "the model did not judge this document type"));
                continue;
            }
            boolean independent = judged.path("independent").asBoolean(false);
            String reason = textOrNull(judged, "reason");
            judgements.add(new Judgement(candidate.documentType(), independent,
                    reason == null ? "no reason stated" : reason));
        }
        return new IndependenceResult(judgements);
    }

    /** The prompt: each candidate document type and the industries it was seen in. */
    private static String userPrompt(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder(
                "Document types observed across client document sets:\n");
        for (Candidate candidate : candidates) {
            sb.append("- document type: ").append(candidate.documentType())
                    .append(" | industries: ").append(String.join(", ", candidate.industries()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
