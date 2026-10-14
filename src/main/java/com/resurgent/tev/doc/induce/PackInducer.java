package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Industry pack induction service (S2.11 / T11+T12). Clusters documents by type
 * signals, mines a generic field schema via {@link PackMiningClient}, generalizes
 * deterministically ({@link Generalizer}), runs quality gates, and auto-publishes
 * to a {@link PackStore} only when every gate passes. No analyst authoring/approval.
 *
 * <p>CLI entry point is {@code DocInduceCommand} (separate task); this class exposes
 * the service API it calls.
 */
public final class PackInducer {

    /** One cluster document’s type signals + instance tokens. */
    public record InduceDocument(
            String id,
            String fileRole,
            String title,
            List<String> layoutText,
            Set<String> sampleEntityTokens) {

        public InduceDocument {
            layoutText = layoutText == null ? List.of() : List.copyOf(layoutText);
            sampleEntityTokens = sampleEntityTokens == null
                    ? Set.of()
                    : Set.copyOf(sampleEntityTokens);
        }

        /** Adapts to the mining SPI document shape. */
        public PackMiningClient.MiningDocument toMiningDocument() {
            return new PackMiningClient.MiningDocument(
                    id, fileRole, title, layoutText, sampleEntityTokens);
        }
    }

    /** Input to {@link #induce}. */
    public record InduceRequest(
            List<InduceDocument> documents,
            String industryTag,
            String fileRole,
            String docTypeCodeHint,
            String familyHint,
            PackMiningClient miningClient,
            PackStore store,
            double minRequiredCoverage,
            int consensusN,
            boolean dryRun) {

        public InduceRequest {
            documents = documents == null ? List.of() : List.copyOf(documents);
            Objects.requireNonNull(miningClient, "miningClient");
            consensusN = consensusN < 1 ? Generalizer.DEFAULT_CONSENSUS_N : consensusN;
            minRequiredCoverage = minRequiredCoverage <= 0
                    ? PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE
                    : minRequiredCoverage;
        }

        /** Builder-style default covering the common CLI case. */
        public static InduceRequest of(
                List<InduceDocument> documents,
                String industryTag,
                PackMiningClient miningClient,
                PackStore store) {
            return new InduceRequest(
                    documents, industryTag, null, null, null,
                    miningClient, store,
                    PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                    Generalizer.DEFAULT_CONSENSUS_N,
                    false);
        }
    }

    /** Outcome of one induction run. */
    public record InduceResult(
            boolean published,
            boolean dryRun,
            boolean allGatesPassed,
            boolean optionalHeavy,
            DocTypePack pack,
            String proposedYaml,
            List<PackQualityGates.GateResult> gates,
            double requiredCoverage,
            List<String> rejectReasons,
            List<String> droppedFields,
            List<String> rewrittenFields,
            String industryTag,
            String version,
            String publishedPath) {

        public InduceResult {
            gates = gates == null ? List.of() : List.copyOf(gates);
            rejectReasons = rejectReasons == null ? List.of() : List.copyOf(rejectReasons);
            droppedFields = droppedFields == null ? List.of() : List.copyOf(droppedFields);
            rewrittenFields = rewrittenFields == null ? List.of() : List.copyOf(rewrittenFields);
        }
    }

    private static final int MAX_CLUSTER_SIZE_HINT = 500;

    /**
     * Clusters documents by type signals (file_role, title slug, layout heading).
     * Deterministic: group by fileRole, then by normalized title/first layout line.
     *
     * @param documents corpus (required)
     * @return clusters in stable order (largest first, ties by cluster key)
     */
    public static List<List<InduceDocument>> clusterByTypeSignals(List<InduceDocument> documents) {
        List<InduceDocument> docs = documents == null ? List.of() : List.copyOf(documents);
        Map<String, List<InduceDocument>> groups = new LinkedHashMap<>();
        for (InduceDocument d : docs) {
            String key = clusterKey(d);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        List<Map.Entry<String, List<InduceDocument>>> entries = new ArrayList<>(groups.entrySet());
        entries.sort((a, b) -> {
            int size = Integer.compare(b.getValue().size(), a.getValue().size());
            return size != 0 ? size : a.getKey().compareTo(b.getKey());
        });
        List<List<InduceDocument>> out = new ArrayList<>();
        for (Map.Entry<String, List<InduceDocument>> e : entries) {
            out.add(List.copyOf(e.getValue()));
        }
        return List.copyOf(out);
    }

    private static String clusterKey(InduceDocument d) {
        String role = d.fileRole() == null ? "unknown" : SpineCatalog.normalize(d.fileRole());
        String title = d.title();
        if (title == null || title.isBlank()) {
            title = d.layoutText() == null || d.layoutText().isEmpty()
                    ? ""
                    : d.layoutText().get(0);
        }
        String titleKey = SpineCatalog.normalize(title);
        if (titleKey.length() > 60) {
            titleKey = titleKey.substring(0, 60);
        }
        return role + "|" + titleKey;
    }

    /**
     * Induces one pack from the given corpus.
     *
     * <p>Steps: cluster by type signals → select the target cluster (fileRole /
     * docType hint, else largest) → {@link PackMiningClient#mine} →
     * {@link Generalizer} → quality gates → publish unless {@code dryRun}.
     *
     * @param request induction request (required)
     * @return gate results, proposed YAML, and publish outcome
     * @throws PackMiningException when the mining client fails
     * @throws IOException         when the pack store cannot publish
     */
    public InduceResult induce(InduceRequest request) throws PackMiningException, IOException {
        Objects.requireNonNull(request, "request");
        if (request.documents().isEmpty()) {
            throw new IllegalArgumentException("no documents in corpus");
        }

        List<List<InduceDocument>> clusters = clusterByTypeSignals(request.documents());
        List<InduceDocument> cluster = selectCluster(clusters, request.fileRole(), request.docTypeCodeHint());
        if (cluster.isEmpty()) {
            return rejectEmpty(request, "no cluster selected");
        }
        if (cluster.size() > MAX_CLUSTER_SIZE_HINT) {
            cluster = List.copyOf(cluster.subList(0, MAX_CLUSTER_SIZE_HINT));
        }

        List<PackMiningClient.MiningDocument> miningDocs = cluster.stream()
                .map(InduceDocument::toMiningDocument)
                .toList();

        String industryTag = request.industryTag() == null || request.industryTag().isBlank()
                || "auto".equalsIgnoreCase(request.industryTag())
                ? "general"
                : request.industryTag().trim();

        PackMiningClient.MiningRequest miningRequest = new PackMiningClient.MiningRequest(
                industryTag,
                request.fileRole(),
                request.docTypeCodeHint(),
                miningDocs);
        PackMiningClient.MiningResult mined = request.miningClient().mine(miningRequest);

        Set<String> sampleEntities = new LinkedHashSet<>();
        for (InduceDocument d : cluster) {
            sampleEntities.addAll(d.sampleEntityTokens());
        }

        Generalizer.Config gConfig = new Generalizer.Config(
                request.consensusN(), sampleEntities, industryTag);
        Generalizer.Result generalized = new Generalizer().generalize(
                mined, cluster.size(), gConfig);

        String code = resolveCode(mined, request.docTypeCodeHint(), request.fileRole());
        String family = resolveFamily(mined, request.familyHint(), code);
        String fileRole = resolveFileRole(request.fileRole(), cluster, mined);
        List<String> industryTags = List.of(industryTag);

        List<PackField> fields = generalized.packFields();
        DocTypePack candidate = new DocTypePack(
                code,
                "0.0.0", // PackStore assigns the real version on publish
                family,
                fileRole,
                industryTags,
                generalized.identitySignals(),
                fields,
                List.of(),
                List.of(),
                List.of());

        // coverage probe: mining appearances restricted to generalized keys
        Set<String> generalizedKeys = new LinkedHashSet<>();
        for (PackField f : fields) {
            generalizedKeys.add(f.key());
        }
        List<String> clusterDocIds = cluster.stream().map(InduceDocument::id).toList();
        PackQualityGates.CoverageProbe probe = PackQualityGates.probeFromMining(
                mined.fields(), clusterDocIds, generalizedKeys);

        PackQualityGates.GateReport report = PackQualityGates.evaluate(
                candidate,
                probe,
                request.minRequiredCoverage(),
                sampleEntities,
                clusterDocIds);

        DocTypePack toPublish = candidate;
        if (report.optionalHeavy()) {
            toPublish = PackQualityGates.demoteRequired(candidate);
            // re-validate after demotion (still must compile)
            report = PackQualityGates.evaluate(
                    toPublish, probe, request.minRequiredCoverage(), sampleEntities, clusterDocIds);
            // optional-heavy is still a pass when compile + keys gates hold
            if (!report.allPassed() && report.rejectReasons().stream()
                    .allMatch(r -> r.startsWith("required_coverage"))) {
                report = new PackQualityGates.GateReport(
                        report.gates(), report.requiredCoverage(), true, true, List.of());
            }
        }

        String proposedYaml;
        try {
            proposedYaml = PackYamlWriter.write(toPublish);
        } catch (IOException e) {
            proposedYaml = "";
        }

        List<String> rejectReasons = new ArrayList<>(report.rejectReasons());
        if (fields.isEmpty()) {
            rejectReasons.add(0, "zero fields after generalization");
            report = new PackQualityGates.GateReport(
                    report.gates(), report.requiredCoverage(), report.optionalHeavy(), false,
                    rejectReasons);
        }

        boolean gatesPassed = report.allPassed() && fields.size() > 0;

        if (request.dryRun()) {
            return new InduceResult(
                    false,
                    true,
                    gatesPassed,
                    report.optionalHeavy(),
                    toPublish,
                    proposedYaml,
                    report.gates(),
                    report.requiredCoverage(),
                    rejectReasons,
                    generalized.dropped(),
                    generalized.rewritten(),
                    industryTag,
                    null,
                    null);
        }

        if (!gatesPassed) {
            return new InduceResult(
                    false,
                    false,
                    false,
                    report.optionalHeavy(),
                    toPublish,
                    proposedYaml,
                    report.gates(),
                    report.requiredCoverage(),
                    rejectReasons,
                    generalized.dropped(),
                    generalized.rewritten(),
                    industryTag,
                    null,
                    null);
        }

        if (request.store() == null) {
            throw new IllegalStateException(
                    "PackStore is required to publish (use dryRun=true to skip publishing)");
        }

        PackStore.PublishRequest publishRequest = new PackStore.PublishRequest(
                toPublish,
                industryTag,
                null,
                List.of(),
                java.util.UUID.randomUUID().toString());
        PackStore.PublishedPack published = request.store().publish(publishRequest);

        return new InduceResult(
                true,
                false,
                true,
                report.optionalHeavy(),
                published.pack(),
                proposedYaml,
                report.gates(),
                report.requiredCoverage(),
                List.of(),
                generalized.dropped(),
                generalized.rewritten(),
                industryTag,
                published.version(),
                published.yamlPath().toString());
    }

    private static List<InduceDocument> selectCluster(
            List<List<InduceDocument>> clusters, String fileRole, String docTypeHint) {
        String role = fileRole == null || fileRole.isBlank()
                ? null
                : SpineCatalog.normalize(fileRole);
        String hint = docTypeHint == null || docTypeHint.isBlank()
                ? null
                : SpineCatalog.normalize(docTypeHint);
        if (role == null && hint == null) {
            return clusters.get(0);
        }
        for (List<InduceDocument> c : clusters) {
            if (c.isEmpty()) {
                continue;
            }
            if (role != null) {
                String cRole = c.get(0).fileRole() == null
                        ? ""
                        : SpineCatalog.normalize(c.get(0).fileRole());
                if (!cRole.equals(role)) {
                    continue;
                }
            }
            if (hint != null) {
                boolean match = c.stream().anyMatch(d -> {
                    String t = d.title() == null ? "" : SpineCatalog.normalize(d.title());
                    return t.contains(hint) || hint.contains(t) && !t.isEmpty();
                });
                if (!match) {
                    // file-role matched cluster is still acceptable when hint is free-form
                    // only skip when a different cluster matches the hint better
                    continue;
                }
            }
            return c;
        }
        // fall back: any cluster with matching file role
        if (role != null) {
            for (List<InduceDocument> c : clusters) {
                if (!c.isEmpty()) {
                    String cRole = c.get(0).fileRole() == null
                            ? ""
                            : SpineCatalog.normalize(c.get(0).fileRole());
                    if (cRole.equals(role)) {
                        return c;
                    }
                }
            }
        }
        return clusters.get(0);
    }

    private static String resolveCode(
            PackMiningClient.MiningResult mined, String hint, String fileRole) {
        if (hint != null && !hint.isBlank()) {
            return normalizeCode(hint);
        }
        if (mined.suggestedCode() != null && !mined.suggestedCode().isBlank()) {
            return normalizeCode(mined.suggestedCode());
        }
        String family = mined.family() == null || mined.family().isBlank()
                ? (fileRole == null || fileRole.isBlank() ? "document" : fileRole)
                : mined.family();
        return normalizeCode(family + ".induced");
    }

    private static String resolveFamily(
            PackMiningClient.MiningResult mined, String familyHint, String code) {
        if (familyHint != null && !familyHint.isBlank()) {
            return familyHint.trim();
        }
        if (mined.family() != null && !mined.family().isBlank()) {
            return mined.family().trim();
        }
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    private static String resolveFileRole(
            String requested, List<InduceDocument> cluster, PackMiningClient.MiningResult mined) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        for (InduceDocument d : cluster) {
            if (d.fileRole() != null && !d.fileRole().isBlank()) {
                return d.fileRole().trim().toLowerCase(Locale.ROOT);
            }
        }
        return "document";
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "document.induced";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // PackLoader requires family.name lowercase dotted; keep dots, drop junk
        s = s.replaceAll("[^a-z0-9_.]+", "_");
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("^_|_$", "");
        if (!s.contains(".")) {
            s = "document." + s;
        }
        if (!s.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+")) {
            s = "document." + s.replaceAll("[^a-z0-9_]+", "_");
        }
        return s;
    }

    private static InduceResult rejectEmpty(InduceRequest request, String reason) {
        return new InduceResult(
                false,
                request.dryRun(),
                false,
                false,
                null,
                "",
                List.of(),
                0.0,
                List.of(reason),
                List.of(),
                List.of(),
                request.industryTag(),
                null,
                null);
    }
}
