package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Parses one Layer B JSON object into row and cell assignments. */
final class LayerBResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerBResponseParser() {}

    static List<LayerBAssignment> parse(String completion) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty Layer B completion");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(completion));
            List<LayerBAssignment> assignments = new ArrayList<>();
            read(root.get("rows"), true, assignments);
            read(root.get("cells"), false, assignments);
            return List.copyOf(assignments);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable Layer B JSON: " + e.getMessage(), e);
        }
    }

    private static void read(JsonNode array, boolean rowEntry, List<LayerBAssignment> assignments) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String root = text(item, "root", "pathRoot", "path_root");
            String path = text(item, "path");
            if (!LayerBBinder.allowed(root, path)) {
                continue;
            }
            if (rowEntry) {
                JsonNode row = item.get("row");
                if (row != null && row.canConvertToInt()) {
                    assignments.add(new LayerBAssignment(row.intValue(), null, root, path));
                }
            } else {
                String coord = text(item, "coord", "cell");
                if (coord != null) {
                    assignments.add(new LayerBAssignment(null, coord, root, path));
                }
            }
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }
}
