package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses a Layer B JSON object ({@code lines: [...]}) from an LLM completion. */
final class LayerBResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerBResponseParser() {}

    static List<LayerBLineJudgment> parse(String completion) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty Layer B completion");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(completion));
            JsonNode linesNode = root.get("lines");
            if (linesNode == null || linesNode.isNull()) {
                linesNode = root.get("bindings");
            }
            if (linesNode == null || linesNode.isNull()) {
                throw new IllegalStateException(
                        "Layer B JSON missing lines array snippet=" + snippet(completion));
            }
            if (!linesNode.isArray()) {
                throw new IllegalStateException(
                        "Layer B lines must be an array snippet=" + snippet(completion));
            }
            List<LayerBLineJudgment> lines = new ArrayList<>();
            for (JsonNode item : linesNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                String coord = text(item, "coord", "cell", "address");
                String verbatim = text(item, "verbatim", "label", "text");
                String path = text(item, "path", "nomenclaturePath", "nomenclature_path");
                String role = normalizeRole(text(item, "amountRole", "amount_role", "role"));
                List<String> aliases = stringList(item, "aliases");
                Double confidence = number(item, "confidence");
                if (coord == null || coord.isBlank()
                        || verbatim == null || verbatim.isBlank()
                        || path == null || path.isBlank()
                        || !AmountRole.isKnown(role)) {
                    throw new IllegalStateException(
                            "invalid Layer B line coord=" + coord
                                    + " path=" + path
                                    + " role=" + role
                                    + " snippet=" + snippet(completion));
                }
                lines.add(new LayerBLineJudgment(coord, verbatim, path, role, aliases, confidence));
            }
            return List.copyOf(lines);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable Layer B JSON: " + e.getMessage(), e);
        }
    }

    private static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "add", "addition", "credit_add" -> AmountRole.ADD;
            case "deduct", "deduction", "less", "subtract" -> AmountRole.DEDUCT;
            case "total", "subtotal", "sum" -> AmountRole.TOTAL;
            case "helper", "memo", "info" -> AmountRole.HELPER;
            default -> key;
        };
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

    private static List<String> stringList(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isArray()) {
            return List.of();
        }
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

    private static Double number(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asDouble();
    }

    private static String snippet(String completion) {
        String trimmed = completion == null ? "" : completion.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }
}
