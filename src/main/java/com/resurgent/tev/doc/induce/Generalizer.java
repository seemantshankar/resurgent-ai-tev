package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
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
 *       ≥ N cluster docs (N=1 for a single-doc cluster when the label matches a spine
 *       leaf). One-off company facts never enter the pack.</li>
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

    /** Result of generalizing one mined cluster. */
    public record Result(
            List<GeneralizedField> fields,
            List<String> identitySignals,
            List<String> dropped,
            List<String> rewritten,
            List<String> notes) {
        public Result {
            fields = fields == null ? List.of() : List.copyOf(fields);
            identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
            rewritten = rewritten == null ? List.of() : List.copyOf(rewritten);
            notes = notes == null ? List.of() : List.copyOf(notes);
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
        Map<String, GeneralizedField> kept = new LinkedHashMap<>();

        for (PackMiningClient.MinedField f : mined.fields()) {
            String rawKey = f.key() == null ? "" : f.key().trim();
            if (rawKey.isEmpty()) {
                dropped.add("(unnamed): empty key");
                continue;
            }

            // --- 1. label-not-value ---
            if (entityFilter.isEntityToken(rawKey)) {
                dropped.add(rawKey + ": key is an entity/value token");
                continue;
            }
            String labelKey = entityFilter.rewriteKey(rawKey);
            if (labelKey.isEmpty()) {
                dropped.add(rawKey + ": key is only entity/value tokens");
                continue;
            }
            String pathCandidate = f.path() == null ? "" : f.path().trim();
            if (pathCandidate.isEmpty()) {
                pathCandidate = rawKey;
            }
            if (entityFilter.isEntityToken(pathCandidate)
                    && SpineCatalog.matchLabel(pathCandidate).isEmpty()) {
                dropped.add(rawKey + ": path is an entity/value token");
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
            boolean singleDocSpineOk = docs == 1 && spineLeaf;
            boolean consensusMet = docCount >= threshold || singleDocSpineOk;
            if (docs == 1 && !spineLeaf) {
                dropped.add(rawKey + ": single-doc cluster and label is not a spine leaf (one-off)");
                continue;
            }
            if (!consensusMet) {
                dropped.add(rawKey + ": one-off (appeared in " + docCount + "/" + docs
                        + " docs; need ≥ " + threshold + ")");
                continue;
            }

            // required only if consensus met AND LLM did not mark optional
            boolean llmRequired = f.required() == null || f.required();
            boolean required = consensusMet && llmRequired;

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
        }

        List<String> identitySignals = nomenclatureSignals(
                mined.identitySignals(), mined.fields(), entityFilter, notes);

        return new Result(
                List.copyOf(kept.values()),
                identitySignals,
                dropped,
                rewritten,
                notes);
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
