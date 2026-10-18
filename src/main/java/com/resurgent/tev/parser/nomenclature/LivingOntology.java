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
    private final List<String> paths;

    private LivingOntology(Map<String, String> aliases, List<String> paths) {
        this.aliases = Map.copyOf(aliases);
        this.paths = List.copyOf(paths);
    }

    public static LivingOntology from(OntologySlice slice) {
        Map<String, String> living = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        for (NomenclatureNode node : slice.nodes()) {
            paths.add(node.path());
            living.put(node.name(), node.path());
            living.put(node.path(), node.path());
        }
        for (NomenclatureAlias alias : slice.aliases()) {
            living.put(alias.aliasText(), alias.leafPath());
        }
        for (ProjectFactField field : slice.projectFactFields()) {
            paths.add(field.path());
            living.put(field.name(), field.path());
            living.put(field.path(), field.path());
        }
        return new LivingOntology(OntologyVocabulary.aliasIndex(living),
                OntologyVocabulary.allowedPaths(paths));
    }

    public Map<String, String> aliases() {
        return aliases;
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
        return new LivingOntology(nextAliases, nextPaths);
    }
}
