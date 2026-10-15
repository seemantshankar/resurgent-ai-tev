package com.resurgent.tev.doc.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.resurgent.tev.doc.extract.ValueKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads and validates doc type packs from YAML (classpath or filesystem).
 * Invalid packs fail with a line-level message — never a half-loaded pack.
 */
public final class PackLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern PACK_CODE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+");
    /** Person/company/PAN-like tokens that must never become field keys (S2.11). */
    private static final Pattern ENTITY_TOKEN = Pattern.compile(
            "(?i)(bhagyanagar|sharma|ramesh|\\b[A-Z]{5}[0-9]{4}[A-Z]\\b|\\b[A-Z]{2}[0-9]{2}[A-Z]{7}\\b"
                    + "|\\b[A-Z]{3}[- ]?[A-Z]{3}[ ]?[0-9]{4}\\b)");

    private PackLoader() {}

    /**
     * Loads a pack from a YAML file path.
     *
     * @param path YAML file (required)
     * @return validated pack
     * @throws PackValidationException when YAML is unreadable or invalid
     */
    public static DocTypePack load(Path path) throws PackValidationException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in, path.toString());
        } catch (IOException e) {
            throw new PackValidationException("cannot read pack file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads a pack from the classpath (e.g. {@code docpacks/agreement.ppa.yaml}).
     *
     * @param resource classpath resource name (required)
     * @return validated pack
     * @throws PackValidationException when missing or invalid
     */
    public static DocTypePack loadClasspath(String resource) throws PackValidationException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PackLoader.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new PackValidationException("pack resource not found: " + resource);
            }
            return load(in, resource);
        } catch (IOException e) {
            throw new PackValidationException("cannot read pack resource " + resource + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses and validates a pack from an input stream.
     *
     * @param in YAML stream (required)
     * @param source label used in error messages
     * @return validated pack
     * @throws PackValidationException on any validation failure
     */
    public static DocTypePack load(InputStream in, String source) throws PackValidationException {
        JsonNode root;
        try {
            root = YAML.readTree(in);
        } catch (IOException e) {
            throw new PackValidationException(source + ": invalid YAML — " + e.getMessage(), e);
        }
        return fromNode(root, source);
    }

    /**
     * Builds a pack from an already-parsed YAML/JSON tree.
     *
     * @param root pack root node (required)
     * @param source label used in error messages
     * @return validated pack
     * @throws PackValidationException on any validation failure
     */
    public static DocTypePack fromNode(JsonNode root, String source) throws PackValidationException {
        if (root == null || !root.isObject()) {
            throw new PackValidationException(source + ": pack root must be a mapping");
        }
        String code = requireText(root, "code", source);
        if (!PACK_CODE.matcher(code).matches()) {
            throw new PackValidationException(source + ": code '" + code
                    + "' must look like 'family.name' (lowercase, dotted)");
        }
        String version = requireText(root, "version", source);
        if (!SEMVER.matcher(version).matches()) {
            throw new PackValidationException(source + ": version '" + version
                    + "' must be semver (e.g. 1.0.0)");
        }
        String family = requireText(root, "family", source);
        String fileRole = requireText(root, "file_role", source);

        List<String> industryTags = textList(root.get("industry_tags"));
        List<String> identitySignals = identitySignals(root.get("identity_signals"), source);

        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode == null || !fieldsNode.isArray() || fieldsNode.isEmpty()) {
            throw new PackValidationException(source + ": fields must be a non-empty list");
        }
        List<PackField> fields = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        int index = 0;
        for (JsonNode f : fieldsNode) {
            index++;
            String where = source + ": fields[" + index + "]";
            if (!f.isObject()) {
                throw new PackValidationException(where + " must be a mapping");
            }
            String key = requireText(f, "key", where);
            if (!keys.add(key)) {
                throw new PackValidationException(where + ": duplicate field key '" + key + "'");
            }
            if (ENTITY_TOKEN.matcher(key).find()) {
                throw new PackValidationException(where + ": key '" + key
                        + "' looks like a company/person/PAN token — use a label, not a value");
            }
            String path = requireText(f, "path", where);
            if (path.isBlank()) {
                throw new PackValidationException(where + ": path must be non-empty");
            }
            String pathRoot = requireText(f, "path_root", where);
            try {
                DocTypePack.requireSpineRoot(pathRoot);
            } catch (IllegalArgumentException e) {
                throw new PackValidationException(where + ": " + e.getMessage());
            }
            ValueKind kind;
            String kindWire = requireText(f, "value_kind", where);
            try {
                kind = ValueKind.fromWire(kindWire);
            } catch (IllegalArgumentException e) {
                throw new PackValidationException(where + ": value_kind '" + kindWire
                        + "' is not in the closed set");
            }
            boolean required = f.path("required").asBoolean(false);
            List<String> slots = textList(f.get("slots"));
            for (String slot : slots) {
                if (!"currency".equals(slot) && !"scale".equals(slot) && !"unit".equals(slot)) {
                    throw new PackValidationException(where + ": unknown slot '" + slot
                            + "' (allowed: currency, scale, unit)");
                }
                if ("scale".equals(slot) || "currency".equals(slot)) {
                    // scale slots use the shared Excel scale table at normalize time
                }
            }
            String hint = f.hasNonNull("prompt_hint") ? f.get("prompt_hint").asText() : null;
            List<String> synonyms = textList(f.get("synonyms"));
            fields.add(new PackField(key, path, pathRoot.toLowerCase(Locale.ROOT), kind,
                    required, slots, hint, synonyms));
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        JsonNode tablesNode = root.get("tables");
        if (tablesNode != null && tablesNode.isArray()) {
            for (JsonNode t : tablesNode) {
                tables.add(jsonToMap(t));
            }
        }
        List<String> checklist = textList(root.get("checklist"));
        List<String> reportSections = textList(root.get("report_sections"));

        String documentDateKind = documentDateKind(root.get("document_date"), source);
        List<PackPassage> passages = passages(root.get("passages"), fields, tables, source);

        return new DocTypePack(code, version, family, fileRole, industryTags, identitySignals,
                fields, tables, checklist, reportSections, documentDateKind, passages);
    }

    /**
     * Optional {@code passages:} section (M3-06): per chapter topic, the heading terms
     * that assign a passage to it and the data points to take from the chapter. Every
     * referenced key must be a declared field or row table — a bad pack fails at load.
     */
    private static List<PackPassage> passages(JsonNode node, List<PackField> fields,
            List<Map<String, Object>> tables, String source) throws PackValidationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new PackValidationException(source + ": passages must be a list");
        }
        java.util.Set<String> knownKeys = new java.util.HashSet<>();
        for (PackField field : fields) {
            knownKeys.add(field.key());
        }
        for (Map<String, Object> table : tables) {
            Object name = table.get("name");
            if (name != null) {
                knownKeys.add(String.valueOf(name));
            }
        }
        List<PackPassage> out = new ArrayList<>();
        java.util.Set<String> topics = new java.util.HashSet<>();
        int index = 0;
        for (JsonNode p : node) {
            index++;
            String where = source + ": passages[" + index + "]";
            if (!p.isObject()) {
                throw new PackValidationException(where + " must be a mapping");
            }
            String topic = requireText(p, "topic", where);
            if (!topics.add(topic)) {
                throw new PackValidationException(where + ": duplicate topic '" + topic + "'");
            }
            List<String> headingTerms = textList(p.get("heading_terms"));
            if (headingTerms.isEmpty()) {
                throw new PackValidationException(where + ": heading_terms must be a non-empty list");
            }
            List<String> keys = textList(p.get("fields"));
            if (keys.isEmpty()) {
                throw new PackValidationException(where + ": fields must be a non-empty list");
            }
            for (String key : keys) {
                if (!knownKeys.contains(key)) {
                    throw new PackValidationException(where + ": field '" + key
                            + "' is not a declared pack field or table");
                }
            }
            try {
                out.add(new PackPassage(topic, headingTerms, keys));
            } catch (IllegalArgumentException e) {
                throw new PackValidationException(where + ": " + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Optional {@code document_date: {kind: execution}} declaration (M3). The kind is
     * from the closed set; an absent declaration means the generic {@code date} kind.
     */
    private static String documentDateKind(JsonNode node, String source)
            throws PackValidationException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new PackValidationException(source
                    + ": document_date must be a mapping (e.g. {kind: execution})");
        }
        String kind = requireText(node, "kind", source + ": document_date");
        try {
            DocTypePack.requireDocumentDateKind(kind);
        } catch (IllegalArgumentException e) {
            throw new PackValidationException(source + ": document_date: " + e.getMessage());
        }
        return kind.toLowerCase(Locale.ROOT);
    }

    /**
     * Loads every {@code *.yaml} / {@code *.yml} under a directory (non-recursive).
     *
     * @param dir pack directory (required)
     * @return successfully loaded packs, in filename order
     * @throws PackValidationException when any file fails validation
     */
    public static List<DocTypePack> loadDirectory(Path dir) throws PackValidationException {
        if (!Files.isDirectory(dir)) {
            throw new PackValidationException("pack dir does not exist: " + dir);
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new PackValidationException("cannot list pack dir " + dir + ": " + e.getMessage(), e);
        }
        List<DocTypePack> packs = new ArrayList<>();
        for (Path file : files) {
            packs.add(load(file));
        }
        return packs;
    }

    /** Loads the four seed goldens from {@code docpacks/} on the classpath. */
    public static List<DocTypePack> loadSeedPacks() throws PackValidationException {
        return List.of(
                loadClasspath("docpacks/identity.promoter_net_worth.yaml"),
                loadClasspath("docpacks/quotation.vendor.yaml"),
                loadClasspath("docpacks/agreement.ppa.yaml"),
                loadClasspath("docpacks/report.dpr.yaml"));
    }

    private static String requireText(JsonNode node, String field, String where)
            throws PackValidationException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new PackValidationException(where + ": missing required field '" + field + "'");
        }
        return v.asText().trim();
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (!n.isNull() && !n.asText().isBlank()) {
                out.add(n.asText().trim());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> identitySignals(JsonNode node, String source)
            throws PackValidationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            // allow either a flat phrase list or [{any_of: [...]}]
            List<String> flat = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    flat.add(item.asText());
                } else if (item.isObject() && item.has("any_of")) {
                    flat.addAll(textList(item.get("any_of")));
                } else {
                    throw new PackValidationException(source
                            + ": identity_signals entries must be strings or {any_of: [...]}");
                }
            }
            return List.copyOf(flat);
        }
        if (node.isObject() && node.has("any_of")) {
            return textList(node.get("any_of"));
        }
        throw new PackValidationException(source + ": identity_signals must be a list or {any_of: [...]}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (node.isObject()) {
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), jsonToValue(e.getValue()));
            }
            return map;
        }
        return Map.of();
    }

    private static Object jsonToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonToMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode n : node) {
                list.add(jsonToValue(n));
            }
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    /** Thrown when a pack fails validation. Message is actionable and names the source. */
    public static final class PackValidationException extends Exception {

        /**
         * Creates a validation failure.
         *
         * @param message line-level reason (required)
         */
        public PackValidationException(String message) {
            super(message);
        }

        /**
         * Creates a validation failure with a cause.
         *
         * @param message line-level reason (required)
         * @param cause underlying error
         */
        public PackValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
