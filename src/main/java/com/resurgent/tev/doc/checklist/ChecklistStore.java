package com.resurgent.tev.doc.checklist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned checklist store beside the workspace, alongside the pack store:
 * {@code {db-parent}/checklists/*.yaml}. Later tickets (#144/#148) publish learned
 * and reshaped definitions here; the checked-in seed spine always loads from the
 * classpath and a file with the same code overrides it (newest definition wins).
 */
public final class ChecklistStore {

    /** Directory name next to the workspace database. */
    public static final String DIRECTORY = "checklists";

    private final Path checklistsRoot;

    /**
     * @param checklistsRoot directory holding checklist YAML files
     *     (typically {@code {db-parent}/checklists})
     */
    public ChecklistStore(Path checklistsRoot) {
        this.checklistsRoot = Objects.requireNonNull(checklistsRoot, "checklistsRoot")
                .toAbsolutePath().normalize();
    }

    public Path checklistsRoot() {
        return checklistsRoot;
    }

    /**
     * Loads every {@code *.yaml} / {@code *.yml} in the directory.
     *
     * @return definitions in filename order; empty when the directory does not exist
     * @throws ChecklistLoader.ChecklistValidationException when a file is invalid
     */
    public List<ChecklistDefinition> loadAll()
            throws ChecklistLoader.ChecklistValidationException {
        if (!Files.isDirectory(checklistsRoot)) {
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.list(checklistsRoot)) {
            files = stream
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ChecklistLoader.ChecklistValidationException(
                    "cannot list checklist dir " + checklistsRoot + ": " + e.getMessage(), e);
        }
        List<ChecklistDefinition> definitions = new ArrayList<>();
        for (Path file : files) {
            definitions.add(ChecklistLoader.load(file));
        }
        return definitions;
    }

    /**
     * The seed spine plus the store's definitions, keyed by definition code with
     * the store winning — the pack-store replacement discipline.
     *
     * @param dbPath workspace database path (the store sits beside it)
     * @return definition code → definition
     * @throws ChecklistLoader.ChecklistValidationException when a stored file is invalid
     */
    public static Map<String, ChecklistDefinition> loadCatalog(Path dbPath)
            throws ChecklistLoader.ChecklistValidationException {
        Map<String, ChecklistDefinition> catalog = new LinkedHashMap<>();
        ChecklistDefinition seed = ChecklistLoader.loadSeedSpine();
        catalog.put(seed.code(), seed);
        for (ChecklistDefinition definition : latestPerCode(
                new ChecklistStore(storeRoot(dbPath)).loadAll()).values()) {
            catalog.put(definition.code(), definition);
        }
        return catalog;
    }

    /**
     * One definition per code: the highest semver wins, so learning a new version
     * ({@code {code}@{version}.yaml}) never fights the file that introduced the
     * code — newer learning outcomes simply outrank older files.
     */
    public static Map<String, ChecklistDefinition> latestPerCode(
            List<ChecklistDefinition> definitions) {
        Map<String, ChecklistDefinition> latest = new LinkedHashMap<>();
        for (ChecklistDefinition definition : definitions) {
            ChecklistDefinition current = latest.get(definition.code());
            if (current == null || compareVersions(definition.version(), current.version()) >= 0) {
                latest.put(definition.code(), definition);
            }
        }
        return latest;
    }

    /** Compares two validated semver strings numerically. */
    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * The checklist store directory beside a workspace database.
     *
     * @param dbPath workspace database path
     * @return {@code {db-parent}/checklists}
     */
    public static Path storeRoot(Path dbPath) {
        Path parent = dbPath.toAbsolutePath().getParent();
        return parent == null ? Path.of(DIRECTORY) : parent.resolve(DIRECTORY);
    }

    /**
     * Publishes one definition as versioned data ({@code {code}.yaml}), the pack
     * store's discipline: an existing file is never overwritten — a definition the
     * catalog already carries is reused, not republished. The caller re-loads the
     * file so what is assessed is exactly what was written and validated.
     *
     * @param definition the definition to publish (required)
     * @return the file path back into the store ({@code {code}.yaml})
     * @throws ChecklistLoader.ChecklistValidationException when rendering fails
     * @throws IOException when the directory or file cannot be written
     */
    public Path publish(ChecklistDefinition definition)
            throws ChecklistLoader.ChecklistValidationException, IOException {
        Path file = checklistsRoot.resolve(definition.code() + ".yaml");
        if (Files.exists(file)) {
            return file;
        }
        Files.createDirectories(checklistsRoot);
        Files.writeString(file, render(definition));
        return file;
    }

    /**
     * Publishes one definition as a new version ({@code {code}@{version}.yaml}),
     * leaving every existing file untouched — a checklist change is a new version
     * recording its reason, never an edit of history (M3-15). The loader validates
     * the written file when the caller re-loads it, so what is assessed is exactly
     * what was written.
     *
     * @param definition the definition to publish (required)
     * @return the file path back into the store ({@code {code}@{version}.yaml})
     * @throws ChecklistLoader.ChecklistValidationException when rendering fails
     * @throws IOException when the directory or file cannot be written
     */
    public Path publishVersioned(ChecklistDefinition definition)
            throws ChecklistLoader.ChecklistValidationException, IOException {
        Path file = checklistsRoot.resolve(
                definition.code() + "@" + definition.version() + ".yaml");
        if (Files.exists(file)) {
            return file;
        }
        Files.createDirectories(checklistsRoot);
        Files.writeString(file, render(definition));
        return file;
    }

    /** Renders one definition back to the store's YAML shape (the loader's input). */
    static String render(ChecklistDefinition definition) {
        StringBuilder yaml = new StringBuilder()
                .append("code: ").append(definition.code()).append('\n')
                .append("version: ").append(definition.version()).append('\n')
                .append("scope: ").append(definition.scope()).append('\n')
                .append("country: ").append(definition.country()).append('\n');
        if (definition.note() != null) {
            yaml.append("note: ").append(quote(definition.note())).append('\n');
        }
        if (!definition.industries().isEmpty()) {
            yaml.append("industries:\n");
            for (String industry : definition.industries()) {
                yaml.append("  - ").append(quote(industry)).append('\n');
            }
        }
        yaml.append("items:\n");
        for (ChecklistItemDef item : definition.items()) {
            yaml.append("  - key: ").append(item.key()).append('\n')
                    .append("    label: ").append(quote(item.label())).append('\n')
                    .append("    level: ").append(item.level()).append('\n')
                    .append("    granularity: ").append(item.granularity()).append('\n');
            if (item.entityRole() != null) {
                yaml.append("    entity_role: ").append(item.entityRole()).append('\n');
            }
            yaml.append("    document_type: ").append(quote(item.documentType())).append('\n');
            if (item.fieldKey() != null) {
                yaml.append("    field_key: ").append(quote(item.fieldKey())).append('\n');
            }
            if (item.ontologyPath() != null) {
                yaml.append("    ontology_path: ").append(quote(item.ontologyPath())).append('\n');
            }
            yaml.append("    mandatory: ").append(item.mandatory()).append('\n')
                    .append("    basis:\n")
                    .append("      kind: ").append(item.basisKind()).append('\n')
                    .append("      text: ").append(quote(item.basisText())).append('\n')
                    .append("    origin: ").append(item.origin()).append('\n')
                    .append("    request: ").append(quote(item.clientRequest())).append('\n');
            if (item.note() != null) {
                yaml.append("    note: ").append(quote(item.note())).append('\n');
            }
        }
        return yaml.toString();
    }

    /** Double-quotes a YAML scalar, escaping backslashes and quotes. */
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Same as {@link #loadCatalog(Path)} but ignores an unreadable store, like the
     * pack store in the assessment stages: a broken checklist registry beside a
     * workspace must not block an assessment.
     *
     * @param dbPath workspace database path
     * @return the loaded catalog, seed-only when the store is broken
     */
    public static Map<String, ChecklistDefinition> loadCatalogLenient(Path dbPath) {
        try {
            return loadCatalog(dbPath);
        } catch (ChecklistLoader.ChecklistValidationException e) {
            System.err.println("[doc-assess] ignoring checklist store beside " + dbPath + ": "
                    + e.getMessage());
            Map<String, ChecklistDefinition> seedOnly = new LinkedHashMap<>();
            try {
                ChecklistDefinition seed = ChecklistLoader.loadSeedSpine();
                seedOnly.put(seed.code(), seed);
            } catch (ChecklistLoader.ChecklistValidationException seedFailure) {
                throw new IllegalStateException("seed spine is invalid: " + seedFailure.getMessage(),
                        seedFailure);
            }
            return seedOnly;
        }
    }
}
