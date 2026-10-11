package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses batched main/helper/scratch regions JSON from the layout LLM. */
final class RegionLayoutBatchResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegionLayoutBatchResponseParser() {}

    static Map<String, List<RegionProposal>> parse(String completion) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty region-layout batch completion");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(completion));
            Map<String, List<RegionProposal>> out = new HashMap<>();

            if (root.isObject()) {
                // Iterate over top-level keys (sheet names)
                root.fields().forEachRemaining(entry -> {
                    String sheetName = entry.getKey();
                    JsonNode sheetRoot = entry.getValue();
                    List<RegionProposal> proposals = new ArrayList<>();
                    proposals.addAll(parseRole(sheetRoot, "main", "main"));
                    proposals.addAll(parseRole(sheetRoot, "helper", "helper"));
                    proposals.addAll(parseRole(sheetRoot, "scratch", "scratch"));
                    if (!proposals.isEmpty()) {
                        out.put(sheetName, proposals);
                    }
                });
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable region-layout batch JSON: " + e.getMessage(), e);
        }
    }

    private static List<RegionProposal> parseRole(JsonNode root, String field, String role) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<RegionProposal> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item == null || item.isNull()) {
                continue;
            }
            String bbox = text(item, "bbox");
            if (bbox == null) {
                continue;
            }
            out.add(new RegionProposal(
                    role,
                    bbox.trim().toUpperCase(Locale.ROOT),
                    text(item, "label"),
                    text(item, "why")));
        }
        return out;
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }
}
