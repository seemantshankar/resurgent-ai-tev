package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LLM label normalisation of proposed clause types (M3-09): one call per document type
 * asks which proposed labels mean the same clause, and trusts the model only for the
 * grouping, never for clause text. Nothing is invented — a member code the model names
 * that was not proposed is dropped, and a proposed code the model omits stays its own
 * group so its evidence still counts towards consensus.
 */
public final class LlmClauseLabelNormalizer implements ClauseLabelNormalizer {

    /** Stage id for {@code llm_usage} of the induction-family promotion step. */
    public static final String STAGE = DocInduceClausesRunner.STAGE;

    private static final String SYSTEM = """
            You normalise clause-type labels proposed by an earlier model call for a bank's
            technical due diligence. Group labels that name the SAME clause concept, even when
            worded differently ("Termination by Procurer" and "Procurer's right to terminate"
            are one type). Clause types that are unrelated each stay their own group.
            Reply with a single JSON object:
            {"groups": [{"key": "<snake_case canonical key>", "label": "<canonical Title>", \
            "parent_family": "<snake_case family or null>", "aliases": ["<other phrasings>"], \
            "members": ["<code of each proposed label in this group>"]}]}
            Use the proposed codes verbatim in "members". Never invent members that were not
            proposed. Every proposed code appears in exactly one group.""";

    private static final int MAX_COMPLETION_TOKENS = 2000;

    private final DocLlmClient client;
    private final Consumer<DocLlmClient.Usage> usageSink;

    /**
     * @param client document LLM seam (required)
     * @param usageSink receives every call's {@link DocLlmClient.Usage} (may be null)
     */
    public LlmClauseLabelNormalizer(DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.usageSink = usageSink;
    }

    @Override
    public String id() {
        return "llm-label-normalisation";
    }

    @Override
    public NormalizationResult normalize(NormalizationRequest request) {
        if (request.labels().isEmpty()) {
            return new NormalizationResult(List.of());
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM, userPrompt(request.labels()), MAX_COMPLETION_TOKENS);
            if (usageSink != null) {
                usageSink.accept(completion.usage());
            }
            return interpret(completion.content(), request.labels());
        } catch (RuntimeException e) {
            if (usageSink != null) {
                usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            }
            System.err.println("[doc-induce] clause label normalisation failed: " + e.getMessage());
            return new FixtureClauseLabelNormalizer().normalize(request);
        }
    }

    /**
     * Interprets the model reply: groups keep only proposed member codes, duplicates are
     * merged, and any proposed code the model left out becomes its own group.
     */
    static NormalizationResult interpret(String content, List<ProposedLabel> labels) {
        Map<String, ProposedLabel> byCode = new LinkedHashMap<>();
        for (ProposedLabel label : labels) {
            byCode.putIfAbsent(label.code(), label);
        }
        List<LabelGroup> groups = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        JsonNode groupsNode;
        try {
            groupsNode = DocLlmJson.object(content).get("groups");
        } catch (DocLlmException e) {
            groupsNode = null;
        }
        if (groupsNode != null && groupsNode.isArray()) {
            for (JsonNode group : groupsNode) {
                if (!group.isObject()) {
                    continue;
                }
                String key = textOrNull(group, "key");
                String label = textOrNull(group, "label");
                if (key == null || label == null) {
                    continue;
                }
                List<String> members = new ArrayList<>();
                for (JsonNode member : arrayOrEmpty(group.get("members"))) {
                    String code = member.asText("").strip();
                    if (!code.isEmpty() && byCode.containsKey(code) && claimed.add(code)) {
                        members.add(code);
                    }
                }
                if (members.isEmpty()) {
                    continue;
                }
                groups.add(new LabelGroup(
                        key, label, textOrNull(group, "parent_family"),
                        strings(arrayOrEmpty(group.get("aliases"))), members));
            }
        }
        // a proposed code the model did not place stays on its own so its documents
        // still count — promotion is decided by consensus, not by model omission
        for (ProposedLabel label : byCode.values()) {
            if (claimed.contains(label.code())) {
                continue;
            }
            groups.add(new LabelGroup(
                    label.code(), label.label(), label.parentFamily(), List.of(),
                    List.of(label.code())));
        }
        return new NormalizationResult(groups);
    }

    /** The prompt: the proposed codes and labels, in proposal order. */
    private static String userPrompt(List<ProposedLabel> labels) {
        StringBuilder sb = new StringBuilder("Proposed clause types:\n");
        for (ProposedLabel label : labels) {
            sb.append("- code: ").append(label.code()).append(" | label: ").append(label.label());
            if (label.parentFamily() != null) {
                sb.append(" | parent family: ").append(label.parentFamily());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static JsonNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray()
                ? node
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String text = node.asText("").strip();
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
        }
        return List.copyOf(out);
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
