package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.pack.RowDisambiguation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Automated induction quality gates (S2.11). Auto-publish only when every gate passes.
 * Failed gates leave the corpus extractable and report why.
 */
public final class PackQualityGates {

    /** Default dry-run required-field coverage threshold. */
    public static final double DEFAULT_MIN_REQUIRED_COVERAGE = 0.6;

    /** One named gate outcome. */
    public record GateResult(String name, boolean passed, String message) {
        /** Alias for {@link #message()}. */
        public String detail() {
            return message;
        }
    }

    /**
     * Dry-run extract coverage probe: which pack field keys resolved on which sample doc.
     * When no probe is supplied, coverage is derived from mining appearances.
     */
    public record CoverageProbe(Map<String, Set<String>> resolvedByDocId) {

        public CoverageProbe {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            if (resolvedByDocId != null) {
                resolvedByDocId.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            }
            resolvedByDocId = Map.copyOf(copy);
        }

        /**
         * Fraction of {@code requiredKeys} that resolve on at least one of {@code docIds}.
         */
        public double coverage(Collection<String> requiredKeys, Collection<String> docIds) {
            if (requiredKeys == null || requiredKeys.isEmpty()) {
                return 0.0;
            }
            Set<String> scope = docIds == null ? Set.of() : Set.copyOf(docIds);
            int hit = 0;
            for (String key : requiredKeys) {
                if (resolves(key, scope)) {
                    hit++;
                }
            }
            return (double) hit / requiredKeys.size();
        }

        /** True when {@code key} resolves on any doc in {@code scope} (or globally if empty). */
        public boolean resolves(String key, Collection<String> scope) {
            for (Map.Entry<String, Set<String>> e : resolvedByDocId.entrySet()) {
                if (scope != null && !scope.isEmpty() && !scope.contains(e.getKey())) {
                    continue;
                }
                if (e.getValue().contains(key)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Marks keys as already resolved on the given documents. Widening uses this
         * so a required path kept from the previous pack does not fail coverage.
         */
        public CoverageProbe plus(Collection<String> keys, Collection<String> docIds) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : resolvedByDocId.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
            Collection<String> ids = docIds == null || docIds.isEmpty() ? copy.keySet() : docIds;
            for (String id : ids) {
                Set<String> resolved = copy.computeIfAbsent(id, key -> new LinkedHashSet<>());
                if (keys != null) {
                    resolved.addAll(keys);
                }
            }
            return new CoverageProbe(copy);
        }
    }

    /** Full gate report. */
    public record GateReport(
            List<GateResult> gates,
            double requiredCoverage,
            boolean optionalHeavy,
            boolean allPassed,
            List<String> rejectReasons) {
        public GateReport {
            gates = gates == null ? List.of() : List.copyOf(gates);
            rejectReasons = rejectReasons == null ? List.of() : List.copyOf(rejectReasons);
        }

        public OptionalLike gate(String name) {
            for (GateResult g : gates) {
                if (g.name().equals(name)) {
                    return new OptionalLike(g);
                }
            }
            return new OptionalLike(null);
        }
    }

    /** Tiny optional wrapper so callers can fetch a gate without Optional import noise. */
    public record OptionalLike(GateResult value) {
        public boolean isPresent() {
            return value != null;
        }
    }

    /**
     * Runs every quality gate against a candidate pack.
     *
     * @param pack             candidate pack (may be empty; gates will fail)
     * @param probe            dry-run coverage probe (required)
     * @param minCoverage      required-field coverage threshold (default {@code 0.6})
     * @param sampleEntities   entity tokens that must not appear in field keys
     * @param clusterDocIds    sample document ids in the cluster
     * @return gate outcomes; {@code allPassed} is true only when every gate passes
     */
    public static GateReport evaluate(
            DocTypePack pack,
            CoverageProbe probe,
            double minCoverage,
            Set<String> sampleEntities,
            List<String> clusterDocIds) {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(probe, "probe");
        double threshold = minCoverage <= 0 ? DEFAULT_MIN_REQUIRED_COVERAGE : minCoverage;
        Set<String> entities = sampleEntities == null ? Set.of() : Set.copyOf(sampleEntities);
        List<String> docs = clusterDocIds == null ? List.of() : List.copyOf(clusterDocIds);

        List<GateResult> gates = new ArrayList<>();
        List<String> rejectReasons = new ArrayList<>();

        // Gate 1: PackLoader + toExtractSchema compiles
        boolean compiles = false;
        String compileDetail;
        try {
            String yaml = PackYamlWriter.write(pack);
            DocTypePack reloaded = PackLoader.load(
                    new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    "induced-pack");
            reloaded.toExtractSchema();
            compiles = true;
            compileDetail = "PackLoader + toExtractSchema ok (" + reloaded.fields().size() + " fields)";
        } catch (PackLoader.PackValidationException | IOException | RuntimeException e) {
            compileDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        gates.add(new GateResult("pack_schema_compiles", compiles, compileDetail));
        if (!compiles) {
            rejectReasons.add("pack_schema_compiles: " + compileDetail);
        }

        // Gate 2: reload from store is byte-identical
        boolean byteIdentical = false;
        String byteDetail;
        try {
            byte[] a = PackYamlWriter.writeBytes(pack);
            String yaml = new String(a, StandardCharsets.UTF_8);
            DocTypePack reloaded = PackLoader.load(
                    new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    "induced-pack");
            byte[] b = PackYamlWriter.writeBytes(reloaded);
            byteIdentical = java.util.Arrays.equals(a, b);
            byteDetail = byteIdentical
                    ? "reload byte-identical (" + a.length + " bytes)"
                    : "reload differs (" + a.length + " vs " + b.length + " bytes)";
        } catch (IOException | PackLoader.PackValidationException | RuntimeException e) {
            byteDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        gates.add(new GateResult("reload_byte_identical", byteIdentical, byteDetail));
        if (!byteIdentical) {
            rejectReasons.add("reload_byte_identical: " + byteDetail);
        }

        // Gate 3: required fields ≥ 1 and each has path + value_kind
        List<PackField> required = pack.requiredFields();
        boolean requiredOk = !required.isEmpty();
        List<String> requiredProblems = new ArrayList<>();
        if (required.isEmpty()) {
            requiredProblems.add("no required fields");
        }
        for (PackField f : pack.fields()) {
            if (f.path() == null || f.path().isBlank()) {
                requiredProblems.add(f.key() + ": empty path");
                requiredOk = false;
            }
            if (f.valueKind() == null) {
                requiredProblems.add(f.key() + ": missing value_kind");
                requiredOk = false;
            }
        }
        for (String problem : tableColumnKindProblems(pack)) {
            requiredProblems.add(problem);
            requiredOk = false;
        }
        String requiredDetail = requiredOk
                ? required.size() + " required field(s), each with path + value_kind"
                : String.join("; ", requiredProblems);
        gates.add(new GateResult("required_fields_valid", requiredOk, requiredDetail));
        if (!requiredOk) {
            rejectReasons.add("required_fields_valid: " + requiredDetail);
        }

        // Gate 4: dry-run extract coverage
        List<String> requiredKeys = required.stream().map(PackField::key).toList();
        double coverage = probe.coverage(requiredKeys, docs);
        boolean someResolve = requiredKeys.stream().anyMatch(k -> probe.resolves(k, docs));
        boolean coverageOk = coverage >= threshold || (someResolve && coverage > 0);
        boolean optionalHeavy = coverageOk && coverage < threshold && someResolve;
        String coverageDetail;
        if (coverage >= threshold) {
            coverageDetail = String.format("required coverage %.2f ≥ %.2f", coverage, threshold);
        } else if (someResolve) {
            coverageDetail = String.format(
                    "required coverage %.2f < %.2f — publishing optional-heavy (lower required flags)",
                    coverage, threshold);
        } else {
            coverageDetail = String.format(
                    "zero required fields resolve (coverage %.2f < %.2f)", coverage, threshold);
        }
        gates.add(new GateResult("required_coverage", coverageOk, coverageDetail));
        if (!coverageOk) {
            rejectReasons.add("required_coverage: " + coverageDetail);
        }

        // Gate 5: no field key contains sample entity name / PAN / CIN
        EntityTokenFilter entityFilter = new EntityTokenFilter(entities);
        boolean keysClean = true;
        List<String> dirty = new ArrayList<>();
        for (PackField f : pack.fields()) {
            if (entityFilter.containsEntity(f.key()) || entityFilter.isEntityToken(f.key())) {
                dirty.add(f.key());
                keysClean = false;
            }
            if (EntityTokenFilter.PAN.matcher(f.key().toUpperCase(java.util.Locale.ROOT)).find()
                    || EntityTokenFilter.CIN.matcher(f.key().toUpperCase(java.util.Locale.ROOT)).find()) {
                dirty.add(f.key() + " (PAN/CIN)");
                keysClean = false;
            }
        }
        String keysDetail = keysClean
                ? "no entity/PAN/CIN tokens in field keys"
                : "entity tokens in keys: " + dirty;
        gates.add(new GateResult("no_entity_in_keys", keysClean, keysDetail));
        if (!keysClean) {
            rejectReasons.add("no_entity_in_keys: " + keysDetail);
        }

        // Gate 6: repeated labels (Total, Grand Total, …) must name which row (#157)
        List<String> ambiguous = RowDisambiguation.ambiguousSlots(pack);
        boolean rowHintsOk = ambiguous.isEmpty();
        String rowHintDetail = rowHintsOk
                ? "field instructions disambiguate repeated labels"
                : "ambiguous row instructions: " + ambiguous;
        gates.add(new GateResult("row_disambiguation", rowHintsOk, rowHintDetail));
        if (!rowHintsOk) {
            rejectReasons.add("row_disambiguation: " + rowHintDetail);
        }

        boolean allPassed = gates.stream().allMatch(GateResult::passed)
                && rejectReasons.isEmpty();
        // optional-heavy still passes overall (spec: publish as optional-heavy)
        if (optionalHeavy && gates.stream().filter(g -> !g.name().equals("required_coverage"))
                .allMatch(GateResult::passed)) {
            allPassed = true;
            rejectReasons = List.of();
        }

        return new GateReport(gates, coverage, optionalHeavy, allPassed, rejectReasons);
    }

    /**
     * Every table column that would publish must declare a closed-set {@code value_kind}.
     * Messages name {@code table.column} so a missing kind is actionable.
     */
    private static List<String> tableColumnKindProblems(DocTypePack pack) {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> table : pack.tables()) {
            Object nameObj = table.get("name");
            String tableName = nameObj == null || String.valueOf(nameObj).isBlank()
                    ? "table" : String.valueOf(nameObj);
            Object fieldsObj = table.get("fields");
            if (!(fieldsObj instanceof List<?> columns)) {
                continue;
            }
            for (Object raw : columns) {
                if (!(raw instanceof Map<?, ?> column)) {
                    continue;
                }
                Object keyObj = column.get("key");
                String colKey = keyObj == null || String.valueOf(keyObj).isBlank()
                        ? "(unnamed)" : String.valueOf(keyObj);
                String slot = tableName + "." + colKey;
                Object kindObj = column.get("value_kind");
                if (kindObj == null || String.valueOf(kindObj).isBlank()) {
                    problems.add(slot + ": missing value_kind");
                    continue;
                }
                try {
                    ValueKind.fromWire(String.valueOf(kindObj));
                } catch (IllegalArgumentException e) {
                    problems.add(slot + ": value_kind '" + kindObj + "' is not in the closed set");
                }
            }
        }
        return problems;
    }

    /**
     * Lowers required flags on every field (optional-heavy demotion after a soft coverage miss).
     */
    public static DocTypePack demoteRequired(DocTypePack pack) {
        List<PackField> fields = new ArrayList<>();
        for (PackField f : pack.fields()) {
            fields.add(new PackField(
                    f.key(), f.path(), f.pathRoot(), f.valueKind(),
                    false, f.slots(), f.promptHint(), f.synonyms()));
        }
        return new DocTypePack(
                pack.code(), pack.version(), pack.family(), pack.fileRole(),
                pack.industryTags(), pack.identitySignals(), fields,
                pack.tables(), pack.checklist(), pack.reportSections(),
                pack.documentDateKind(), pack.clauseSection(), pack.passages());
    }

    /**
     * Builds a coverage probe from mining appearances: each field resolves on the
     * documents listed in its {@code docIds}. When {@code docIds} is empty the field
     * resolves only if it carried sample values (then on all cluster docs); a field
     * with neither docIds nor sample values does not resolve (zero-coverage reject).
     */
    public static CoverageProbe probeFromMining(
            List<PackMiningClient.MinedField> mined,
            List<String> clusterDocIds,
            Set<String> generalizedKeys) {
        Map<String, Set<String>> byDoc = new LinkedHashMap<>();
        List<String> docs = clusterDocIds == null ? List.of() : clusterDocIds;
        for (String id : docs) {
            byDoc.put(id, new LinkedHashSet<>());
        }
        if (mined != null) {
            for (PackMiningClient.MinedField f : mined) {
                String key = f.key() == null ? "" : f.key().trim();
                String resolvedKey = matchGeneralized(key, generalizedKeys);
                if (resolvedKey == null) {
                    continue;
                }
                if (f.docIds().isEmpty() && f.sampleValues().isEmpty()) {
                    continue;
                }
                List<String> ids = f.docIds().isEmpty() ? docs : f.docIds();
                for (String id : ids) {
                    byDoc.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(resolvedKey);
                }
            }
        }
        return new CoverageProbe(byDoc);
    }

    private static String matchGeneralized(String rawKey, Set<String> generalizedKeys) {
        if (generalizedKeys == null || generalizedKeys.isEmpty()) {
            return null;
        }
        EntityTokenFilter filter = EntityTokenFilter.none();
        String label = filter.rewriteKey(rawKey);
        if (label != null && generalizedKeys.contains(label)) {
            return label;
        }
        if (generalizedKeys.contains(rawKey)) {
            return rawKey;
        }
        String n = SpineCatalog.normalize(rawKey).replace(' ', '_');
        for (String g : generalizedKeys) {
            if (g.equalsIgnoreCase(n) || SpineCatalog.normalize(g).replace(' ', '_').equals(n)) {
                return g;
            }
        }
        return null;
    }
}
