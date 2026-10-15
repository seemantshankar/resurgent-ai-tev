package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * Serializes a {@link DocTypePack} to the YAML shape accepted by {@code PackLoader}.
 * Pure function of the pack: reload → rewrite is byte-identical (quality gate).
 */
public final class PackYamlWriter {

    private static final YAMLFactory FACTORY = new YAMLFactory()
            .configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true)
            .configure(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE, false)
            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
    private static final ObjectMapper MAPPER = new ObjectMapper(FACTORY);

    private PackYamlWriter() {}

    /**
     * Writes the pack as YAML text.
     *
     * @param pack validated pack (required)
     * @return YAML document (no leading {@code ---})
     * @throws IOException if serialization fails
     */
    public static String write(DocTypePack pack) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("code", pack.code());
        root.put("version", pack.version());
        root.put("family", pack.family());
        root.put("file_role", pack.fileRole());

        ArrayNode tags = root.putArray("industry_tags");
        pack.industryTags().forEach(tags::add);

        ObjectNode signals = root.putObject("identity_signals");
        ArrayNode anyOf = signals.putArray("any_of");
        pack.identitySignals().forEach(anyOf::add);

        ArrayNode fields = root.putArray("fields");
        for (PackField f : pack.fields()) {
            ObjectNode n = fields.addObject();
            n.put("key", f.key());
            n.put("path", f.path());
            n.put("path_root", f.pathRootWire());
            n.put("value_kind", f.valueKind().wireName());
            n.put("required", f.required());
            if (!f.slots().isEmpty()) {
                ArrayNode slots = n.putArray("slots");
                f.slots().forEach(slots::add);
            }
            if (f.promptHint() != null && !f.promptHint().isBlank()) {
                n.put("prompt_hint", f.promptHint());
            }
            if (!f.synonyms().isEmpty()) {
                ArrayNode syn = n.putArray("synonyms");
                f.synonyms().forEach(syn::add);
            }
        }

        ArrayNode tables = root.putArray("tables");
        for (Map<String, Object> t : pack.tables()) {
            tables.add(MAPPER.valueToTree(t));
        }

        ArrayNode checklist = root.putArray("checklist");
        pack.checklist().forEach(checklist::add);

        if (pack.declaresClauses()) {
            ObjectNode clauses = root.putObject("clauses");
            clauses.put("open_extraction", pack.clauseOpenExtraction());
            ArrayNode types = clauses.putArray("types");
            for (com.resurgent.tev.doc.pack.PackClauseType t : pack.clauseTypes()) {
                ObjectNode n = types.addObject();
                n.put("key", t.key());
                n.put("label", t.label());
                if (t.parentFamily() != null && !t.parentFamily().isBlank()) {
                    n.put("parent_family", t.parentFamily());
                }
                if (!t.aliases().isEmpty()) {
                    ArrayNode aliases = n.putArray("aliases");
                    t.aliases().forEach(aliases::add);
                }
            }
        }

        if (pack.documentDateKind() != null) {
            ObjectNode date = root.putObject("document_date");
            date.put("kind", pack.documentDateKind());
        }

        ArrayNode reportSections = root.putArray("report_sections");
        pack.reportSections().forEach(reportSections::add);

        StringWriter sw = new StringWriter();
        MAPPER.writeValue(sw, root);
        String out = sw.toString();
        if (!out.endsWith("\n")) {
            out = out + "\n";
        }
        return out;
    }

    /**
     * Writes YAML bytes (UTF-8) for store/hash comparisons.
     *
     * @param pack validated pack (required)
     * @return UTF-8 YAML bytes
     * @throws IOException if serialization fails
     */
    public static byte[] writeBytes(DocTypePack pack) throws IOException {
        return write(pack).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
