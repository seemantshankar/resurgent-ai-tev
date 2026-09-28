package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses main/helper/scratch region JSON from the layout LLM. */
final class RegionLayoutResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegionLayoutResponseParser() {}

    static List<RegionProposal> parse(String completion) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty region-layout completion");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(completion));
            List<RegionProposal> out = new ArrayList<>();
            out.addAll(parseRole(root, "main", "main"));
            out.addAll(parseRole(root, "helper", "helper"));
            out.addAll(parseRole(root, "scratch", "scratch"));
            if (out.isEmpty()) {
                throw new IllegalStateException("region-layout JSON had no regions");
            }
            return List.copyOf(out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable region-layout JSON: " + e.getMessage(), e);
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
