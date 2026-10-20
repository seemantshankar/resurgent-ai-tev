package com.resurgent.tev.parser.nomenclature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The living master ontology for one binding run: the frozen seed plus whatever this
 * mandate has grown, as the label→path index and the path list a binder may choose.
 *
 * <p>Both binders build one from the mandate's {@link OntologySlice} — the Excel
 * model's Layer B binding and the document pipeline's fact binding (issue #153) —
 * and both grow it the same way with {@link #withLeaf}.
 */
public final class LivingOntology {

    private final Map<String, String> aliases;
    private final Map<String, String> hardAliases;
    private final List<String> paths;

    private LivingOntology(
            Map<String, String> aliases, Map<String, String> hardAliases, List<String> paths) {
        this.aliases = Map.copyOf(aliases);
        this.hardAliases = Map.copyOf(hardAliases);
        this.paths = List.copyOf(paths);
    }

    public static LivingOntology from(OntologySlice slice) {
        Map<String, String> living = new LinkedHashMap<>();
        Map<String, String> hard = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        java.util.Set<String> softPaths = new java.util.HashSet<>();
        for (NomenclatureNode node : slice.nodes()) {
            if (NomenclatureNode.LAYER_MANDATE_SOFT.equals(node.layer())) {
                softPaths.add(node.path());
            }
        }
        for (NomenclatureNode node : slice.nodes()) {
            paths.add(node.path());
            living.put(node.name(), node.path());
            living.put(node.path(), node.path());
            if (!softPaths.contains(node.path())) {
                hard.put(node.name(), node.path());
                hard.put(node.path(), node.path());
            }
        }
        for (NomenclatureAlias alias : slice.aliases()) {
            living.put(alias.aliasText(), alias.leafPath());
            if (!softPaths.contains(alias.leafPath())) {
                hard.put(alias.aliasText(), alias.leafPath());
            }
        }
        for (ProjectFactField field : slice.projectFactFields()) {
            paths.add(field.path());
            living.put(field.name(), field.path());
            living.put(field.path(), field.path());
            hard.put(field.name(), field.path());
            hard.put(field.path(), field.path());
        }
        return new LivingOntology(
                OntologyVocabulary.aliasIndex(living),
                OntologyVocabulary.aliasIndex(hard),
                OntologyVocabulary.allowedPaths(paths));
    }

    public Map<String, String> aliases() {
        return aliases;
    }

    public Map<String, String> hardAliases() {
        return hardAliases;
    }

    public List<String> paths() {
        return paths;
    }

    public boolean contains(String path) {
        return paths.contains(path);
    }

    public LivingOntology withLeaf(String path, String aliasText) {
        Map<String, String> nextAliases = new LinkedHashMap<>(aliases);
        OntologyVocabulary.putAlias(nextAliases, aliasText, path);
        OntologyVocabulary.putAlias(nextAliases, path, path);
        List<String> nextPaths = new ArrayList<>(paths);
        if (!nextPaths.contains(path)) {
            nextPaths.add(path);
        }
        return new LivingOntology(nextAliases, hardAliases, nextPaths);
    }
}
