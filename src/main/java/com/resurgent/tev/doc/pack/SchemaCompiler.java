package com.resurgent.tev.doc.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.extract.ValueKind;
import java.util.Map;

/**
 * Builds the JSON Schema fragment sent to {@code ExtractClient} from a pack.
 * Object of field keys; each leaf carries {@code value_kind}, {@code required},
 * and a short {@code prompt_hint}. Multi-value leaves are arrays. Quotation packs
 * add a {@code line_items} array of row objects from pack {@code tables}.
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

        for (PackField field : pack.fields()) {
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

        // Quotation-style packs: row maps under tables[].fields become line_items[].
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name == null || !"line_items".equals(String.valueOf(name))) {
                continue;
            }
            ObjectNode lineItems = props.putObject("line_items");
            lineItems.put("type", "array");
            ObjectNode items = lineItems.putObject("items");
            items.put("type", "object");
            ObjectNode itemProps = items.putObject("properties");
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
                    }
                }
            }
            // line_items is structurally required when the pack declares the table.
            required.add("line_items");
        }

        return root;
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
