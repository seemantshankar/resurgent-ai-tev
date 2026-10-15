package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One model call per document reads its clause candidates (M3-08): for each segment it
 * confirms a pack clause type (Pass A), or proposes a type, and returns the one-line
 * summary, lender-risk importance and stated key numbers (Pass B inputs). The reply is
 * trusted only for these fields — verbatim text always comes from the stored document.
 * Every call — success or failure — is reported to the stage usage sink.
 */
public final class LlmClauseReading {

    private static final String SYSTEM = """
            You read contract clauses from ONE document for a bank's technical due diligence.
            For each numbered clause candidate, reply with a single JSON object:
            {"clauses": [{"index": <n>, "type": "<key of an allowed clause type, or null>", \
            "proposed_type": {"code": "<snake_case>", "label": "<Title>", "parent_family": "<snake_case>"} or null, \
            "summary": "<one line of plain English>", "importance": "high" | "medium" | "low", \
            "slots": [{"key": "notice_period | penalty | tenure | ...", "value": "<value exactly as written>"}]}]}
            Use "type" only when the clause truly fits that allowed type; otherwise set it null
            and propose a new type. Never invent clause text. importance is the risk to a
            lender: high for money obligations and termination rights, medium for operational
            duties, low for boilerplate. slots capture only numbers the clause itself states
            (notice periods, penalties, tenure) with their value as written.
            Return one entry for every candidate, in the given order.""";

    private static final int MAX_COMPLETION_TOKENS = 4000;
    private static final int MAX_USER_CHARS = 12_000;
    private static final int MAX_SLOTS = 12;

    private LlmClauseReading() {}

    /**
     * One clause as the model read it.
     *
     * @param typeKey an allowed pack clause type key, or null when none fits
     * @param proposedCode proposed snake_case type code, or null when the model matched a type
     * @param proposedLabel proposed human label, or null
     * @param proposedFamily proposed parent family, or null
     * @param summary one-line English summary, or null
     * @param importance {@code high} | {@code medium} | {@code low}, or null
     * @param slots stated key numbers (key + value as written)
     */
    public record Reading(
            String typeKey,
            String proposedCode,
            String proposedLabel,
            String proposedFamily,
            String summary,
            String importance,
            List<SlotValue> slots) {}

    /** One stated key number, as written in the clause. */
    public record SlotValue(String key, String value) {}

    /**
     * Reads every clause candidate of one document in a single call.
     *
     * @param client document LLM seam ({@code null} keeps the offline path: no call)
     * @param pack the version's pack (its clause types are the allowed set)
     * @param segments the segmented clauses, in reading order
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return readings keyed by 1-based segment index; empty when offline or unusable
     */
    public static Map<Integer, Reading> read(DocLlmClient client, DocTypePack pack,
            List<ClauseSegmenter.Segment> segments, Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null || segments.isEmpty()) {
            return Map.of();
        }
        try {
            DocLlmClient.Completion completion = client.complete(
                    SYSTEM, userPrompt(pack, segments), MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content(), segments.size());
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] clause LLM failed: " + e.getMessage());
            return Map.of();
        }
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static Map<Integer, Reading> interpret(String content, int segmentCount) {
        JsonNode clauses;
        try {
            JsonNode root = DocLlmJson.object(content);
            clauses = root.get("clauses");
        } catch (DocLlmException e) {
            return Map.of();
        }
        if (clauses == null || !clauses.isArray()) {
            return Map.of();
        }
        Map<Integer, Reading> readings = new LinkedHashMap<>();
        int position = 0;
        for (JsonNode c : clauses) {
            position++;
            int index = c.hasNonNull("index") ? c.get("index").asInt() : position;
            if (index < 1 || index > segmentCount || readings.containsKey(index)) {
                continue;
            }
            String typeKey = c.hasNonNull("type") ? c.get("type").asText().strip() : null;
            if (typeKey != null && typeKey.isEmpty()) {
                typeKey = null;
            }
            String proposedCode = null;
            String proposedLabel = null;
            String proposedFamily = null;
            JsonNode proposed = c.get("proposed_type");
            if (proposed != null && proposed.isObject()) {
                proposedLabel = textOrNull(proposed, "label");
                proposedFamily = textOrNull(proposed, "parent_family");
                proposedCode = textOrNull(proposed, "code");
                if (proposedCode == null && proposedLabel != null) {
                    proposedCode = ClauseSlots.normalizeKey(proposedLabel);
                }
            }
            readings.put(index, new Reading(
                    typeKey,
                    proposedCode,
                    proposedLabel,
                    proposedFamily,
                    textOrNull(c, "summary"),
                    importance(c.get("importance")),
                    slots(c.get("slots"))));
        }
        return readings;
    }

    private static List<SlotValue> slots(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SlotValue> slots = new ArrayList<>();
        for (JsonNode s : node) {
            if (slots.size() >= MAX_SLOTS) {
                break;
            }
            if (!s.isObject()) {
                continue;
            }
            String key = textOrNull(s, "key");
            String value = textOrNull(s, "value");
            if (key != null && value != null) {
                slots.add(new SlotValue(key, value));
            }
        }
        return List.copyOf(slots);
    }

    private static String importance(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "high", "medium", "low" -> value;
            default -> null;
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    /** The prompt: allowed types (with aliases) first, then the numbered candidates. */
    private static String userPrompt(DocTypePack pack, List<ClauseSegmenter.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Allowed clause types:\n");
        if (pack.clauseTypes().isEmpty()) {
            sb.append("- (none declared)\n");
        }
        for (PackClauseType type : pack.clauseTypes()) {
            sb.append("- ").append(type.key()).append(": ").append(type.label());
            if (type.parentFamily() != null) {
                sb.append(" (parent family: ").append(type.parentFamily()).append(')');
            }
            if (!type.aliases().isEmpty()) {
                sb.append(" (aliases: ").append(String.join(", ", type.aliases())).append(')');
            }
            sb.append('\n');
        }
        sb.append("\nClause candidates:\n");
        for (int i = 0; i < segments.size(); i++) {
            ClauseSegmenter.Segment segment = segments.get(i);
            if (sb.length() >= MAX_USER_CHARS) {
                break;
            }
            sb.append("### ").append(i + 1).append(" [page ").append(segment.pageStart()).append("]\n")
                    .append(segment.text()).append('\n');
        }
        return sb.length() <= MAX_USER_CHARS ? sb.toString() : sb.substring(0, MAX_USER_CHARS);
    }
}
