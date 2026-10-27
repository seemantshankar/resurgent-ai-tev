package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackPassage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic post-LLM generalization filters (S2.11).
 *
 * <ol>
 *   <li><b>Label not value</b> — drop/rewrite keys and paths that match person/company/
 *       amount/PAN/CIN/sample-entity tokens; rewrite to a label token.</li>
 *   <li><b>Spine-first</b> — map to frozen spine leaves; new industry leaves only under
 *       known {@link DocTypePack#SPINE_ROOTS} with layer=industry.</li>
 *   <li><b>Cross-document consensus</b> — required only when the field appears in
 *       ≥ N cluster docs. A single document ({@code N = 1}) still keeps a field,
 *       table, clause, or passage that passes {@link EntityTokenFilter}, sits under
 *       a spine root, and is evidenced or marked as a domain standard. Non-spine
 *       leaves on {@code N = 1} are optional. One-off company facts never enter
 *       the pack.</li>
 *   <li><b>Nomenclature</b> — populate identity_signals and field prompt_hint synonyms
 *       from labels across the cluster.</li>
 * </ol>
 */
public final class Generalizer {

    /** Default consensus threshold for multi-document clusters. */
    public static final int DEFAULT_CONSENSUS_N = 2;

    /** Generalization configuration. */
    public record Config(
            int consensusN,
            Set<String> sampleEntityTokens,
            String industryTag) {

        public Config {
            consensusN = consensusN < 1 ? DEFAULT_CONSENSUS_N : consensusN;
            sampleEntityTokens = sampleEntityTokens == null
                    ? Set.of()
                    : Set.copyOf(sampleEntityTokens);
            industryTag = industryTag == null ? "" : industryTag;
        }

        /** Default config with the given sample entity tokens. */
        public static Config defaults(Set<String> sampleEntityTokens) {
            return new Config(DEFAULT_CONSENSUS_N, sampleEntityTokens, "");
        }
    }

    /** One surviving pack field plus its nomenclature evidence. */
    public record GeneralizedField(
            PackField field,
            List<String> sourceLabels,
            int docCount,
            boolean spineLeaf,
            boolean industryLeaf) {
        public GeneralizedField {
            sourceLabels = sourceLabels == null ? List.of() : List.copyOf(sourceLabels);
        }
    }

    /** One promotion, rewrite, demotion, or drop. */
    public record DecisionNote(String candidateKey, String outcome, String reasonCode, String rationale) {}

    /** Result of generalizing one mined cluster. */
    public record Result(
            List<GeneralizedField> fields,
            List<String> identitySignals,
            List<String> dropped,
            List<String> rewritten,
            List<String> notes,
            List<Map<String, Object>> tables,
            ClauseSection clauseSection,
            List<PackPassage> passages,
            String documentDateKind,
            List<DecisionNote> decisions) {
        public Result {
            fields = fields == null ? List.of() : List.copyOf(fields);
            identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
            rewritten = rewritten == null ? List.of() : List.copyOf(rewritten);
            notes = notes == null ? List.of() : List.copyOf(notes);
            tables = tables == null ? List.of() : List.copyOf(tables);
            clauseSection = clauseSection == null ? ClauseSection.NONE : clauseSection;
            passages = passages == null ? List.of() : List.copyOf(passages);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }

        /** Pack fields only. */
        public List<PackField> packFields() {
            return fields.stream().map(GeneralizedField::field).toList();
        }
    }

    /**
     * Applies the four deterministic filters to a mined cluster schema.
     *
     * @param mined            LLM mining output (required)
     * @param clusterDocCount  number of documents in the cluster (≥ 1)
     * @param config           consensus + entity filter configuration
     * @return generalized fields, identity signals, and an audit trail
     */
    public Result generalize(
            PackMiningClient.MiningResult mined,
            int clusterDocCount,
            Config config) {
        Objects.requireNonNull(mined, "mined");
        Objects.requireNonNull(config, "config");
        int docs = Math.max(1, clusterDocCount);
        EntityTokenFilter entityFilter = new EntityTokenFilter(config.sampleEntityTokens());
        List<String> dropped = new ArrayList<>();
        List<String> rewritten = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<DecisionNote> decisions = new ArrayList<>();
        mined = NestedScheduleNormalizer.rewrite(mined, decisions, dropped);
        Map<String, GeneralizedField> kept = new LinkedHashMap<>();

        for (PackMiningClient.MinedField f : mined.fields()) {
            String rawKey = f.key() == null ? "" : f.key().trim();
            if (rawKey.isEmpty()) {
                dropped.add("(unnamed): empty key");
                decisions.add(note("(unnamed)", "dropped", "empty_key", "Empty field key."));
                continue;
            }

            // --- 1. label-not-value ---
            if (entityFilter.isEntityToken(rawKey)) {
                dropped.add(rawKey + ": key is an entity/value token");
                decisions.add(note(rawKey, "dropped", "entity_token", "Key is an entity or value token."));
                continue;
            }
            String labelKey = entityFilter.rewriteKey(rawKey);
            if (labelKey.isEmpty()) {
                dropped.add(rawKey + ": key is only entity/value tokens");
                decisions.add(note(rawKey, "dropped", "entity_token", "Key is only entity or value tokens."));
                continue;
            }
            String pathCandidate = f.path() == null ? "" : f.path().trim();
            if (pathCandidate.isEmpty()) {
                pathCandidate = rawKey;
            }
            if (entityFilter.isEntityToken(pathCandidate)
                    && SpineCatalog.matchLabel(pathCandidate).isEmpty()) {
                dropped.add(rawKey + ": path is an entity/value token");
                decisions.add(note(rawKey, "dropped", "entity_token", "Path is an entity or value token."));
                continue;
            }

            String cleanKey = labelKey;
            if (!cleanKey.equals(rawKey) && !cleanKey.equalsIgnoreCase(rawKey)) {
                rewritten.add(rawKey + " -> " + cleanKey);
            }

            // path: strip entity tokens then spine-map
            String strippedPath = entityFilter.strip(pathCandidate);
            String pathLabelSource = strippedPath.isEmpty() ? cleanKey : strippedPath.replace('_', ' ');
            if (!strippedPath.isEmpty() && !entityFilter.containsEntity(pathCandidate)
                    && !strippedPath.equalsIgnoreCase(SpineCatalog.normalize(pathCandidate))) {
                // path had entity noise removed
                if (!rewritten.contains(rawKey + " path " + pathCandidate + " -> " + strippedPath)) {
                    rewritten.add("path " + pathCandidate + " -> " + strippedPath);
                }
            }

            // --- 2. spine-first ---
            // Prefer the proposed path when it is already a frozen spine leaf.
            Optional<SpineCatalog.SpineLeaf> spine = Optional.empty();
            String rawPathNorm = SpineCatalog.normalize(pathCandidate);
            for (SpineCatalog.SpineLeaf leaf : SpineCatalog.leaves()) {
                if (SpineCatalog.normalize(leaf.path()).equals(rawPathNorm)) {
                    spine = Optional.of(leaf);
                    break;
                }
            }
            if (spine.isEmpty()) {
                spine = SpineCatalog.matchLabel(pathLabelSource)
                        .or(() -> SpineCatalog.matchLabel(cleanKey.replace('_', ' ')))
                        .or(() -> SpineCatalog.matchLabel(joinLabels(f.labels())));
            }
            boolean spineLeaf = spine.isPresent();
            String path;
            String pathRoot;
            boolean industryLeaf = false;
            if (spineLeaf) {
                path = spine.get().path();
                pathRoot = spine.get().pathRoot();
            } else {
                String proposedRoot = f.pathRoot() == null ? "" : f.pathRoot().toLowerCase(Locale.ROOT).trim();
                if (proposedRoot.isEmpty()) {
                    // try to take the root from the original path
                    int sep = pathLabelSource.indexOf('>');
                    if (sep < 0) {
                        sep = pathLabelSource.indexOf(' ');
                    }
                    proposedRoot = sep > 0 ? pathLabelSource.substring(0, sep).trim() : "";
                }
                if (!SpineCatalog.isKnownRoot(proposedRoot)) {
                    // last chance: root from raw path's first segment
                    String first = pathCandidate.split(">")[0].trim().toLowerCase(Locale.ROOT);
                    if (SpineCatalog.isKnownRoot(first)) {
                        proposedRoot = first;
                    } else {
                        dropped.add(rawKey + ": cannot map to a spine leaf and root '"
                                + proposedRoot + "' is not a known spine root");
                        decisions.add(note(rawKey, "dropped", "unknown_root",
                                "Root '" + proposedRoot + "' is not one of the seven spine roots."));
                        continue;
                    }
                }
                pathRoot = proposedRoot;
                String leafName = leafNameOf(pathLabelSource, cleanKey);
                path = pathRoot + " > " + leafName;
                industryLeaf = true;
                notes.add(rawKey + ": industry leaf under " + pathRoot + " (layer=industry)");
            }

            ValueKind kind = parseKind(f.valueKind());

            // --- 3. cross-document consensus ---
            List<String> docIds = f.docIds() == null ? List.of() : f.docIds().stream()
                    .filter(d -> d != null && !d.isBlank())
                    .distinct()
                    .toList();
            int docCount = docIds.isEmpty() ? docs : docIds.size();
            int threshold = docs == 1 ? 1 : config.consensusN();
            boolean domainStandard = f.domainStandard();
            boolean evidenced = !docIds.isEmpty() || domainStandard;
            if (docs == 1 && !spineLeaf && !evidenced) {
                dropped.add(rawKey + ": single-doc cluster and label is not a spine leaf (one-off)");
                decisions.add(note(rawKey, "dropped", "not_evidenced",
                        "Single-document field is not a spine leaf and is not evidenced."));
                continue;
            }
            if (docs > 1 && docCount < threshold) {
                dropped.add(rawKey + ": one-off (appeared in " + docCount + "/" + docs
                        + " docs; need ≥ " + threshold + ")");
                decisions.add(note(rawKey, "dropped", "below_consensus",
                        "Appeared in " + docCount + " of " + docs + " documents."));
                continue;
            }

            // required only if consensus met AND LLM did not mark optional.
            // Non-spine leaves on a single document stay optional.
            boolean llmRequired = f.required() == null || f.required();
            boolean demoteOptional = docs == 1 && !spineLeaf && llmRequired;
            boolean required = demoteOptional ? false : llmRequired && (docs == 1 || docCount >= threshold);

            // --- 4. nomenclature ---
            List<String> labels = new ArrayList<>();
            for (String label : f.labels()) {
                if (label != null && !label.isBlank() && !entityFilter.containsEntity(label)) {
                    labels.add(label.trim());
                }
            }
            for (String syn : f.synonyms()) {
                if (syn != null && !syn.isBlank() && !entityFilter.containsEntity(syn)
                        && !labels.contains(syn.trim())) {
                    labels.add(syn.trim());
                }
            }
            String cleanLeaf = spineLeaf ? spine.get().leafName() : leafNameOf(pathLabelSource, cleanKey);
            if (!labels.stream().anyMatch(l -> SpineCatalog.normalize(l).equals(SpineCatalog.normalize(cleanLeaf)))) {
                labels.add(0, cleanLeaf);
            }
            labels = dedupe(labels);

            String promptHint = f.promptHint();
            if (promptHint == null || promptHint.isBlank()) {
                promptHint = cleanLeaf;
            } else if (entityFilter.containsEntity(promptHint)) {
                promptHint = entityFilter.strip(promptHint);
                if (promptHint.isBlank()) {
                    promptHint = cleanLeaf;
                } else {
                    promptHint = promptHint.replace('_', ' ');
                }
            }

            List<String> slots = slotsFor(kind);
            PackField field = new PackField(
                    cleanKey,
                    path,
                    pathRoot,
                    kind,
                    required,
                    slots,
                    promptHint,
                    labels.stream().filter(l -> !l.equalsIgnoreCase(cleanLeaf)).toList());

            String mapKey = cleanKey;
            if (kept.containsKey(mapKey)) {
                GeneralizedField prev = kept.get(mapKey);
                // merge: union labels/docCount, prefer spine path already set
                List<String> mergedLabels = dedupe(merge(prev.sourceLabels(), labels));
                kept.put(mapKey, new GeneralizedField(
                        prev.field(),
                        mergedLabels,
                        Math.max(prev.docCount(), docCount),
                        prev.spineLeaf(),
                        prev.industryLeaf()));
            } else {
                kept.put(mapKey, new GeneralizedField(
                        field, labels, docCount, spineLeaf, industryLeaf));
            }
            boolean keyRewritten = !cleanKey.equals(rawKey) && !cleanKey.equalsIgnoreCase(rawKey);
            if (keyRewritten) {
                decisions.add(note(cleanKey, "rewritten", "label_not_value",
                        "Key rewritten from '" + rawKey + "'."));
            }
            if (demoteOptional) {
                decisions.add(note(cleanKey, "demoted_optional",
                        domainStandard ? "domain_standard" : "single_document_industry_leaf",
                        "Non-spine leaf on a single document is optional."));
            } else if (!keyRewritten) {
                decisions.add(note(cleanKey, "promoted",
                        spineLeaf ? "spine_leaf" : (domainStandard ? "domain_standard" : "evidenced"),
                        spineLeaf
                                ? "Spine leaf kept."
                                : "Domain field kept under " + pathRoot + "."));
            }
        }

        List<String> identitySignals = nomenclatureSignals(
                mined.identitySignals(), mined.fields(), entityFilter, notes);
        List<Map<String, Object>> tables = generalizeTables(
                mined, docs, entityFilter, decisions, dropped);
        ClauseSection clauses = generalizeClauses(mined, docs, entityFilter, decisions, dropped);
        String dateKind = dateKind(mined.documentDateKind());
        Set<String> knownKeys = new LinkedHashSet<>();
        for (GeneralizedField field : kept.values()) {
            knownKeys.add(field.field().key());
        }
        for (Map<String, Object> table : tables) {
            Object name = table.get("name");
            if (name != null) {
                knownKeys.add(String.valueOf(name));
            }
        }
        List<PackPassage> passages = generalizePassages(
                mined, docs, entityFilter, knownKeys, decisions, dropped);

        return new Result(
                List.copyOf(kept.values()),
                identitySignals,
                dropped,
                rewritten,
                notes,
                tables,
                clauses,
                passages,
                dateKind,
                decisions);
    }

    static DecisionNote note(String key, String outcome, String reason, String rationale) {
        return new DecisionNote(key, outcome, InductionReasonCodes.canonical(reason), rationale);
    }

    private static DecisionNote drop(String key, String reason, String rationale) {
        return note(key, "dropped", reason, rationale);
    }

    private static List<Map<String, Object>> generalizeTables(
            PackMiningClient.MiningResult mined,
            int docs,
            EntityTokenFilter filter,
            List<DecisionNote> decisions,
            List<String> dropped) {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (PackMiningClient.MinedTable table : mined.tables()) {
            String rawName = table.name() == null ? "" : table.name().trim();
            String name = filter.rewriteKey(rawName);
            if (name.isEmpty() || filter.isEntityToken(rawName)) {
                dropped.add(rawName + ": table name is an entity/value token");
                decisions.add(drop(rawName.isEmpty() ? "(table)" : rawName, "entity_token",
                        "Table name is an entity or value token."));
                continue;
            }
            if (!keepStructure(docs, table.docIds(), table.domainStandard())) {
                dropped.add(name + ": table is not evidenced");
                decisions.add(drop(name, "not_evidenced", "Table is not evidenced on this cluster."));
                continue;
            }
            String root = rootOf(table.pathRoot(), table.path());
            if (!SpineCatalog.isKnownRoot(root)) {
                dropped.add(name + ": table root is not a spine root");
                decisions.add(drop(name, "unknown_root", "Table root is not one of the seven spine roots."));
                continue;
            }
            List<Map<String, Object>> columns = new ArrayList<>();
            LinkedHashSet<String> columnKeys = new LinkedHashSet<>();
            for (PackMiningClient.MinedColumn column : table.columns()) {
                String key = filter.rewriteKey(column.key() == null ? "" : column.key());
                if (key.isEmpty() || !columnKeys.add(key)) {
                    continue;
                }
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("key", key);
                col.put("value_kind", parseKind(column.valueKind()).wireName());
                columns.add(col);
            }
            if (columns.isEmpty()) {
                dropped.add(name + ": table has no columns");
                decisions.add(drop(name, "no_columns", "Table has no usable columns."));
                continue;
            }
            String keyColumn = columnOrDefault(filter.rewriteKey(table.keyColumn()), columnKeys, columns);
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("name", name);
            mapped.put("path", structurePath(table.path(), root, name));
            mapped.put("path_root", root);
            mapped.put("key_column", keyColumn);
            putRole(mapped, "entity_key", filter.rewriteKey(table.entityKey()), columnKeys);
            putRole(mapped, "site_key", filter.rewriteKey(table.siteKey()), columnKeys);
            putRole(mapped, "period_key", filter.rewriteKey(table.periodKey()), columnKeys);
            mapped.put("fields", columns);
            tables.add(mapped);
            decisions.add(note(name, "promoted",
                    table.domainStandard() ? "domain_standard" : "evidenced",
                    "Schedule table kept under " + root + "."));
        }
        return tables;
    }

    private static ClauseSection generalizeClauses(
            PackMiningClient.MiningResult mined,
            int docs,
            EntityTokenFilter filter,
            List<DecisionNote> decisions,
            List<String> dropped) {
        List<PackClauseType> types = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (PackMiningClient.MinedClause clause : mined.clauses()) {
            String rawKey = clause.key() == null ? "" : clause.key().trim();
            String key = filter.rewriteKey(rawKey);
            if (key.isEmpty() || filter.isEntityToken(rawKey) || !seen.add(key)) {
                dropped.add(rawKey + ": clause key is an entity/value token");
                decisions.add(drop(rawKey.isEmpty() ? "(clause)" : rawKey, "entity_token",
                        "Clause key is an entity or value token."));
                continue;
            }
            if (!keepStructure(docs, clause.docIds(), clause.domainStandard())) {
                dropped.add(key + ": clause is not evidenced");
                decisions.add(drop(key, "not_evidenced", "Clause is not evidenced on this cluster."));
                continue;
            }
            String label = clause.label() == null ? "" : filter.redact(clause.label()).trim();
            if (label.isEmpty() || filter.isEntityToken(label)) {
                label = key.replace('_', ' ');
            }
            List<String> aliases = new ArrayList<>();
            for (String alias : clause.aliases()) {
                if (alias == null || alias.isBlank() || filter.containsEntity(alias)) {
                    continue;
                }
                String cleaned = alias.trim();
                if (!aliases.contains(cleaned)) {
                    aliases.add(cleaned);
                }
            }
            String family = clause.parentFamily() == null || clause.parentFamily().isBlank()
                    ? null
                    : filter.rewriteKey(clause.parentFamily());
            if (family != null && family.isBlank()) {
                family = null;
            }
            types.add(new PackClauseType(key, label, family, aliases));
            decisions.add(note(key, "promoted",
                    clause.domainStandard() ? "domain_standard" : "evidenced",
                    "Clause type kept."));
        }
        if (types.isEmpty()) {
            return ClauseSection.NONE;
        }
        return new ClauseSection(true, true, types);
    }

    private static List<PackPassage> generalizePassages(
            PackMiningClient.MiningResult mined,
            int docs,
            EntityTokenFilter filter,
            Set<String> knownKeys,
            List<DecisionNote> decisions,
            List<String> dropped) {
        List<PackPassage> passages = new ArrayList<>();
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (PackMiningClient.MinedPassage passage : mined.passages()) {
            String topic = filter.rewriteKey(passage.topic() == null ? "" : passage.topic());
            if (topic.isEmpty() || !topics.add(topic)) {
                dropped.add(topic + ": passage topic rejected");
                decisions.add(drop(topic.isEmpty() ? "(passage)" : topic, "entity_token",
                        "Passage topic is empty or an entity token."));
                continue;
            }
            if (!keepStructure(docs, passage.docIds(), passage.domainStandard())) {
                dropped.add(topic + ": passage is not evidenced");
                decisions.add(drop(topic, "not_evidenced", "Passage is not evidenced on this cluster."));
                continue;
            }
            List<String> terms = new ArrayList<>();
            for (String term : passage.headingTerms()) {
                if (term == null || term.isBlank() || filter.containsEntity(term)) {
                    continue;
                }
                String cleaned = term.trim();
                if (!terms.contains(cleaned)) {
                    terms.add(cleaned);
                }
            }
            if (terms.isEmpty()) {
                terms.add(topic.replace('_', ' '));
            }
            List<String> refs = new ArrayList<>();
            for (String field : passage.fields()) {
                if (field != null && knownKeys.contains(field) && !refs.contains(field)) {
                    refs.add(field);
                }
            }
            if (refs.isEmpty()) {
                dropped.add(topic + ": passage names no declared field or table");
                decisions.add(drop(topic, "no_field", "Passage names no declared field or table."));
                continue;
            }
            passages.add(new PackPassage(topic, terms, refs));
            decisions.add(note(topic, "promoted",
                    passage.domainStandard() ? "domain_standard" : "evidenced",
                    "Passage topic kept."));
        }
        return passages;
    }

    private static boolean keepStructure(int docs, List<String> docIds, boolean domainStandard) {
        boolean evidenced = (docIds != null && !docIds.isEmpty()) || domainStandard;
        if (docs == 1) {
            return evidenced;
        }
        if (domainStandard || docIds == null || docIds.isEmpty()) {
            return true;
        }
        return docIds.size() >= 2 || docs < 2;
    }

    private static String rootOf(String pathRoot, String path) {
        if (pathRoot != null && SpineCatalog.isKnownRoot(pathRoot)) {
            return pathRoot.toLowerCase(Locale.ROOT).trim();
        }
        if (path != null && path.contains(">")) {
            String first = path.split(">")[0].trim();
            if (SpineCatalog.isKnownRoot(first)) {
                return first.toLowerCase(Locale.ROOT);
            }
        }
        return pathRoot == null ? "" : pathRoot.toLowerCase(Locale.ROOT).trim();
    }

    private static String structurePath(String path, String root, String name) {
        Optional<SpineCatalog.SpineLeaf> spine = SpineCatalog.matchLabel(path);
        if (spine.isPresent() && spine.get().pathRoot().equals(root)) {
            return spine.get().path();
        }
        if (path != null && path.toLowerCase(Locale.ROOT).startsWith(root + " ")) {
            return path.trim();
        }
        return root + " > " + leafNameOf(name.replace('_', ' '), name);
    }

    private static String columnOrDefault(
            String requested, Set<String> columnKeys, List<Map<String, Object>> columns) {
        if (requested != null && columnKeys.contains(requested)) {
            return requested;
        }
        return String.valueOf(columns.get(0).get("key"));
    }

    private static void putRole(
            Map<String, Object> table, String role, String column, Set<String> columnKeys) {
        if (column != null && !column.isBlank() && columnKeys.contains(column)) {
            table.put(role, column);
        }
    }

    private static String dateKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String kind = raw.trim().toLowerCase(Locale.ROOT);
        if (!DocTypePack.DOCUMENT_DATE_KINDS.contains(kind)) {
            return null;
        }
        return kind;
    }

    /**
     * Builds pack identity_signals from mined signals + titles/labels across the cluster,
     * stripping entity/value tokens (S2.11 nomenclature).
     */
    public static List<String> nomenclatureSignals(
            List<String> minedSignals,
            List<PackMiningClient.MinedField> fields,
            EntityTokenFilter entityFilter,
            List<String> notes) {
        List<String> out = new ArrayList<>();
        EntityTokenFilter filter = entityFilter == null ? EntityTokenFilter.none() : entityFilter;
        if (minedSignals != null) {
            for (String s : minedSignals) {
                if (s == null || s.isBlank() || filter.containsEntity(s)) {
                    continue;
                }
                String t = s.trim();
                if (!out.stream().anyMatch(o -> o.equalsIgnoreCase(t))) {
                    out.add(t);
                }
            }
        }
        if (fields != null) {
            for (PackMiningClient.MinedField f : fields) {
                for (String label : f.labels()) {
                    if (label == null || label.isBlank() || filter.containsEntity(label)) {
                        continue;
                    }
                    // short generic labels are identity-signal candidates only when
                    // they look like document-type titles (contain spaces or are known)
                    String t = label.trim();
                    if (t.length() < 4) {
                        continue;
                    }
                    if (!out.stream().anyMatch(o -> o.equalsIgnoreCase(t))) {
                        out.add(t);
                    }
                }
            }
        }
        // cap signals so packs stay small
        if (out.size() > 12) {
            if (notes != null) {
                notes.add("identity_signals capped at 12 (from " + out.size() + ")");
            }
            out = new ArrayList<>(out.subList(0, 12));
        }
        return List.copyOf(out);
    }

    /** Maps a mined value_kind wire name; unknown kinds become {@code text}. */
    public static ValueKind parseKind(String wire) {
        if (wire == null || wire.isBlank()) {
            return ValueKind.TEXT;
        }
        try {
            return ValueKind.fromWire(wire);
        } catch (IllegalArgumentException e) {
            return ValueKind.TEXT;
        }
    }

    private static String leafNameOf(String pathLabelSource, String cleanKey) {
        String src = pathLabelSource == null ? "" : pathLabelSource.trim();
        int sep = src.lastIndexOf('>');
        if (sep >= 0) {
            src = src.substring(sep + 1).trim();
        }
        if (src.isBlank()) {
            src = cleanKey.replace('_', ' ');
        }
        src = SpineCatalog.normalize(src);
        if (src.isEmpty()) {
            return "field";
        }
        // Title Case multi-word leaf names for industry leaves
        String[] words = src.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static List<String> slotsFor(ValueKind kind) {
        return switch (kind) {
            case MONEY -> List.of("currency", "scale");
            case QUANTITY -> List.of("unit");
            case RATE -> List.of("unit");
            case PERCENT -> List.of();
            default -> List.of();
        };
    }

    private static String joinLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        return String.join(" ", labels);
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            for (String s : b) {
                if (!out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static List<String> dedupe(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String key = s.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
