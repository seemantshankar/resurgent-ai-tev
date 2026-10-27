package com.resurgent.tev.doc.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.extract.ValueKind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the JSON Schema fragment sent to {@code ExtractClient} from a pack.
 * Object of field keys; each leaf carries {@code value_kind}, {@code required},
 * and a short {@code prompt_hint}. Multi-value leaves are arrays. Pack
 * {@code tables} are compiled as arrays of row objects (one property per column).
 * A table's {@code key_column} is marked {@code "line_key": true}, optional
 * {@code entity_key} and {@code site_key} columns are marked {@code "entity"} and
 * {@code "site"}, and {@code period_key} is marked {@code "period": true}, so a
 * record schedule or a period matrix can emit one fact per value cell. A field
 * whose key is a column of such a table compiles only inside its table — never
 * as a root leaf.
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
                // Fields that repeat per row (audited headline figures, ...) are
                // extracted as table columns, not as single root-level values.
                continue;
            }
            putFieldProps(props, field);
            if (field.required()) {
                required.add(field.key());
            }
        }

        // Row tables (quotation line_items, audited headline figures, ...): row maps
        // under tables[].fields become an array of row objects.
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name == null) {
                continue;
            }
            putTableRows(props, table);
            // A table array is structurally required when the pack declares the table.
            required.add(String.valueOf(name));
        }

        return root;
    }

    /**
     * Compiles a topic-scoped JSON Schema for one chapter of a prose document: only the
     * named pack field keys and the named tables' row lists, with nothing required —
     * chapter facts are best-effort per chapter (M3-06).
     *
     * @param pack validated pack (required)
     * @param fieldKeys pack field keys and table names the chapter takes (required)
     * @return schema node suitable for extract adapters
     */
    public static JsonNode compileForFields(DocTypePack pack, List<String> fieldKeys) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        Set<String> wanted = new HashSet<>(fieldKeys);

        for (PackField field : pack.fields()) {
            if (wanted.contains(field.key())) {
                putFieldProps(props, field);
            }
        }
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name != null && wanted.contains(String.valueOf(name))) {
                putTableRows(props, table);
            }
        }
        return root;
    }

    /** Field keys owned by a declared table — compiled as columns, never root leaves. */
    private static Set<String> tableOwnedKeys(DocTypePack pack) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> table : pack.tables()) {
            // Period matrices and record schedules repeat their columns per row.
            // A bare line-item table (no key and no period) keeps its columns off
            // this set so a shared scalar can still compile at the root.
            if (table.get("period_key") == null && table.get("key_column") == null) {
                continue;
            }
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

    /** Leaf schema for one pack field, shared by {@link #compile} and {@link #compileForFields}. */
    private static void putFieldProps(ObjectNode parent, PackField field) {
        if (field.valueKind() == null) {
            throw new IllegalArgumentException(field.key() + ": missing value_kind");
        }
        ObjectNode leaf = parent.putObject(field.key());
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
    }

    /** Array-of-row-objects schema for one pack table under its name key. */
    private static void putTableRows(ObjectNode parent, Map<String, Object> table) {
        ObjectNode tableNode = parent.putObject(String.valueOf(table.get("name")));
        tableNode.put("type", "array");
        ObjectNode items = tableNode.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        String periodColumn = columnRole(table, "period_key");
        String keyColumn = columnRole(table, "key_column");
        String entityColumn = columnRole(table, "entity_key");
        String siteColumn = columnRole(table, "site_key");
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
                    if (key.equals(keyColumn)) {
                        col.put("line_key", true);
                    }
                    if (key.equals(entityColumn)) {
                        col.put("entity", true);
                    }
                    if (key.equals(siteColumn)) {
                        col.put("site", true);
                    }
                }
            }
        }
    }

    /** The column a table role names, or null when the role is absent. */
    private static String columnRole(Map<String, Object> table, String role) {
        Object value = table.get(role);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
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
