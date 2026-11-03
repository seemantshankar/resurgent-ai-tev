package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.EntityTokenFilter;
import com.resurgent.tev.doc.induce.PackQualityGates;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.induce.PackWidener;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Publishes optional scalar leaves learned from labeled-line bind (ADR 0036, #206).
 * Checked-in seed YAML is untouched; versions land under {@code {db-parent}/packs/}.
 */
public final class LabeledLinePackPublisher {

    private static final Set<String> SPINE_ROOTS = Set.of(
            "identity", "economic", "technical", "commercial", "legal", "regulatory", "frame");

    /** One bound labeled line that is not yet a pack field. */
    public record Candidate(
            String fieldKey,
            String packPath,
            String pathRoot,
            List<String> synonyms) {

        public Candidate {
            Objects.requireNonNull(fieldKey, "fieldKey");
            Objects.requireNonNull(packPath, "packPath");
            Objects.requireNonNull(pathRoot, "pathRoot");
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }
    }

    /** Outcome of a publish attempt at end of bind. */
    public record Outcome(
            boolean published,
            String packIdentity,
            List<String> rejectReasons,
            List<String> addedKeys) {

        public Outcome {
            rejectReasons = rejectReasons == null ? List.of() : List.copyOf(rejectReasons);
            addedKeys = addedKeys == null ? List.of() : List.copyOf(addedKeys);
        }
    }

    private LabeledLinePackPublisher() {}

    /**
     * Widens the matched pack with optional leaves and publishes when quality gates pass.
     *
     * @param dbPath workspace database path (pack store is {@code db-parent/packs})
     * @param mandateId mandate scope
     * @param extractPack pack used at extract for this run
     * @param parseRunId bind parse_run (for coverage probe)
     * @param candidates new fields to add (already bound on the living master)
     * @return publish outcome; gate failure leaves the store unchanged
     */
    public static Outcome publish(
            Path dbPath,
            long mandateId,
            DocTypePack extractPack,
            long parseRunId,
            List<Candidate> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) {
            return new Outcome(false, null, List.of(), List.of());
        }
        Path packsRoot = packStoreRoot(dbPath);
        PackStore store = new PackStore(packsRoot);
        DocTypePack base = resolveBase(store, extractPack);
        List<Candidate> eligible = inductionEligible(candidates, base);
        if (eligible.isEmpty()) {
            return new Outcome(false, null, List.of("no eligible labeled-line leaves"), List.of());
        }
        DocTypePack incoming = withNewFields(base, eligible);
        PackWidener.WidenResult widened = PackWidener.widen(base, incoming);
        long documentVersionId;
        Set<String> resolvedKeys;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            documentVersionId = documentVersionId(db.connection(), parseRunId);
            resolvedKeys = resolvedFieldKeys(db.connection(), parseRunId);
        }
        String docId = Long.toString(documentVersionId);
        PackQualityGates.CoverageProbe probe =
                new PackQualityGates.CoverageProbe(Map.of(docId, resolvedKeys));
        Set<String> sampleEntities = Set.of();
        PackQualityGates.GateReport report = evaluateWidenGates(
                widened.pack(), probe, sampleEntities, List.of(docId), eligible, resolvedKeys);
        if (!report.allPassed()) {
            return new Outcome(false, null, report.rejectReasons(), List.of());
        }
        DocTypePack toPublish = toPublishPack(widened.pack(), report);
        String industryTag = industryTag(store, toPublish);
        PackStore.PublishedPack published = store.publish(new PackStore.PublishRequest(
                toPublish,
                industryTag,
                null,
                List.of(Long.toString(mandateId)),
                "labeled-line:" + toPublish.code(),
                List.of(docId)));
        List<String> added = eligible.stream().map(Candidate::fieldKey).toList();
        return new Outcome(
                true,
                published.pack().code() + "@" + published.version(),
                List.of(),
                added);
    }

    /**
     * Quality gates for optional-leaf widen (ADR 0036, #206). Required paths on the
     * pack stay as they were; a single document may add optional leaves even when a
     * required field did not resolve on that same run (spec: one document is enough).
     * When coverage fails only for that reason and this run bound at least one new
     * labeled-line leaf, publish proceeds like induction's optional-heavy path.
     */
    private static PackQualityGates.GateReport evaluateWidenGates(
            DocTypePack widened,
            PackQualityGates.CoverageProbe probe,
            Set<String> sampleEntities,
            List<String> docIds,
            List<Candidate> eligible,
            Set<String> resolvedKeys) {
        PackQualityGates.GateReport report = PackQualityGates.evaluate(
                widened,
                probe,
                PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                sampleEntities,
                docIds);
        if (report.allPassed()) {
            return report;
        }
        if (!labeledLineWidenCoveragePass(report, eligible, resolvedKeys)) {
            return report;
        }
        if (report.optionalHeavy()) {
            DocTypePack demoted = PackQualityGates.demoteRequired(widened);
            PackQualityGates.GateReport second = PackQualityGates.evaluate(
                    demoted, probe, PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                    sampleEntities, docIds);
            if (second.allPassed() || coverageOnlyFailure(second)) {
                return new PackQualityGates.GateReport(
                        second.gates(), second.requiredCoverage(), true, true, List.of());
            }
            return second;
        }
        // Required field missing on this run, but a new optional labeled-line leaf bound.
        return new PackQualityGates.GateReport(
                report.gates(), report.requiredCoverage(), false, true, List.of());
    }

    private static DocTypePack toPublishPack(DocTypePack widened, PackQualityGates.GateReport report) {
        if (report.optionalHeavy()) {
            return PackQualityGates.demoteRequired(widened);
        }
        return widened;
    }

    private static boolean labeledLineWidenCoveragePass(
            PackQualityGates.GateReport report,
            List<Candidate> eligible,
            Set<String> resolvedKeys) {
        if (eligible.isEmpty()) {
            return false;
        }
        boolean anyNewLeafOnDoc = eligible.stream()
                .anyMatch(c -> resolvedKeys.contains(c.fieldKey()));
        if (!anyNewLeafOnDoc) {
            return false;
        }
        return coverageOnlyFailure(report);
    }

    private static boolean coverageOnlyFailure(PackQualityGates.GateReport report) {
        return !report.allPassed()
                && report.rejectReasons().stream().allMatch(r -> r.startsWith("required_coverage"));
    }

    private static DocTypePack resolveBase(PackStore store, DocTypePack extractPack) {
        DocTypePack stored = store.latestForCode(extractPack.code()).orElse(null);
        if (stored == null) {
            return extractPack;
        }
        if (PackStore.compareVersions(extractPack.version(), stored.version()) > 0) {
            return extractPack;
        }
        return stored;
    }

    private static List<Candidate> inductionEligible(List<Candidate> candidates, DocTypePack base) {
        Set<String> existing = new LinkedHashSet<>();
        for (PackField field : base.fields()) {
            existing.add(field.key());
        }
        EntityTokenFilter filter = EntityTokenFilter.none();
        List<Candidate> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (existing.contains(candidate.fieldKey())) {
                continue;
            }
            String root = candidate.pathRoot().toLowerCase(Locale.ROOT);
            if (!SPINE_ROOTS.contains(root)) {
                continue;
            }
            if (candidate.packPath() == null || candidate.packPath().isBlank()) {
                continue;
            }
            if (filter.isEntityToken(candidate.fieldKey())
                    || filter.containsEntity(candidate.fieldKey())) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    private static DocTypePack withNewFields(DocTypePack base, List<Candidate> candidates) {
        List<PackField> fields = new ArrayList<>(base.fields());
        for (Candidate candidate : candidates) {
            List<String> synonyms = new ArrayList<>(candidate.synonyms());
            fields.add(new PackField(
                    candidate.fieldKey(),
                    candidate.packPath(),
                    candidate.pathRoot(),
                    ValueKind.TEXT,
                    false,
                    List.of(),
                    null,
                    synonyms));
        }
        return new DocTypePack(
                base.code(),
                base.version(),
                base.family(),
                base.fileRole(),
                base.industryTags(),
                base.identitySignals(),
                List.copyOf(fields),
                base.tables(),
                base.checklist(),
                base.reportSections(),
                base.documentDateKind(),
                base.clauseSection(),
                base.passages());
    }

    private static String industryTag(PackStore store, DocTypePack pack) {
        return store.latestEntryForCode(pack.code())
                .map(PackStore.PackRegistryEntry::industryTag)
                .filter(tag -> tag != null && !tag.isBlank())
                .orElseGet(() -> pack.industryTags().isEmpty()
                        ? "general"
                        : pack.industryTags().get(0));
    }

    private static Path packStoreRoot(Path dbPath) {
        Path parent = dbPath.toAbsolutePath().getParent();
        return parent == null ? Path.of("packs") : parent.resolve("packs");
    }

    private static long documentVersionId(Connection connection, long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_version_id FROM fact WHERE parse_run_id = ? LIMIT 1")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("document_version_id");
                }
            }
        }
        throw new SQLException("no facts for parse_run " + parseRunId);
    }

    private static Set<String> resolvedFieldKeys(Connection connection, long parseRunId)
            throws SQLException {
        FactRepository facts = new FactRepository(connection);
        Set<String> keys = new LinkedHashSet<>();
        for (FactRepository.FactBindRow row : facts.selectFactsForParseRun(parseRunId)) {
            if (row.fieldKey() != null && !row.fieldKey().isBlank()) {
                keys.add(row.fieldKey());
            }
        }
        return keys;
    }
}
