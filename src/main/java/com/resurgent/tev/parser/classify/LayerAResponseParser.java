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
            String family = normalizeFamily(text(root, "scheduleFamily", "schedule_family"));
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
            if (family == null || family.isBlank()
                    || !Triage.isKnown(triage)
                    || !Relevance.isKnown(relevance)) {
                throw new IllegalStateException(
                        "invalid Layer A JSON fields family=" + family
                                + " triage=" + triage
                                + " relevance=" + relevance
                                + " snippet=" + snippet(completion));
            }
            List<ProjectFactJudgment> facts = parseFacts(root);
            return new LayerAJudgment(family, triage, relevance, rowLabels, columnHeaders, head, facts);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM returned unparseable Layer A JSON: " + e.getMessage(), e);
        }
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

    private static List<ProjectFactJudgment> parseFacts(JsonNode root) {
        JsonNode node = root.get("facts");
        if (node == null) {
            node = root.get("projectFacts");
        }
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ProjectFactJudgment> facts = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String coord = text(item, "coord", "cell", "address");
            String verbatim = text(item, "verbatim", "label", "text", "value");
            String path = text(item, "factPath", "fact_path", "path");
            if (verbatim == null || verbatim.isBlank() || path == null || path.isBlank()) {
                continue;
            }
            facts.add(new ProjectFactJudgment(coord, verbatim, path));
        }
        return List.copyOf(facts);
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

    static String normalizeFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = snake(raw);
        String family = switch (key) {
            case "capex", "capexdetail", "capital_cost", "project_cost", "assets",
                    "capex_detail" -> ScheduleFamily.CAPEX_DETAIL;
            case "mof", "meansoffinance", "means_of_finance" -> ScheduleFamily.MEANS_OF_FINANCE;
            case "pnl", "p_l", "pl", "profitandloss", "profit_and_loss", "profitloss" ->
                    ScheduleFamily.PROFIT_AND_LOSS;
            case "bs", "balancesheet", "balance_sheet" -> ScheduleFamily.BALANCE_SHEET;
            case "cf", "cashflow", "cash_flow" -> ScheduleFamily.CASH_FLOW;
            case "assumption", "assumptions" -> ScheduleFamily.ASSUMPTIONS;
            case "projectsummary", "project_summary", "at_glance", "atglance" ->
                    ScheduleFamily.PROJECT_SUMMARY;
            default -> null;
        };
        return ScheduleFamily.isKnown(family) ? family : null;
    }

    static String normalizeTriage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = snake(raw);
        return switch (key) {
            case "main", "primary_schedule", "keep", "main_schedule" -> Triage.MAIN;
            case "scratch", "scratchpad", "working", "working_paper", "workings", "calc" ->
                    Triage.SCRATCH;
            case "orphan", "orphaned", "unattached" -> Triage.ORPHAN;
            default -> key;
        };
    }

    static String normalizeRelevance(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = snake(raw);
        return switch (key) {
            case "primary", "core", "main" -> Relevance.PRIMARY;
            case "supporting", "support", "secondary", "ancillary", "detail" ->
                    Relevance.SUPPORTING;
            case "noise", "irrelevant" -> Relevance.NOISE;
            default -> key;
        };
    }

    private static String snippet(String completion) {
        String trimmed = completion == null ? "" : completion.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private static String snake(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replace('-', '_')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}
