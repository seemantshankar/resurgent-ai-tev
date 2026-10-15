package com.resurgent.tev.doc.checklist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads and validates versioned checklist definitions from YAML (classpath or
 * filesystem) — the pack-store precedent. A definition that fails any rule fails
 * whole with a line-level message; in particular an item without a basis (a named
 * regulation or practice) is rejected, so the checklist never contains invented
 * requirements (spec #133).
 */
public final class ChecklistLoader {

    /** Classpath location of the checked-in seed spine. */
    public static final String SEED_SPINE_RESOURCE = "checklists/in.spine.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Pattern CODE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+");

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private static final Pattern ITEM_KEY = Pattern.compile("[a-z0-9_]+");

    private static final List<String> LEVELS = List.of("document", "data_point");

    private static final List<String> GRANULARITIES = List.of("mandate", "entity", "site");

    private static final List<String> ENTITY_ROLES =
            List.of("borrower", "promoter", "group_company");

    private static final List<String> ORIGINS = List.of("drafted", "observed", "promoted");

    private ChecklistLoader() {}

    /**
     * Loads the checked-in India spine golden from the classpath.
     *
     * @return the seed spine definition
     * @throws ChecklistValidationException when the seed is missing or invalid
     */
    public static ChecklistDefinition loadSeedSpine() throws ChecklistValidationException {
        return loadClasspath(SEED_SPINE_RESOURCE);
    }

    /**
     * Loads a checklist definition from a classpath resource.
     *
     * @param resource classpath resource name (required)
     * @return validated definition
     * @throws ChecklistValidationException when missing or invalid
     */
    public static ChecklistDefinition loadClasspath(String resource)
            throws ChecklistValidationException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ChecklistLoader.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ChecklistValidationException(
                        "checklist resource not found: " + resource);
            }
            return load(in, resource);
        } catch (IOException e) {
            throw new ChecklistValidationException(
                    "cannot read checklist resource " + resource + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads a checklist definition from a YAML file.
     *
     * @param path YAML file (required)
     * @return validated definition
     * @throws ChecklistValidationException when unreadable or invalid
     */
    public static ChecklistDefinition load(Path path) throws ChecklistValidationException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in, path.toString());
        } catch (IOException e) {
            throw new ChecklistValidationException(
                    "cannot read checklist file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses and validates a checklist definition from a YAML stream.
     *
     * @param in YAML stream (required)
     * @param source label used in error messages
     * @return validated definition
     * @throws ChecklistValidationException on any validation failure
     */
    public static ChecklistDefinition load(InputStream in, String source)
            throws ChecklistValidationException {
        JsonNode root;
        try {
            root = YAML.readTree(in);
        } catch (IOException e) {
            throw new ChecklistValidationException(source + ": invalid YAML — " + e.getMessage(), e);
        }
        return fromNode(root, source);
    }

    /**
     * Builds a definition from an already-parsed tree.
     *
     * @param root definition root node (required)
     * @param source label used in error messages
     * @return validated definition
     * @throws ChecklistValidationException on any validation failure
     */
    public static ChecklistDefinition fromNode(JsonNode root, String source)
            throws ChecklistValidationException {
        if (root == null || !root.isObject()) {
            throw new ChecklistValidationException(source + ": checklist root must be a mapping");
        }
        String code = requireText(root, "code", source);
        if (!CODE.matcher(code).matches()) {
            throw new ChecklistValidationException(source + ": code '" + code
                    + "' must look like 'country.family' (lowercase, dotted)");
        }
        String version = requireText(root, "version", source);
        if (!SEMVER.matcher(version).matches()) {
            throw new ChecklistValidationException(source + ": version '" + version
                    + "' must be semver (e.g. 1.0.0)");
        }
        String scope = requireText(root, "scope", source).toLowerCase(Locale.ROOT);
        if (!"spine".equals(scope) && !"industry".equals(scope)) {
            throw new ChecklistValidationException(source
                    + ": scope must be 'spine' or 'industry', got: " + scope);
        }
        String country = requireText(root, "country", source).toUpperCase(Locale.ROOT);
        JsonNode industriesNode = root.get("industries");
        List<String> industries = new ArrayList<>();
        if (industriesNode != null && industriesNode.isArray()) {
            for (JsonNode n : industriesNode) {
                if (!n.isNull() && !n.asText().isBlank()) {
                    industries.add(n.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if ("industry".equals(scope) && industries.isEmpty()) {
            throw new ChecklistValidationException(source
                    + ": an industry checklist must name its industries");
        }

        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new ChecklistValidationException(source + ": items must be a non-empty list");
        }
        List<ChecklistItemDef> items = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (JsonNode item : itemsNode) {
            index++;
            String where = source + ": items[" + index + "]";
            if (!item.isObject()) {
                throw new ChecklistValidationException(where + " must be a mapping");
            }
            items.add(itemFromNode(item, where, keys));
        }
        return new ChecklistDefinition(code, version, scope, country, industries, items);
    }

    private static ChecklistItemDef itemFromNode(JsonNode item, String where, Set<String> keys)
            throws ChecklistValidationException {
        String key = containstKey(item, where, keys);
        String label = requireText(item, "label", where);
        String level = requireText(item, "level", where).toLowerCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new ChecklistValidationException(where + " (" + key + "): level must be one of "
                    + LEVELS + ", got: " + level);
        }
        String granularity = item.hasNonNull("granularity")
                ? item.get("granularity").asText().trim().toLowerCase(Locale.ROOT)
                : "mandate";
        if (!GRANULARITIES.contains(granularity)) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): granularity must be one of " + GRANULARITIES + ", got: " + granularity);
        }
        String entityRole = item.hasNonNull("entity_role")
                ? item.get("entity_role").asText().trim().toLowerCase(Locale.ROOT)
                : null;
        if (entityRole != null) {
            if (entityRole.isEmpty()) {
                entityRole = null;
            } else if (!ENTITY_ROLES.contains(entityRole)) {
                throw new ChecklistValidationException(where + " (" + key
                        + "): entity_role must be one of " + ENTITY_ROLES + ", got: " + entityRole);
            } else if (!"entity".equals(granularity)) {
                throw new ChecklistValidationException(where + " (" + key
                        + "): entity_role needs granularity 'entity'");
            }
        }
        String documentType = item.hasNonNull("document_type")
                ? item.get("document_type").asText().trim()
                : null;
        if (documentType == null || documentType.isEmpty()) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): document_type is required — both levels name the document type");
        }
        String fieldKey = item.hasNonNull("field_key") ? item.get("field_key").asText().trim() : null;
        if (fieldKey != null && fieldKey.isEmpty()) {
            fieldKey = null;
        }
        String ontologyPath = item.hasNonNull("ontology_path")
                ? item.get("ontology_path").asText().trim()
                : null;
        if (ontologyPath != null && ontologyPath.isEmpty()) {
            ontologyPath = null;
        }
        if ("data_point".equals(level) && fieldKey == null && ontologyPath == null) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): a data-point item needs a field_key or an ontology_path");
        }
        boolean mandatory = !item.hasNonNull("mandatory") || item.get("mandatory").asBoolean(true);
        String origin = item.hasNonNull("origin")
                ? item.get("origin").asText().trim().toLowerCase(Locale.ROOT)
                : "drafted";
        if (!ORIGINS.contains(origin)) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): origin must be one of " + ORIGINS + ", got: " + origin);
        }

        // The basis is what keeps invented requirements out: no basis, no item.
        JsonNode basis = item.get("basis");
        if (basis == null || !basis.isObject()) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): basis is required — name the regulation or practice that requires this item");
        }
        String basisKind = basis.hasNonNull("kind")
                ? basis.get("kind").asText().trim().toLowerCase(Locale.ROOT)
                : "";
        if (!ChecklistItemDef.basisKinds().contains(basisKind)) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): basis.kind must be one of " + ChecklistItemDef.basisKinds()
                    + ", got: " + basisKind);
        }
        String basisText = basis.hasNonNull("text") ? basis.get("text").asText().trim() : "";
        if (basisText.isEmpty()) {
            throw new ChecklistValidationException(where + " (" + key
                    + "): basis.text is required — an item without a named regulation or"
                    + " practice basis is dropped, never stored");
        }

        String clientRequest = requireText(item, "request", where);
        return new ChecklistItemDef(key, label, level, granularity, entityRole, documentType,
                fieldKey, ontologyPath, mandatory, basisKind, basisText, origin, clientRequest);
    }

    private static String containstKey(JsonNode item, String where, Set<String> keys)
            throws ChecklistValidationException {
        String key = requireText(item, "key", where);
        if (!ITEM_KEY.matcher(key).matches()) {
            throw new ChecklistValidationException(where + ": key '" + key
                    + "' must be snake_case (lowercase letters, digits, underscores)");
        }
        if (!keys.add(key)) {
            throw new ChecklistValidationException(where + ": duplicate item key '" + key + "'");
        }
        return key;
    }

    private static String requireText(JsonNode node, String field, String where)
            throws ChecklistValidationException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new ChecklistValidationException(where + ": missing required field '" + field + "'");
        }
        return v.asText().trim();
    }

    /** Thrown when a checklist definition fails validation. Message names the source. */
    public static final class ChecklistValidationException extends Exception {

        /**
         * Creates a validation failure.
         *
         * @param message line-level reason (required)
         */
        public ChecklistValidationException(String message) {
            super(message);
        }

        /**
         * Creates a validation failure with a cause.
         *
         * @param message line-level reason (required)
         * @param cause underlying error
         */
        public ChecklistValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
