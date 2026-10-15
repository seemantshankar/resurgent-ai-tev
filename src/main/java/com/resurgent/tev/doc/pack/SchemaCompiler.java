package com.resurgent.tev.doc.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.extract.ValueKind;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds the JSON Schema fragment sent to {@code ExtractClient} from a pack.
 * Object of field keys; each leaf carries {@code value_kind}, {@code required},
 * and a short {@code prompt_hint}. Multi-value leaves are arrays. Pack
 * {@code tables} are compiled as arrays of row objects (one property per column);
 * a table's {@code period_key} column is marked {@code "period": true} so row
 * facts can carry the period they were read for. A field whose key is a table
 * column compiles only inside its table — never as a root leaf.
 */
public final class SchemaCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaCompiler() {}

    /**
     * Compiles a pack into a JSON Schema object fragment.
     *
     * @param pack validated pack (required)
     * @return schema node suitable for extract adapters
     */
    public static JsonNode compile(DocTypePack pack) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        Set<String> tableOwned = tableOwnedKeys(pack);

        for (PackField field : pack.fields()) {
            if (tableOwned.contains(field.key())) {
                // Figures that repeat per row (revenue, PAT, net worth, ...) are
                // extracted as table columns, not as single root-level values.
                continue;
            }
            ObjectNode leaf = props.putObject(field.key());
            putValueKind(leaf, field.valueKind());
            leaf.put("description", field.promptHint() != null ? field.promptHint() : field.path());
            leaf.put("ontology_path", field.path());
            if (!field.synonyms().isEmpty()) {
                ArrayNode syn = leaf.putArray("synonyms");
                field.synonyms().forEach(syn::add);
            }
            if (field.slots().contains("currency")) {
                leaf.put("currency", true);
            }
            if (field.slots().contains("scale")) {
                leaf.put("scale", true);
            }
            if (field.slots().contains("unit")) {
                leaf.put("unit", true);
            }
            if (field.required()) {
                required.add(field.key());
            }
        }

        // Row tables (quotation line_items, audited financial years, ...): row maps
        // under tables[].fields become an array of row objects.
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name == null) {
                continue;
            }
            String tableName = String.valueOf(name);
            ObjectNode rows = props.putObject(tableName);
            rows.put("type", "array");
            ObjectNode items = rows.putObject("items");
            items.put("type", "object");
            ObjectNode itemProps = items.putObject("properties");
            Object periodKey = table.get("period_key");
            String periodColumn = periodKey == null ? null : String.valueOf(periodKey);
            Object fieldsObj = table.get("fields");
            if (fieldsObj instanceof java.util.List<?> list) {
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> f) {
                        String key = String.valueOf(f.get("key"));
                        ObjectNode col = itemProps.putObject(key);
                        Object kind = f.get("value_kind");
                        if (kind != null) {
                            try {
                                putValueKind(col, ValueKind.fromWire(String.valueOf(kind)));
                            } catch (IllegalArgumentException ignored) {
                                col.put("type", "string");
                            }
                        } else {
                            col.put("type", "string");
                        }
                        if (f.get("prompt_hint") != null) {
                            col.put("description", String.valueOf(f.get("prompt_hint")));
                        }
                        if (key.equals(periodColumn)) {
                            col.put("period", true);
                        }
                    }
                }
            }
            // The table array is structurally required when the pack declares the table.
            required.add(tableName);
        }

        return root;
    }

    /** Field keys owned by a declared table — compiled as columns, never root leaves. */
    private static Set<String> tableOwnedKeys(DocTypePack pack) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> table : pack.tables()) {
            Object fieldsObj = table.get("fields");
            if (fieldsObj instanceof java.util.List<?> list) {
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> f && f.get("key") != null) {
                        keys.add(String.valueOf(f.get("key")));
                    }
                }
            }
        }
        return keys;
    }

    private static void putValueKind(ObjectNode leaf, ValueKind kind) {
        switch (kind) {
            case MONEY, QUANTITY, RATE, PERCENT, DURATION -> leaf.put("type", "number");
            case DATE -> leaf.put("type", "string");
            case BOOLEAN -> leaf.put("type", "boolean");
            case LIST -> leaf.put("type", "array");
            case TEXT, ENUM -> leaf.put("type", "string");
            default -> leaf.put("type", "string");
        }
        leaf.put("value_kind", kind.wireName());
    }
}
