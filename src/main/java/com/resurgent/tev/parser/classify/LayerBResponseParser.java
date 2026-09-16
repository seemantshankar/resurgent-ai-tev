package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses compact Layer B JSON ({@code lines} index triples + optional {@code soft})
 * and resolves indices via {@link LayerBPromptIndex}.
 */
final class LayerBResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerBResponseParser() {}

    static List<LayerBLineJudgment> parse(String completion, LayerBPromptIndex index) {
        return parseDetailed(completion, index).lines();
    }

    /**
     * A malformed individual item costs only that item. Structural failures (no
     * JSON, no {@code lines} array) and responses where every item is unusable
     * still throw, because those are worth a retry; a response with one bad item
     * among many good ones is not.
     */
    static Parsed parseDetailed(String completion, LayerBPromptIndex index) {
        if (completion == null || completion.isBlank()) {
            throw new IllegalStateException("LLM returned an empty Layer B completion");
        }
        Objects.requireNonNull(index, "index");
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
            List<String> dropped = new ArrayList<>();
            for (JsonNode item : linesNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                try {
                    LayerBLineJudgment compact = parseCompactLine(item, index, completion);
                    lines.add(compact != null ? compact : parseLegacyLine(item, completion));
                } catch (IllegalStateException e) {
                    dropped.add(e.getMessage());
                }
            }
            JsonNode softNode = root.get("soft");
            if (softNode != null && softNode.isArray()) {
                for (JsonNode item : softNode) {
                    if (item == null || item.isNull()) {
                        continue;
                    }
                    try {
                        lines.add(parseSoftLeaf(item, index, completion));
                    } catch (IllegalStateException e) {
                        dropped.add(e.getMessage());
                    }
                }
            }
            if (lines.isEmpty() && !dropped.isEmpty()) {
                throw new IllegalStateException(dropped.get(0));
            }
            return new Parsed(List.copyOf(lines), List.copyOf(dropped));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM returned unparseable Layer B JSON: " + e.getMessage(), e);
        }
    }

    /** Usable judgments plus the reason each unusable item was skipped. */
    record Parsed(List<LayerBLineJudgment> lines, List<String> dropped) {}

    /** @deprecated Prefer {@link #parse(String, LayerBPromptIndex)}. */
    static List<LayerBLineJudgment> parse(String completion) {
        throw new IllegalStateException(
                "Layer B parse requires prompt index; use parse(completion, index)");
    }

    private static LayerBLineJudgment parseCompactLine(
            JsonNode item, LayerBPromptIndex index, String completion) {
        Integer cellIndex;
        Integer pathIndex;
        Integer roleCode;
        if (item.isArray() && item.size() >= 3) {
            cellIndex = intValue(item.get(0));
            pathIndex = intValue(item.get(1));
            roleCode = intValue(item.get(2));
        } else if (item.isObject()
                && (item.has("c") || item.has("cellIndex"))
                && (item.has("p") || item.has("pathIndex"))) {
            cellIndex = intValue(item.get("c"));
            if (cellIndex == null) {
                cellIndex = intValue(item.get("cellIndex"));
            }
            pathIndex = intValue(item.get("p"));
            if (pathIndex == null) {
                pathIndex = intValue(item.get("pathIndex"));
            }
            roleCode = intValue(item.get("r"));
            if (roleCode == null) {
                roleCode = intValue(item.get("roleCode"));
            }
            if (roleCode == null) {
                String role = normalizeRole(text(item, "amountRole", "role"));
                roleCode = roleCode(role);
            }
        } else {
            return null;
        }
        if (cellIndex == null || pathIndex == null || roleCode == null) {
            return null;
        }
        String role = roleFromCode(roleCode);
        if (!AmountRole.isKnown(role)) {
            throw new IllegalStateException(
                    "invalid Layer B roleCode=" + roleCode + " snippet=" + snippet(completion));
        }
        LayerBPromptIndex.AmountRow amount = index.amount(cellIndex);
        NomenclatureNode pathNode = index.path(pathIndex);
        String verbatim = amount.label().isBlank() ? amount.coord() : amount.label();
        return new LayerBLineJudgment(
                amount.coord(),
                verbatim,
                pathNode.path(),
                role,
                List.of(),
                null,
                parsePeers(item, completion));
    }

    private static LayerBLineJudgment parseSoftLeaf(
            JsonNode item, LayerBPromptIndex index, String completion) {
        Integer cellIndex = intValue(item.get("c"));
        if (cellIndex == null) {
            cellIndex = intValue(item.get("cellIndex"));
        }
        Integer parentIndex = intValue(item.get("pp"));
        if (parentIndex == null) {
            parentIndex = intValue(item.get("parent"));
        }
        if (parentIndex == null) {
            parentIndex = intValue(item.get("parentPathIndex"));
        }
        String name = text(item, "n", "name", "leafName");
        Integer roleCode = intValue(item.get("r"));
        if (roleCode == null) {
            roleCode = intValue(item.get("roleCode"));
        }
        String role = roleCode != null
                ? roleFromCode(roleCode)
                : normalizeRole(text(item, "amountRole", "role"));
        List<String> aliases = stringList(item, "a");
        if (aliases.isEmpty()) {
            aliases = stringList(item, "aliases");
        }
        if (cellIndex == null || parentIndex == null || name == null || name.isBlank()
                || !AmountRole.isKnown(role)) {
            throw new IllegalStateException(
                    "invalid Layer B soft leaf c=" + cellIndex
                            + " pp=" + parentIndex
                            + " n=" + name
                            + " snippet=" + snippet(completion));
        }
        LayerBPromptIndex.AmountRow amount = index.amount(cellIndex);
        NomenclatureNode parent = index.path(parentIndex);
        // A soft leaf proposed under an existing leaf means the model wanted finer
        // granularity than the ontology offers. Bind to the leaf rather than lose
        // the line: the leaf is the join key, and the invented name adds no rollup.
        String path = parent.leaf()
                ? parent.path()
                : parent.path() + " > " + name.trim();
        List<String> leafAliases = parent.leaf() ? List.of() : aliases;
        String verbatim = amount.label().isBlank() ? name.trim() : amount.label();
        return new LayerBLineJudgment(
                amount.coord(),
                verbatim,
                path,
                role,
                leafAliases,
                number(item, "confidence"),
                parsePeers(item, completion));
    }

    private static LayerBLineJudgment parseLegacyLine(JsonNode item, String completion) {
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
        return new LayerBLineJudgment(
                coord, verbatim, path, role, aliases, confidence, parsePeers(item, completion));
    }

    private static List<LinePeerRef> parsePeers(JsonNode item, String completion) {
        JsonNode peersNode = item.get("peers");
        if (peersNode == null || !peersNode.isArray()) {
            return List.of();
        }
        List<LinePeerRef> peers = new ArrayList<>();
        for (JsonNode peer : peersNode) {
            if (peer == null || peer.isNull()) {
                continue;
            }
            String coord;
            String reason;
            if (peer.isTextual()) {
                coord = peer.asText();
                reason = PeerReason.ANTI_DOUBLE_COUNT;
            } else {
                coord = text(peer, "coord", "cell", "peerCoord", "peer_coord");
                reason = text(peer, "reason", "peerReason", "peer_reason");
                if (reason == null) {
                    reason = PeerReason.ANTI_DOUBLE_COUNT;
                }
            }
            if (coord == null || coord.isBlank()) {
                continue;
            }
            if (!PeerReason.isKnown(reason)) {
                throw new IllegalStateException(
                        "invalid peer reason '" + reason + "' snippet=" + snippet(completion));
            }
            peers.add(new LinePeerRef(coord, reason));
        }
        return List.copyOf(peers);
    }

    static String roleFromCode(int code) {
        return switch (code) {
            case 0 -> AmountRole.ADD;
            case 1 -> AmountRole.DEDUCT;
            case 2 -> AmountRole.TOTAL;
            case 3 -> AmountRole.HELPER;
            default -> null;
        };
    }

    static Integer roleCode(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case AmountRole.ADD -> 0;
            case AmountRole.DEDUCT -> 1;
            case AmountRole.TOTAL -> 2;
            case AmountRole.HELPER -> 3;
            default -> null;
        };
    }

    private static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "add", "addition", "credit_add", "0" -> AmountRole.ADD;
            case "deduct", "deduction", "less", "subtract", "1" -> AmountRole.DEDUCT;
            case "total", "subtotal", "sum", "2" -> AmountRole.TOTAL;
            case "helper", "memo", "info", "3" -> AmountRole.HELPER;
            default -> key;
        };
    }

    private static Integer intValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        return null;
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
