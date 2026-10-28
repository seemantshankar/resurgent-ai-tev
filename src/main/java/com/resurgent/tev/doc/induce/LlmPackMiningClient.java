package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Live LLM field mining for pack induction (S2.11). One call per type cluster: the model
 * authors a generic field schema from the cluster's printed labels and roles, preferring
 * the frozen spine paths and never using instance values as keys.
 *
 * <p>Cross-document consensus is enforced deterministically after the model: for each
 * mined field this adapter attributes the cluster documents whose layout text actually
 * contains one of the field's printed labels. The {@link Generalizer} then drops one-off
 * fields and the entity token filter strips company names, PAN/CIN and amounts.
 */
public final class LlmPackMiningClient implements PackMiningClient {

    private static final int MAX_COMPLETION_TOKENS = 8_000;

    private static final String SYSTEM = """
            You author a reusable field schema for a document TYPE from sample documents of \
            that type. The schema must fit ANY company of the type, not the samples you see.
            Rules:
            - Field keys and ontology paths come from printed LABELS and roles, never from \
            instance values, amounts, company or person names, PAN, CIN, GSTIN or addresses.
            - Prefer these frozen spine paths when they fit: %s
            - A single evidenced document can yield fields, tables, clauses, and passages. \
            Do not refuse a document because it is the only sample, and do not stop at a \
            fixed field count.
            - value_kind must be one of: money, quantity, rate, percent, date, duration, \
            text, enum, boolean, list. Every field and every table column MUST include \
            value_kind.
            - path_root must be one of: identity, economic, technical, commercial, legal, \
            regulatory, frame. Use an industry leaf under a known root only when no spine \
            leaf fits.
            - Schedule-like content (repeating line items, matrices, keyed rows) MUST be \
            tables[] of row objects, not nested object fields. Columns use generic roles \
            where they fit: description, amount, period, unit. Amount columns are money \
            or quantity; mark period_key on the period column. This table shape applies \
            to any document type.
            - Also return schedule tables (record schedules with key_column, and period \
            matrices with key_column and period_key), clauses, passages, and \
            document_date.kind (execution, signing, issue, report, offer, or date).
            - A table column key is a label, never a name or amount. Mark \
            domain_standard true when analysts always extract that item from this \
            document type.
            - Read every heading, table header, and layout line you are shown. \
            Keep prompt_hint under 12 words. No prose.
            - When a label repeats (Total, Sub Total, Grand Total), prompt_hint must \
            name which row (e.g. final grand total of the statement, section subtotal \
            excluding the grand total) — never a bare "Total" slot.
            Reply with JSON only, no prose:
            {"suggested_code":"family.name","family":"<root-ish family>",
             "identity_signals":["<printed phrases that identify this document type>"],
             "document_date":{"kind":"issue"},
             "fields":[{"key":"snake_case","path":"root > ... > Leaf","path_root":"root",
                        "value_kind":"money","required":true,"prompt_hint":"what to read",
                        "labels":["printed label variant","alias"],"sample_values":["<value seen>"],
                        "domain_standard":false}],
             "tables":[{"name":"snake_name","path":"root > Leaf","path_root":"root",
                        "key_column":"description","entity_key":"entity","site_key":"site",
                        "period_key":"period","domain_standard":true,
                        "columns":[{"key":"description","value_kind":"text"},
                                   {"key":"amount","value_kind":"money"},
                                   {"key":"period","value_kind":"date"},
                                   {"key":"unit","value_kind":"text"}]}],
             "clauses":[{"key":"snake_name","label":"Label","parent_family":"family",
                         "aliases":["printed heading"],"domain_standard":true}],
             "passages":[{"topic":"snake_name","heading_terms":["heading"],
                          "fields":["field_or_table_key"],"domain_standard":true}]}""";

    private final DocLlmClient client;
    private final Consumer<DocLlmClient.Usage> usageSink;
    private final WebSearchClient web;
    private final Map<String, String> env;

    /**
     * @param client    live client (required)
     * @param usageSink receives this call's usage for {@code llm_usage} (may be null)
     */
    public LlmPackMiningClient(DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) {
        this(client, usageSink, null, Map.of());
    }

    /**
     * @param client    live client (required)
     * @param usageSink receives this call's usage for {@code llm_usage} (may be null)
     * @param web       search adapter; called only when {@code env} allows web search
     * @param env       residency and endpoint configuration (may be empty)
     */
    public LlmPackMiningClient(
            DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink,
            WebSearchClient web,
            Map<String, String> env) {
        this.client = Objects.requireNonNull(client, "client");
        this.usageSink = usageSink;
        this.web = web;
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    @Override
    public String id() {
        return "llm-mining:" + client.id();
    }

    @Override
    public MiningResult mine(MiningRequest request) throws PackMiningException {
        try {
            String query = StructuralFingerprint.webQuery(request.documents(), request.industryTag());
            String snippets = "";
            if (web != null && PackMiningClients.webSearchAllowed(env)) {
                snippets = web.search(query);
                if (snippets == null) {
                    snippets = "";
                }
                snippets = new EntityTokenFilter(sampleTokens(request)).redact(snippets);
            }
            String rationale = snippets.isBlank()
                    ? "Offline structural fingerprint; web search was not used."
                    : snippets;
            DocLlmClient.Completion completion =
                    client.complete(systemPrompt(), userPrompt(request, snippets), MAX_COMPLETION_TOKENS);
            report(completion.usage());
            MiningResult parsed = interpret(completion.content(), request);
            if (parsed.domainRationale() != null && !parsed.domainRationale().isBlank()
                    && snippets.isBlank()) {
                rationale = parsed.domainRationale();
            }
            return parsed.withResearch(query, rationale);
        } catch (DocLlmException e) {
            report(DocLlmClient.Usage.failed(client.id(), 0));
            throw new PackMiningException("pack mining LLM failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            report(DocLlmClient.Usage.failed(client.id(), 0));
            throw new PackMiningException("pack mining failed: " + e.getMessage(), e);
        }
    }

    private static String systemPrompt() {
        return String.format(SYSTEM, String.join("; ", SpineCatalog.paths()));
    }

    private static String userPrompt(MiningRequest request, String snippets) {
        EntityTokenFilter filter = new EntityTokenFilter(sampleTokens(request));
        StringBuilder sb = new StringBuilder();
        sb.append("Author a reusable document-type schema. Put schedule-like content in ")
                .append("tables[] as row objects with description, amount, period, and unit ")
                .append("columns where they fit. Include value_kind on every field and every ")
                .append("table column.\n");
        sb.append("Industry: ")
                .append(request.industryTag() == null || request.industryTag().isBlank()
                        ? "general" : filter.redact(request.industryTag()))
                .append('\n');
        sb.append("File role: ")
                .append(request.fileRole() == null || request.fileRole().isBlank()
                        ? "(unknown)" : filter.redact(request.fileRole()))
                .append('\n');
        if (request.docTypeHint() != null && !request.docTypeHint().isBlank()) {
            sb.append("Type hint: ").append(filter.redact(request.docTypeHint())).append('\n');
        }
        if (snippets != null && !snippets.isBlank()) {
            sb.append("Domain research:\n").append(snippets).append('\n');
        }
        List<MiningDocument> docs = request.documents();
        sb.append("\nSample documents (").append(docs.size()).append("):\n");
        for (MiningDocument doc : docs) {
            sb.append("\n=== DOC ").append(doc.id()).append(" ===\n");
            sb.append("title: ").append(filter.redact(doc.title() == null ? "" : doc.title()))
                    .append('\n');
            sb.append("structure:\n").append(StructuralFingerprint.promptText(List.of(doc)))
                    .append('\n');
        }
        return sb.toString();
    }

    private static Set<String> sampleTokens(MiningRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        if (request.documents() == null) {
            return tokens;
        }
        for (MiningDocument document : request.documents()) {
            tokens.addAll(document.sampleEntityTokens());
        }
        return tokens;
    }

    private static String layoutText(MiningDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (String line : doc.layoutText()) {
            if (line != null && !line.isBlank()) {
                sb.append(line.trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Parses the model's schema and attributes each field's {@code docIds} deterministically:
     * a field is present in a document when the document text contains one of the field's
     * printed labels (or its key/leaf words). This keeps consensus honest even when the
     * model does not return per-document ids.
     */
    static MiningResult interpret(String content, MiningRequest request) {
        JsonNode root = DocLlmJson.object(content);
        String suggestedCode = text(root, "suggested_code");
        String family = text(root, "family");
        List<String> identitySignals = stringArray(root.get("identity_signals"));

        List<MiningDocument> docs = request.documents();
        List<MinedField> fields = new ArrayList<>();
        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                String key = text(f, "key");
                if (key == null) {
                    continue;
                }
                List<String> labels = stringArray(f.get("labels"));
                List<String> synonyms = stringArray(f.get("synonyms"));
                String path = text(f, "path");
                List<String> phrases = detectionPhrases(key, labels, synonyms, path);
                List<String> docIds = attributeDocs(phrases, docs);
                String pathRoot = text(f, "path_root");
                String valueKind = text(f, "value_kind");
                Boolean required = f.hasNonNull("required") ? f.get("required").asBoolean() : null;
                String promptHint = text(f, "prompt_hint");
                List<String> sampleValues = stringArray(f.get("sample_values"));
                boolean domainStandard = flag(f, "domain_standard");
                fields.add(new MinedField(
                        key,
                        path,
                        pathRoot,
                        valueKind,
                        required,
                        promptHint,
                        synonyms,
                        labels,
                        docIds,
                        sampleValues,
                        domainStandard));
                addNestedRowFields(fields, f, key, path, pathRoot, required, docIds,
                        domainStandard, docs);
            }
        }
        List<MinedTable> tables = tables(root.get("tables"), docs);
        List<MinedClause> clauses = clauses(root.get("clauses"), docs);
        List<MinedPassage> passages = passages(root.get("passages"), docs);
        String dateKind = documentDateKind(root.get("document_date"));
        return new MiningResult(suggestedCode, family, identitySignals, fields, tables, clauses,
                passages, dateKind, null, text(root, "domain_rationale"));
    }

    /**
     * Carries Shape 1 nested row objects as dotted child fields so the generalizer
     * can rewrite them into pack tables. Does not invent {@code value_kind} from
     * JSON Schema {@code type}; extract infers later, and quality gates reject
     * a blank kind on a published column.
     */
    private static void addNestedRowFields(
            List<MinedField> fields,
            JsonNode parent,
            String parentKey,
            String parentPath,
            String pathRoot,
            Boolean required,
            List<String> docIds,
            boolean domainStandard,
            List<MiningDocument> docs) {
        if (!isRowArray(parent)) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (MinedField existing : fields) {
            seen.add(existing.key());
        }
        JsonNode properties = nestedProperties(parent);
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> addNestedColumn(
                    fields, seen, parentKey, parentPath, pathRoot, required, docIds,
                    domainStandard, docs, entry.getKey(), entry.getValue()));
        }
        JsonNode columns = parent.get("columns");
        if (columns == null) {
            columns = parent.has("items") ? null : parent.get("fields");
        }
        if (columns != null && columns.isArray()) {
            for (JsonNode column : columns) {
                String key = text(column, "key");
                if (key != null) {
                    addNestedColumn(fields, seen, parentKey, parentPath, pathRoot, required,
                            docIds, domainStandard, docs, key, column);
                }
            }
        }
    }

    private static boolean isRowArray(JsonNode parent) {
        String kind = text(parent, "value_kind");
        if ("list".equalsIgnoreCase(kind)) {
            return true;
        }
        String type = text(parent, "type");
        if ("array".equalsIgnoreCase(type)) {
            return true;
        }
        JsonNode items = parent.get("items");
        return items != null && items.isObject();
    }

    private static JsonNode nestedProperties(JsonNode parent) {
        JsonNode items = parent.get("items");
        if (items != null && items.isObject()) {
            JsonNode properties = items.get("properties");
            if (properties != null && properties.isObject()) {
                return properties;
            }
        }
        JsonNode properties = parent.get("properties");
        return properties != null && properties.isObject() ? properties : null;
    }

    private static void addNestedColumn(
            List<MinedField> fields,
            Set<String> seen,
            String parentKey,
            String parentPath,
            String pathRoot,
            Boolean required,
            List<String> parentDocIds,
            boolean domainStandard,
            List<MiningDocument> docs,
            String columnKey,
            JsonNode column) {
        if (columnKey == null || columnKey.isBlank()) {
            return;
        }
        String childKey = parentKey + "." + columnKey.trim();
        if (!seen.add(childKey)) {
            return;
        }
        String kind = declaredKind(column);
        if ("list".equalsIgnoreCase(kind)) {
            return;
        }
        List<String> labels = stringArray(column.get("labels"));
        if (labels.isEmpty()) {
            labels = List.of(columnKey.replace('_', ' '));
        }
        String path = text(column, "path");
        if (path == null) {
            path = nestedPath(parentPath, columnKey);
        }
        List<String> phrases = detectionPhrases(childKey, labels, List.of(), path);
        List<String> docIds = docs == null || docs.isEmpty()
                ? parentDocIds
                : attributeDocs(phrases, docs);
        if (docIds.isEmpty()) {
            docIds = parentDocIds;
        }
        fields.add(new MinedField(
                childKey,
                path,
                text(column, "path_root") == null ? pathRoot : text(column, "path_root"),
                kind,
                required,
                text(column, "prompt_hint"),
                stringArray(column.get("synonyms")),
                labels,
                docIds,
                stringArray(column.get("sample_values")),
                domainStandard || flag(column, "domain_standard")));
    }

    /** Declared {@code value_kind} only — JSON {@code type} is not a mining kind. */
    private static String declaredKind(JsonNode node) {
        String kind = text(node, "value_kind");
        if (kind != null) {
            return kind;
        }
        String type = text(node, "type");
        if (type != null && "array".equalsIgnoreCase(type)) {
            return "list";
        }
        return null;
    }

    private static String nestedPath(String parentPath, String columnKey) {
        String leaf = columnKey.replace('_', ' ').trim();
        if (parentPath == null || parentPath.isBlank()) {
            return leaf;
        }
        return parentPath + " > " + leaf;
    }

    private static List<MinedTable> tables(JsonNode node, List<MiningDocument> docs) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<MinedTable> tables = new ArrayList<>();
        for (JsonNode table : node) {
            String name = text(table, "name");
            if (name == null) {
                continue;
            }
            List<MinedColumn> columns = new ArrayList<>();
            JsonNode cols = table.get("columns");
            if (cols == null) {
                cols = table.get("fields");
            }
            if (cols != null && cols.isArray()) {
                for (JsonNode column : cols) {
                    String key = text(column, "key");
                    if (key != null) {
                        columns.add(new MinedColumn(key, text(column, "value_kind")));
                    }
                }
            }
            List<String> phrases = detectionPhrases(name, stringArray(table.get("labels")),
                    List.of(), text(table, "path"));
            tables.add(new MinedTable(
                    name,
                    text(table, "path"),
                    text(table, "path_root"),
                    text(table, "key_column"),
                    text(table, "entity_key"),
                    text(table, "site_key"),
                    text(table, "period_key"),
                    columns,
                    attributeDocs(phrases, docs),
                    flag(table, "domain_standard")));
        }
        return tables;
    }

    private static List<MinedClause> clauses(JsonNode node, List<MiningDocument> docs) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<MinedClause> clauses = new ArrayList<>();
        for (JsonNode clause : node) {
            String key = text(clause, "key");
            if (key == null) {
                continue;
            }
            List<String> aliases = stringArray(clause.get("aliases"));
            clauses.add(new MinedClause(
                    key,
                    text(clause, "label"),
                    text(clause, "parent_family"),
                    aliases,
                    attributeDocs(detectionPhrases(key, aliases, List.of(), text(clause, "label")), docs),
                    flag(clause, "domain_standard")));
        }
        return clauses;
    }

    private static List<MinedPassage> passages(JsonNode node, List<MiningDocument> docs) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<MinedPassage> passages = new ArrayList<>();
        for (JsonNode passage : node) {
            String topic = text(passage, "topic");
            if (topic == null) {
                continue;
            }
            List<String> terms = stringArray(passage.get("heading_terms"));
            passages.add(new MinedPassage(
                    topic,
                    terms,
                    stringArray(passage.get("fields")),
                    attributeDocs(detectionPhrases(topic, terms, List.of(), null), docs),
                    flag(passage, "domain_standard")));
        }
        return passages;
    }

    private static String documentDateKind(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return text(node, "kind");
    }

    private static boolean flag(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).asBoolean();
    }

    private static List<String> detectionPhrases(String key, List<String> labels,
            List<String> synonyms, String path) {
        Set<String> phrases = new LinkedHashSet<>();
        addPhrase(phrases, key);
        for (String label : labels) {
            addPhrase(phrases, label);
        }
        for (String synonym : synonyms) {
            addPhrase(phrases, synonym);
        }
        if (path != null) {
            int sep = path.lastIndexOf('>');
            addPhrase(phrases, sep >= 0 ? path.substring(sep + 1) : path);
        }
        return List.copyOf(phrases);
    }

    private static void addPhrase(Set<String> phrases, String raw) {
        if (raw == null) {
            return;
        }
        String normalized = SpineCatalog.normalize(raw.replace('_', ' '));
        if (normalized.length() >= 3) {
            phrases.add(normalized);
        }
    }

    private static List<String> attributeDocs(List<String> phrases, List<MiningDocument> docs) {
        List<String> ids = new ArrayList<>();
        for (MiningDocument doc : docs) {
            String haystack = SpineCatalog.normalize(docText(doc));
            if (haystack.isEmpty()) {
                continue;
            }
            for (String phrase : phrases) {
                if (haystack.contains(phrase)) {
                    ids.add(doc.id());
                    break;
                }
            }
        }
        return ids;
    }

    private static String docText(MiningDocument doc) {
        StringBuilder sb = new StringBuilder();
        if (doc.title() != null) {
            sb.append(doc.title()).append(' ');
        }
        for (String line : doc.layoutText()) {
            if (line != null) {
                sb.append(line).append(' ');
            }
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText();
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return List.copyOf(out);
    }

    private void report(DocLlmClient.Usage usage) {
        if (usageSink != null && usage != null) {
            usageSink.accept(usage);
        }
    }
}
