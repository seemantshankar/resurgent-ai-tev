package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves a schema leaf's {@link ValueKind} for extract mapping. Explicit pack
 * {@code value_kind} wins; otherwise JSON Schema {@code type} fills the gap
 * (issue #198). Numbers default to quantity. Money is never inferred from
 * field names or LlamaExtract response meta.
 */
public final class SchemaLeafKinds {

    private SchemaLeafKinds() {}

    /**
     * Kind for {@code properties[key]}, or {@link ValueKind#TEXT} when the leaf
     * is absent.
     */
    public static ValueKind of(JsonNode props, String key) {
        if (props == null || key == null || !props.has(key)) {
            return ValueKind.TEXT;
        }
        return of(props.get(key));
    }

    /**
     * Kind for one schema leaf object.
     */
    public static ValueKind of(JsonNode leaf) {
        if (leaf == null || !leaf.isObject()) {
            return ValueKind.TEXT;
        }
        String declared = leaf.path("value_kind").asText(null);
        if (declared != null && !declared.isBlank()) {
            try {
                return ValueKind.fromWire(declared);
            } catch (IllegalArgumentException ignored) {
                // fall through to type inference
            }
        }
        if (moneyCue(leaf)) {
            return ValueKind.MONEY;
        }
        String type = jsonType(leaf);
        if ("boolean".equals(type)) {
            return ValueKind.BOOLEAN;
        }
        if (isDateType(leaf, type)) {
            return ValueKind.DATE;
        }
        if (isNumberType(type)) {
            return ValueKind.QUANTITY;
        }
        return ValueKind.TEXT;
    }

    private static String jsonType(JsonNode leaf) {
        JsonNode type = leaf.get("type");
        if (type == null || type.isNull()) {
            return null;
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode entry : type) {
                if (!entry.isTextual()) {
                    continue;
                }
                String value = entry.asText();
                if ("null".equals(value)) {
                    continue;
                }
                return value;
            }
        }
        return null;
    }

    private static boolean moneyCue(JsonNode leaf) {
        return flag(leaf, "currency") || flag(leaf, "scale") || flag(leaf, "money");
    }

    private static boolean flag(JsonNode leaf, String name) {
        JsonNode node = leaf.get(name);
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static boolean isNumberType(String type) {
        return "number".equals(type) || "integer".equals(type);
    }

    private static boolean isDateType(JsonNode leaf, String type) {
        if ("date".equals(type) || "date-time".equals(type)) {
            return true;
        }
        if (type != null && !"string".equals(type)) {
            return false;
        }
        String format = leaf.path("format").asText("");
        return "date".equals(format) || "date-time".equals(format);
    }
}
