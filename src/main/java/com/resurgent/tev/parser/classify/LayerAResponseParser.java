package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses a Layer A JSON object from an LLM completion. */
final class LayerAResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerAResponseParser() {}

    static LayerAJudgment parse(String completion) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty Layer A completion");
        }
        try {
            JsonNode root = MAPPER.readTree(extractJsonObject(completion));
            String family = resolveFamily(
                    text(root, "scheduleFamily", "schedule_family"),
                    text(root, "suggestedFamily", "suggested_family"));
            String triage = normalizeTriage(text(root, "triage"));
            String relevance = normalizeRelevance(text(root, "relevance"));
            if (Triage.isSoft(triage)) {
                relevance = Relevance.NOISE;
            }
            List<String> rowLabels = stringList(root, "rowLabels", "row_labels");
            List<String> columnHeaders = stringList(root, "columnHeaders", "column_headers");
            String head = text(root, "packetDefaultHead", "packet_default_head");
            if (head != null && head.isBlank()) {
                head = null;
            }
            String about = text(root, "about");
            if (family == null || family.isBlank()
                    || !Triage.isKnown(triage)
                    || !Relevance.isKnown(relevance)
                    || about == null || about.isBlank()) {
                throw new IllegalStateException(
                        "invalid Layer A JSON fields family=" + family
                                + " triage=" + triage
                                + " relevance=" + relevance
                                + " about=" + about
                                + " snippet=" + snippet(completion));
            }
            return new LayerAJudgment(
                    family, triage, relevance, rowLabels, columnHeaders, head, about.trim(),
                    statedScale(text(root, "statedScale", "stated_scale")),
                    cellCoord(text(root, "scaleCell", "scale_cell")));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e instanceof IllegalStateException ise
                    ? ise
                    : new IllegalStateException(e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable Layer A JSON: " + e.getMessage(), e);
        }
    }

    /** A scale wire name, or null for anything else (an unknown scale is treated as not stated). */
    static String statedScale(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CellScale.fromWire(value.strip().toLowerCase(java.util.Locale.ROOT)).wireName();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String cellCoord(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    static String extractJsonObject(String completion) {
        String trimmed = completion.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int fence = trimmed.lastIndexOf("```");
            String body = fence > start ? trimmed.substring(start, fence) : trimmed.substring(start);
            trimmed = body.trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM completion did not contain a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private static String resolveFamily(String scheduleFamily, String suggestedFamily) {
        if (scheduleFamily == null || scheduleFamily.isBlank()) {
            return null;
        }
        String family = scheduleFamily.trim().toLowerCase(Locale.ROOT);
        if (ScheduleFamily.NONE.equals(family)) {
            return ScheduleFamily.newFamily(suggestedFamily);
        }
        if (ScheduleFamily.isKnown(family)) {
            return family;
        }
        String admitted = ScheduleFamily.newFamily(family);
        return admitted != null ? admitted : family;
    }

    private static String normalizeTriage(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRelevance(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull()) {
                String value = node.asText();
                if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : node) {
                    if (item != null && !item.isNull()) {
                        String value = item.asText();
                        if (value != null && !value.isBlank()) {
                            values.add(value.trim());
                        }
                    }
                }
                return values;
            }
        }
        return List.of();
    }

    private static String snippet(String completion) {
        String trimmed = completion.trim().replace('\n', ' ');
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160) + "...";
    }
}
