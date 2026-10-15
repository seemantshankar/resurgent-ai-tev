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
        Path parent = dbPath.toAbsolutePath().getParent();
        Path root = parent == null ? Path.of(DIRECTORY) : parent.resolve(DIRECTORY);
        for (ChecklistDefinition definition : new ChecklistStore(root).loadAll()) {
            catalog.put(definition.code(), definition);
        }
        return catalog;
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
