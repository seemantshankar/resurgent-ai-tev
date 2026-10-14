package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** Per-document layout text sent to the model. */
    static final int MAX_DOC_CHARS = 1_500;

    /** Total layout text across the cluster. */
    static final int MAX_TOTAL_CHARS = 12_000;

    /** Documents described in one call. */
    static final int MAX_DOCS = 8;

    private static final int MAX_COMPLETION_TOKENS = 8_000;

    private static final String SYSTEM = """
            You author a reusable field schema for a document TYPE from sample documents of \
            that type. The schema must fit ANY company of the type, not the samples you see.
            Rules:
            - Field keys and ontology paths come from printed LABELS and roles, never from \
            instance values, amounts, company or person names, PAN, CIN, GSTIN or addresses.
            - Prefer these frozen spine paths when they fit: %s
            - A field only belongs in the schema when its label appears across multiple \
            documents of the cluster (cross-document consensus).
            - value_kind must be one of: money, quantity, rate, percent, date, duration, \
            text, enum, boolean, list.
            - path_root must be one of: identity, economic, technical, commercial, legal, \
            regulatory, frame. Use an industry leaf under a known root only when no spine \
            leaf fits.
            - Return at most 12 fields. Keep prompt_hint under 12 words. No prose.
            Reply with JSON only, no prose:
            {"suggested_code":"family.name","family":"<root-ish family>",
             "identity_signals":["<printed phrases that identify this document type>"],
             "fields":[{"key":"snake_case","path":"root > ... > Leaf","path_root":"root",
                        "value_kind":"money","required":true,"prompt_hint":"what to read",
                        "labels":["printed label variant","alias"],"sample_values":["<value seen>"]}]}""";

    private final DocLlmClient client;
    private final Consumer<DocLlmClient.Usage> usageSink;

    /**
     * @param client    live client (required)
     * @param usageSink receives this call's usage for {@code llm_usage} (may be null)
     */
    public LlmPackMiningClient(DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) {
        this.client = Objects.requireNonNull(client, "client");
        this.usageSink = usageSink;
    }

    @Override
    public String id() {
        return "llm-mining:" + client.id();
    }

    @Override
    public MiningResult mine(MiningRequest request) throws PackMiningException {
        try {
            DocLlmClient.Completion completion =
                    client.complete(systemPrompt(), userPrompt(request), MAX_COMPLETION_TOKENS);
            report(completion.usage());
            return interpret(completion.content(), request);
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

    private static String userPrompt(MiningRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Industry: ")
                .append(request.industryTag() == null || request.industryTag().isBlank()
                        ? "general" : request.industryTag())
                .append('\n');
        sb.append("File role: ")
                .append(request.fileRole() == null || request.fileRole().isBlank()
                        ? "(unknown)" : request.fileRole())
                .append('\n');
        if (request.docTypeHint() != null && !request.docTypeHint().isBlank()) {
            sb.append("Type hint: ").append(request.docTypeHint()).append('\n');
        }
        List<MiningDocument> docs = request.documents();
        int limit = Math.min(docs.size(), MAX_DOCS);
        sb.append("\nSample documents (").append(limit).append("):\n");
        int budget = MAX_TOTAL_CHARS;
        for (int i = 0; i < limit; i++) {
            MiningDocument doc = docs.get(i);
            sb.append("\n=== DOC ").append(doc.id()).append(" ===\n");
            sb.append("title: ").append(doc.title() == null ? "" : doc.title()).append('\n');
            String text = layoutText(doc);
            if (text.length() > MAX_DOC_CHARS) {
                text = text.substring(0, MAX_DOC_CHARS);
            }
            if (text.length() > budget) {
                text = text.substring(0, Math.max(0, budget));
            }
            budget -= text.length();
            sb.append("layout:\n").append(text).append('\n');
        }
        return sb.toString();
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
                fields.add(new MinedField(
                        key,
                        path,
                        text(f, "path_root"),
                        text(f, "value_kind"),
                        f.hasNonNull("required") ? f.get("required").asBoolean() : null,
                        text(f, "prompt_hint"),
                        synonyms,
                        labels,
                        docIds,
                        stringArray(f.get("sample_values"))));
            }
        }
        return new MiningResult(suggestedCode, family, identitySignals, fields);
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
