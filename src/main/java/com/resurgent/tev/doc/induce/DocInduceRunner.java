package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.CostUnit;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI facade for pack induction. Loads the mandate corpus and delegates to
 * {@link PackInducer}.
 *
 * <p>Default target is the <em>unknown</em> documents: the ones classification could not
 * place. Every such cluster is induced separately, so one command can mint a pack per
 * newly discovered document type. A live LLM miner is used when a model is configured;
 * otherwise the deterministic offline miner still produces real packs from printed
 * labels. Quality gates run in {@link PackInducer}; only all-gates-pass packs publish to
 * {@code {db-parent}/packs}, and each outcome is recorded in {@code llm_usage},
 * {@code run_stat}, {@code doc_type_pack}, and {@code audit_log}.
 */
public final class DocInduceRunner {

    /** Parser version stamped on the induction {@code parse_run}s. */
    public static final String PARSER_VERSION = "0.1.0-doc-induce";

    private DocInduceRunner() {}

    /**
     * Runs pack induction over a mandate corpus.
     *
     * @param mandateId mandate scope
     * @param db workspace database
     * @param industry auto|tag
     * @param fileRole optional file_role filter (switches off the unknown-only default)
     * @param docType optional pack code filter (single targeted induction)
     * @param versions optional document_version_id allow-list (scopes a live run to samples)
     * @param dryRun when true, do not publish
     * @return one summary per induced cluster
     * @throws Exception on runtime failure
     */
    public static List<Result> run(long mandateId, Path db, String industry, String fileRole,
            String docType, List<Long> versions, boolean dryRun) throws Exception {
        return run(mandateId, db, industry, fileRole, docType, versions, dryRun, null);
    }

    /**
     * Same as {@link #run(long, Path, String, String, String, List, boolean)}.
     * When {@code platform} is set, the mandate is recorded even if nothing is
     * published, and an unset mandate ceiling inherits the platform default.
     */
    public static List<Result> run(long mandateId, Path db, String industry, String fileRole,
            String docType, List<Long> versions, boolean dryRun, Path platform) throws Exception {
        com.resurgent.tev.doc.platform.PlatformRegistry.recordIfNamed(platform, db, mandateId);
        String industryTag = industry == null || "auto".equalsIgnoreCase(industry)
                ? "general"
                : industry;
        Path packsRoot = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        PackStore store = new PackStore(packsRoot);

        List<PackInducer.InduceDocument> documents;
        String mandateResidency = null;
        double estimate = 0;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            documents = loadCorpus(workspace.connection(), mandateId, fileRole, docType, versions);
            estimate = pageEstimate(documents);
            mandateResidency = new com.resurgent.tev.doc.db.MandatePolicyRepository(
                    workspace.connection()).dataResidency(mandateId);
            if (com.resurgent.tev.doc.platform.SpendCeiling.blocks(
                    workspace.connection(), mandateId, platform, estimate)) {
                mandateResidency = com.resurgent.tev.doc.residency.DataResidency.LOCAL_ONLY;
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "no matching documents found for mandate " + mandateId
                            + (fileRole == null ? " (unknown/unclassified only)" : ""));
        }

        DocLlmClient live = DocLlmEnvironment.clientOrNull(db, mandateId, platform, estimate);
        List<List<PackInducer.InduceDocument>> clusters;
        if (docType != null && !docType.isBlank()) {
            clusters = List.of(documents);
        } else {
            clusters = PackInducer.clusterByTypeSignals(documents);
        }

        List<Outcome> outcomes = new ArrayList<>();
        List<Result> failures = new ArrayList<>();
        for (List<PackInducer.InduceDocument> cluster : clusters) {
            if (cluster.isEmpty()) {
                continue;
            }
            List<DocLlmClient.Usage> clusterUsage = new ArrayList<>();
            java.util.Map<String, String> env = System.getenv();
            String effective = com.resurgent.tev.doc.residency.DataResidency.effective(
                    env.get(com.resurgent.tev.doc.residency.DataResidency.ENV), mandateResidency);
            WebSearchClient web = PackMiningClients.webSearchAllowed(env, mandateResidency)
                    ? new HttpWebSearchClient(env.get(PackMiningClients.WEB_SEARCH_URL), effective, null)
                    : null;
            PackMiningClient mining = PackMiningClients.select(
                    env, live, web, clusterUsage::add, mandateResidency);
            String typeHint = docType == null || docType.isBlank()
                    ? clusterCode(cluster.get(0))
                    : docType;
            PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                    cluster, industryTag, fileRole, typeHint, null,
                    mining, store,
                    PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                    Generalizer.DEFAULT_CONSENSUS_N,
                    dryRun);
            System.err.printf("[doc-induce] mining cluster of %d doc(s) starting at %s via %s%n",
                    cluster.size(), cluster.get(0).id(), mining.id());
            try {
                PackInducer.InduceResult result = new PackInducer().induce(request);
                outcomes.add(new Outcome(cluster, result, clusterUsage));
            } catch (Exception e) {
                System.err.printf("[doc-induce] cluster at %s skipped: %s%n",
                        cluster.get(0).id(), e.getMessage());
                failures.add(new Result("(mining failed)", "-", industryTag, null,
                        0, 0, false, List.of(), List.of(),
                        List.of("error: " + e.getMessage()), versionIds(cluster), List.of()));
            }
        }

        persist(db, packsRoot, mandateId, industryTag, outcomes, live != null);
        List<Result> results = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            results.add(toResult(outcome));
        }
        results.addAll(failures);
        return results;
    }

    /** One mandate workspace named on an induction command. */
    public record MandateWorkspace(long mandateId, Path dbPath) {}

    private record Sourced(MandateWorkspace workspace, PackInducer.InduceDocument original,
            String rewrittenId) {}

    /**
     * Judges the named workspaces once, on the combined evidence, and copies one
     * version of each passing pack into every named workspace. A gate failure
     * writes nothing. {@code platform} is accepted so a later registration can
     * be named; this overload does not open a registry when it is null.
     */
    public static List<Result> run(List<MandateWorkspace> workspaces, String industry,
            String fileRole, String docType, List<Long> versions, boolean dryRun, Path platform)
            throws Exception {
        if (workspaces == null || workspaces.isEmpty()) {
            throw new IllegalArgumentException("induction needs at least one workspace");
        }
        for (MandateWorkspace workspace : workspaces) {
            if (workspace.mandateId() <= 0 || !java.nio.file.Files.isRegularFile(workspace.dbPath())) {
                throw new IllegalArgumentException(
                        "expected an existing mandate workspace: " + workspace.dbPath());
            }
            com.resurgent.tev.doc.platform.PlatformRegistry.recordIfNamed(
                    platform, workspace.dbPath(), workspace.mandateId());
        }
        String industryTag = industry == null || "auto".equalsIgnoreCase(industry)
                ? "general"
                : industry;
        List<Sourced> sourced = new ArrayList<>();
        boolean offline = false;
        int pages = 0;
        boolean pagesKnown = false;
        int index = 0;
        for (MandateWorkspace workspace : workspaces) {
            try (WorkspaceDatabase opened = WorkspaceDatabase.open(workspace.dbPath())) {
                List<PackInducer.InduceDocument> documents = loadCorpus(
                        opened.connection(), workspace.mandateId(), fileRole, docType, versions);
                int counted = countedPages(documents);
                if (counted > 0) {
                    pagesKnown = true;
                    pages += counted;
                }
                String residency = new com.resurgent.tev.doc.db.MandatePolicyRepository(
                        opened.connection()).dataResidency(workspace.mandateId());
                double estimate = counted > 0
                        ? com.resurgent.tev.doc.platform.SpendCeiling.estimateUsd(counted, null)
                        : 0;
                if (com.resurgent.tev.doc.platform.SpendCeiling.blocks(
                        opened.connection(), workspace.mandateId(), platform, estimate)
                        || com.resurgent.tev.doc.residency.DataResidency.isLocalOnly(
                                com.resurgent.tev.doc.residency.DataResidency.effective(
                                        System.getenv(com.resurgent.tev.doc.residency.DataResidency.ENV),
                                        residency))) {
                    offline = true;
                }
                for (PackInducer.InduceDocument document : documents) {
                    String rewritten = "w" + index + ":" + document.id();
                    sourced.add(new Sourced(workspace, document, rewritten));
                }
            }
            index++;
        }
        if (sourced.isEmpty()) {
            throw new IllegalArgumentException(
                    "no matching documents found for the named mandates"
                            + (fileRole == null ? " (unknown/unclassified only)" : ""));
        }
        Map<String, Sourced> byId = new LinkedHashMap<>();
        List<PackInducer.InduceDocument> combined = new ArrayList<>();
        for (Sourced item : sourced) {
            byId.put(item.rewrittenId(), item);
            combined.add(new PackInducer.InduceDocument(
                    item.rewrittenId(),
                    item.original().fileRole(),
                    item.original().title(),
                    item.original().layoutText(),
                    item.original().sampleEntityTokens()));
        }
        List<List<PackInducer.InduceDocument>> clusters = docType != null && !docType.isBlank()
                ? List.of(combined)
                : PackInducer.clusterByTypeSignals(combined);
        List<PackStore> stores = new ArrayList<>();
        for (MandateWorkspace workspace : workspaces) {
            stores.add(new PackStore(packsRoot(workspace.dbPath())));
        }
        double estimate = pagesKnown
                ? com.resurgent.tev.doc.platform.SpendCeiling.estimateUsd(pages, null)
                : 0;
        DocLlmClient live = offline
                ? null
                : DocLlmEnvironment.clientOrNull(workspaces.get(0).dbPath(),
                        workspaces.get(0).mandateId(), platform, estimate);
        List<Judged> judged = new ArrayList<>();
        boolean blocked = false;
        for (List<PackInducer.InduceDocument> cluster : clusters) {
            if (cluster.isEmpty()) {
                continue;
            }
            String typeHint = docType == null || docType.isBlank()
                    ? clusterCode(byId.get(cluster.get(0).id()).original())
                    : docType;
            try {
                PackInducer.InduceResult result = new PackInducer().induce(new PackInducer.InduceRequest(
                        cluster, industryTag, fileRole, typeHint, null,
                        miner(offline, live), null,
                        PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                        Generalizer.DEFAULT_CONSENSUS_N,
                        true));
                boolean passed = result.allGatesPassed() && result.pack() != null
                        && !result.pack().fields().isEmpty();
                if (!passed) {
                    blocked = true;
                }
                judged.add(new Judged(cluster, result, passed));
            } catch (Exception e) {
                blocked = true;
                judged.add(new Judged(cluster, null, false));
                System.err.printf("[doc-induce] cluster at %s skipped: %s%n",
                        cluster.get(0).id(), e.getMessage());
            }
        }
        List<Result> results = new ArrayList<>();
        boolean publish = !dryRun && !blocked;
        for (Judged item : judged) {
            if (item.result() == null) {
                results.add(new Result("(mining failed)", "-", industryTag, null,
                        0, 0, false, List.of(), List.of(),
                        List.of("error: mining failed"), List.of(), List.of()));
                continue;
            }
            if (!publish) {
                results.add(toResult(new Outcome(originals(item.cluster(), byId), item.result(), List.of())));
                continue;
            }
            String version = sharedVersion(stores, industryTag, item.result().pack());
            List<String> contributors = contributors(item.cluster(), byId);
            PackStore.PublishedPack installed = null;
            for (int i = 0; i < workspaces.size(); i++) {
                installed = stores.get(i).installCopy(new PackStore.PublishRequest(
                        item.result().pack(), industryTag, null, contributors,
                        "induce-combined"), version);
                List<PackInducer.InduceDocument> mine = originalsFor(
                        workspaces.get(i), item.cluster(), byId);
                if (mine.isEmpty()) {
                    continue;
                }
                PackInducer.InduceResult recorded = new PackInducer.InduceResult(
                        true, false, true, item.result().optionalHeavy(), installed.pack(),
                        item.result().proposedYaml(), item.result().gates(),
                        item.result().requiredCoverage(), List.of(),
                        item.result().droppedFields(), item.result().rewrittenFields(),
                        industryTag, installed.version(), installed.yamlPath().toString(),
                        item.result().decisions());
                persist(workspaces.get(i).dbPath(), stores.get(i).packsRoot(),
                        workspaces.get(i).mandateId(), industryTag,
                        List.of(new Outcome(mine, recorded, List.of())), live != null);
            }
            PackInducer.InduceResult summary = new PackInducer.InduceResult(
                    true, false, true, item.result().optionalHeavy(),
                    installed == null ? item.result().pack() : installed.pack(),
                    item.result().proposedYaml(), item.result().gates(),
                    item.result().requiredCoverage(), List.of(),
                    item.result().droppedFields(), item.result().rewrittenFields(),
                    industryTag, version, installed == null ? null : installed.yamlPath().toString(),
                    item.result().decisions());
            Result summaryResult = toResult(
                    new Outcome(originals(item.cluster(), byId), summary, List.of()));
            if (platform != null && installed != null) {
                String registration = registerPack(platform, workspaces, industryTag, contributors,
                        installed);
                summaryResult = summaryResult.withNotices(List.of(registration));
            }
            results.add(summaryResult);
        }
        return results;
    }

    private static String registerPack(Path platform, List<MandateWorkspace> workspaces,
            String industryTag, List<String> contributors, PackStore.PublishedPack installed)
            throws Exception {
        byte[] yaml = java.nio.file.Files.readAllBytes(installed.yamlPath());
        for (MandateWorkspace workspace : workspaces) {
            com.resurgent.tev.doc.platform.PlatformRegistry.recordIfNamed(
                    platform, workspace.dbPath(), workspace.mandateId());
        }
        try (com.resurgent.tev.doc.platform.PlatformRegistry registry =
                com.resurgent.tev.doc.platform.PlatformRegistry.open(platform)) {
            registry.publishPack(installed.pack().code(), installed.version(), installed.fileHash(),
                    industryTag, contributors, yaml);
        }
        return "registered pack " + installed.pack().code() + "@" + installed.version()
                + " industry " + industryTag
                + " hash " + installed.fileHash()
                + " mandates " + String.join(", ", contributors);
    }

    private record Judged(List<PackInducer.InduceDocument> cluster, PackInducer.InduceResult result,
            boolean passed) {}

    private static PackMiningClient miner(boolean offline, DocLlmClient live) {
        if (offline || live == null) {
            return new OfflinePackMiningClient();
        }
        java.util.Map<String, String> env = System.getenv();
        WebSearchClient web = PackMiningClients.webSearchAllowed(env, null)
                ? new HttpWebSearchClient(env.get(PackMiningClients.WEB_SEARCH_URL), null, null)
                : null;
        return PackMiningClients.select(env, live, web, null, null);
    }

    /** Page price when layout stored pages; otherwise no estimate. */
    private static double pageEstimate(List<PackInducer.InduceDocument> documents) {
        int pages = countedPages(documents);
        return pages > 0
                ? com.resurgent.tev.doc.platform.SpendCeiling.estimateUsd(pages, null)
                : 0;
    }

    private static int countedPages(List<PackInducer.InduceDocument> documents) {
        int pages = 0;
        if (documents == null) {
            return 0;
        }
        for (PackInducer.InduceDocument document : documents) {
            pages += document.layoutText().size();
        }
        return pages;
    }

    private static Path packsRoot(Path db) {
        Path absolute = db.toAbsolutePath();
        return absolute.getParent() == null
                ? Path.of("packs")
                : absolute.getParent().resolve("packs");
    }

    private static String sharedVersion(List<PackStore> stores, String industry, DocTypePack pack) {
        String highest = null;
        PackStore owner = null;
        for (PackStore store : stores) {
            var latest = store.latestEntry(industry, pack.code());
            if (latest.isEmpty()) {
                continue;
            }
            if (highest == null || PackStore.compareVersions(latest.get().version(), highest) > 0) {
                highest = latest.get().version();
                owner = store;
            }
        }
        boolean requiredChanged = owner != null && owner.requiredFieldsChanged(industry, pack);
        return PackStore.bumpVersion(highest, requiredChanged);
    }

    private static List<PackInducer.InduceDocument> originals(
            List<PackInducer.InduceDocument> cluster, Map<String, Sourced> byId) {
        List<PackInducer.InduceDocument> originals = new ArrayList<>();
        for (PackInducer.InduceDocument document : cluster) {
            Sourced sourced = byId.get(document.id());
            if (sourced != null) {
                originals.add(sourced.original());
            }
        }
        return originals;
    }

    private static List<PackInducer.InduceDocument> originalsFor(MandateWorkspace workspace,
            List<PackInducer.InduceDocument> cluster, Map<String, Sourced> byId) {
        List<PackInducer.InduceDocument> originals = new ArrayList<>();
        for (PackInducer.InduceDocument document : cluster) {
            Sourced sourced = byId.get(document.id());
            if (sourced != null && sourced.workspace().equals(workspace)) {
                originals.add(sourced.original());
            }
        }
        return originals;
    }

    private static List<String> contributors(
            List<PackInducer.InduceDocument> cluster, Map<String, Sourced> byId) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (PackInducer.InduceDocument document : cluster) {
            Sourced sourced = byId.get(document.id());
            if (sourced == null) {
                continue;
            }
            names.add(sourced.workspace().dbPath().toAbsolutePath().normalize()
                    + "#" + sourced.workspace().mandateId());
        }
        return List.copyOf(names);
    }

    /**
     * A stable pack code for one cluster when the caller did not name a type.
     * Distinct titles must not all publish as {@code document.induced}.
     */
    private static String clusterCode(PackInducer.InduceDocument document) {
        String title = document.title() == null || document.title().isBlank()
                ? document.id()
                : document.title();
        String slug = SpineCatalog.normalize(title).replace(' ', '_').replaceAll("[^a-z0-9_]", "");
        if (slug.isBlank()) {
            slug = "cluster";
        }
        return "induced." + slug;
    }

    private record Outcome(
            List<PackInducer.InduceDocument> cluster,
            PackInducer.InduceResult result,
            List<DocLlmClient.Usage> usage) {}

    private static Result toResult(Outcome outcome) {
        PackInducer.InduceResult result = outcome.result();
        List<String> gateMessages = new ArrayList<>();
        int passed = 0;
        for (PackQualityGates.GateResult gate : result.gates()) {
            gateMessages.add(gate.name() + ": " + gate.detail());
            if (gate.passed()) {
                passed++;
            }
        }
        gateMessages.addAll(result.rejectReasons());
        String packCode = result.pack() != null ? result.pack().code() : "(none)";
        String packVersion = result.pack() != null ? result.pack().version() : result.version();
        return new Result(
                packCode,
                packVersion,
                result.industryTag(),
                result.proposedYaml(),
                passed,
                result.gates().size(),
                result.published(),
                result.droppedFields(),
                result.rewrittenFields(),
                gateMessages,
                versionIds(outcome.cluster()),
                List.of());
    }

    /** Version ids encoded as {@code dv:<id>} on the cluster's documents. */
    private static List<Long> versionIds(List<PackInducer.InduceDocument> cluster) {
        List<Long> ids = new ArrayList<>();
        if (cluster == null) {
            return ids;
        }
        for (PackInducer.InduceDocument document : cluster) {
            Long id = versionIdOf(document.id());
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Records each induction outcome: a {@code parse_run} (anchored to the cluster's first
     * document), {@code llm_usage}, {@code run_stat}, a {@code doc_type_pack} row for a
     * published pack, and an {@code audit_log} entry. Re-induction replaces the stage's
     * usage/stat rows so totals never accumulate.
     */
    private static void persist(Path db, Path packsRoot, long mandateId, String industryTag,
            List<Outcome> outcomes, boolean liveModel) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            Connection connection = workspace.connection();
            WorkspaceRepository repo = new WorkspaceRepository(connection);
            DocumentRepository docs = new DocumentRepository(connection);
            connection.setAutoCommit(false);
            try {
                for (Outcome outcome : outcomes) {
                    long sourceFileId = anchorSourceFileId(docs, outcome.cluster());
                    if (sourceFileId <= 0) {
                        continue;
                    }
                    PackInducer.InduceResult result = outcome.result();
                    String code = result.pack() != null ? result.pack().code() : "unknown";
                    String configHash = "induce:" + industryTag.toLowerCase() + ":" + code;
                    Long existing = repo.findParseRunId(sourceFileId, PARSER_VERSION, configHash);
                    long parseRunId = existing != null ? existing
                            : repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                                    configHash, Timestamps.now(), Timestamps.now(), "running", null);
                    repo.deleteStageStats(parseRunId, "doc-induce");

                    Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
                    for (DocLlmClient.Usage usage : outcome.usage()) {
                        totals.merge(usage.modelId(), usage, DocLlmClient.Usage::plus);
                    }
                    for (DocLlmClient.Usage total : totals.values()) {
                        repo.recordLlmUsage(parseRunId, "doc-induce", total.modelId(),
                                total.calls(), total.failedCalls(), 0,
                                total.promptTokens(), total.completionTokens(),
                                total.costUsd(), CostUnit.USD, total.costUsd() == null ? 1 : 0,
                                total.latencyMsTotal());
                    }
                    repo.recordRunStat(parseRunId, "doc-induce", "documents_in_cluster",
                            (double) outcome.cluster().size(), null);
                    repo.recordRunStat(parseRunId, "doc-induce", "gates_passed",
                            (double) result.gates().stream().filter(PackQualityGates.GateResult::passed)
                                    .count(), null);
                    repo.recordRunStat(parseRunId, "doc-induce", "fields_generalized",
                            (double) (result.pack() == null ? 0 : result.pack().fields().size()), null);
                    if (result.published()) {
                        repo.recordRunStat(parseRunId, "doc-induce", "published_version",
                                null, result.version());
                        if (result.pack() != null && result.publishedPath() != null) {
                            Path yamlPath = Path.of(result.publishedPath()).toAbsolutePath().normalize();
                            String yamlRel;
                            try {
                                yamlRel = packsRoot.relativize(yamlPath).toString().replace('\\', '/');
                            } catch (IllegalArgumentException e) {
                                yamlRel = result.pack().code() + "@" + result.version() + ".yaml";
                            }
                            String fileHash = null;
                            try {
                                fileHash = PackStore.sha256Hex(java.nio.file.Files.readAllBytes(yamlPath));
                            } catch (Exception e) {
                                fileHash = result.pack().identity();
                            }
                            docs.upsertDocTypePack(
                                    result.pack().code(), result.version(),
                                    result.pack().family(), result.pack().fileRole(),
                                    industryTag, fileHash, yamlRel, "[" + mandateId + "]",
                                    "induce:" + parseRunId, "published");
                        }
                    }
                    repo.updateParseRunResult(parseRunId, Timestamps.now(),
                            result.published() ? "success" : "partial",
                            "{\"pack\":\"" + code + "\",\"live_model\":" + liveModel
                                    + ",\"published\":" + result.published() + "}");
                    InductionDecisionLog.recordPack(
                            connection, mandateId, parseRunId, code, result.decisions());
                    repo.insertAuditLog(parseRunId, "induce_completed", Timestamps.now(),
                            "{\"pack\":\"" + code + "\",\"published\":" + result.published()
                                    + ",\"gates_passed\":" + result.allGatesPassed()
                                    + ",\"rejects\":" + result.rejectReasons().size() + "}",
                            result.published() ? "info" : "warn");
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception ignored) {
                    // keep the original failure
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                    // connection may already be released
                }
            }
        }
    }

    private static long anchorSourceFileId(DocumentRepository docs,
            List<PackInducer.InduceDocument> cluster) throws Exception {
        for (PackInducer.InduceDocument document : cluster) {
            Long versionId = versionIdOf(document.id());
            if (versionId == null) {
                continue;
            }
            var row = docs.findVersion(versionId);
            if (row.isPresent() && row.get().sourceFileId() != null) {
                return row.get().sourceFileId();
            }
        }
        return -1;
    }

    private static Long versionIdOf(String documentId) {
        if (documentId == null || !documentId.startsWith("dv:")) {
            return null;
        }
        try {
            return Long.parseLong(documentId.substring(3).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Loads the induction corpus. With no filters this is the <em>unknown</em> or
     * unclassified documents only, so {@code induce} grows packs for the types classify
     * could not place. A {@code --file-role} or {@code --doc-type} filter widens it.
     */
    private static List<PackInducer.InduceDocument> loadCorpus(Connection connection,
            long mandateId, String fileRole, String docType, List<Long> versions) throws Exception {
        boolean roleFilter = fileRole != null && !fileRole.isBlank();
        boolean typeFilter = docType != null && !docType.isBlank();
        boolean versionFilter = versions != null && !versions.isEmpty();
        StringBuilder sql = new StringBuilder("""
                SELECT dv.document_version_id, dv.file_role, dv.file_name,
                       COALESCE(d.logical_name, dv.file_name) AS title
                FROM document_version dv
                JOIN document d ON d.document_id = dv.document_id
                LEFT JOIN doc_classification c ON c.document_version_id = dv.document_version_id
                WHERE d.mandate_id = ?
                """);
        if (roleFilter) {
            sql.append(" AND dv.file_role = ? ");
        }
        if (typeFilter) {
            sql.append(" AND (c.doc_type_code = ? OR c.doc_type_code IS NULL) ");
        }
        if (!roleFilter && !typeFilter && !versionFilter) {
            sql.append(" AND (c.doc_type_code IS NULL OR c.doc_type_code = 'unknown') ");
        }
        if (versionFilter) {
            sql.append(" AND dv.document_version_id IN (")
                    .append("?,".repeat(versions.size() - 1))
                    .append("?) ");
        }
        sql.append(" ORDER BY dv.document_version_id ");

        List<PackInducer.InduceDocument> docs = new ArrayList<>();
        try (var ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setLong(index++, mandateId);
            if (roleFilter) {
                ps.setString(index++, fileRole);
            }
            if (typeFilter) {
                ps.setString(index++, docType);
            }
            if (versionFilter) {
                for (Long version : versions) {
                    ps.setLong(index++, version);
                }
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long versionId = rs.getLong(1);
                    String role = rs.getString(2);
                    String fileName = rs.getString(3);
                    String title = rs.getString(4);
                    List<String> layout = loadLayoutText(connection, versionId);
                    docs.add(new PackInducer.InduceDocument(
                            "dv:" + versionId,
                            role,
                            title,
                            layout,
                            entityTokens(title, fileName, layout)));
                }
            }
        }
        return docs;
    }

    /**
     * Entity tokens for label-not-value stripping: the logical title, the file name, and
     * every PAN/CIN/GSTIN/amount token seen in the layout.
     */
    private static Set<String> entityTokens(String title, String fileName, List<String> layout) {
        return EntityTokenFilter.sampleTokens(title, fileName, layout);
    }

    private static List<String> loadLayoutText(Connection connection, long documentVersionId)
            throws Exception {
        Long documentId = documentIdOf(connection, documentVersionId);
        boolean members = documentId != null && hasMemberPages(connection, documentId);
        long key = members ? documentId : documentVersionId;
        String pageJoin = members
                ? " JOIN document_page dp ON dp.page_id = p.page_id WHERE dp.document_id = ?"
                : " WHERE p.document_version_id = ?";
        List<String> lines = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT COALESCE(NULLIF(b.text_norm, ''), b.text_raw) FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + pageJoin
                        + " ORDER BY p.page_index, b.block_index LIMIT 120")) {
            ps.setLong(1, key);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString(1);
                    if (text != null && !text.isBlank()) {
                        lines.add(text.trim());
                    }
                }
            }
        }
        try (var ps = connection.prepareStatement(
                "SELECT tc.text_raw FROM table_cell tc"
                        + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + pageJoin
                        + " ORDER BY p.page_index, tc.row_index, tc.col_index")) {
            ps.setLong(1, key);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString(1);
                    if (text != null && !text.isBlank() && !lines.contains(text.trim())) {
                        lines.add(text.trim());
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            long holder = members
                    ? pageHolder(connection, documentId, documentVersionId)
                    : documentVersionId;
            List<Integer> memberPages = members ? memberIndexes(connection, documentId) : List.of();
            List<String> cloud = new CloudParsePageRepository(connection).loadTextPages(holder);
            for (int i = 0; i < cloud.size(); i++) {
                if (!memberPages.isEmpty() && !memberPages.contains(i + 1)) {
                    continue;
                }
                String text = cloud.get(i);
                if (text != null && !text.isBlank()) {
                    lines.add(text.trim());
                }
            }
        }
        return lines.stream().limit(120).toList();
    }

    /** The version that owns the physical pages a document's membership points at. */
    private static long pageHolder(Connection connection, long documentId, long fallback)
            throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT p.document_version_id FROM page p"
                        + " JOIN document_page dp ON dp.page_id = p.page_id"
                        + " WHERE dp.document_id = ? LIMIT 1")) {
            ps.setLong(1, documentId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : fallback;
            }
        }
    }

    private static List<Integer> memberIndexes(Connection connection, long documentId)
            throws Exception {
        List<Integer> indexes = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT p.page_index FROM page p"
                        + " JOIN document_page dp ON dp.page_id = p.page_id"
                        + " WHERE dp.document_id = ?")) {
            ps.setLong(1, documentId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getInt(1));
                }
            }
        }
        return indexes;
    }

    private static Long documentIdOf(Connection connection, long documentVersionId) throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT document_id FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static boolean hasMemberPages(Connection connection, long documentId) throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM document_page WHERE document_id = ? LIMIT 1")) {
            ps.setLong(1, documentId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Induction summary for one cluster.
     *
     * @param packCode induced or proposed pack code
     * @param packVersion version (bumped on re-induct)
     * @param industryTag industry used
     * @param packYaml proposed YAML (dry-run)
     * @param gatesPassed gates that passed
     * @param gatesTotal gates evaluated
     * @param published true when a pack was written to the store
     * @param droppedFields fields dropped by generalization (one-off/entity)
     * @param rewrittenFields keys/paths rewritten to labels
     * @param gateMessages per-gate reasons
     * @param versionIds document versions in the cluster this pack was induced from
     * @param notices operator lines, including a platform registration when one was written
     */
    public record Result(
            String packCode,
            String packVersion,
            String industryTag,
            String packYaml,
            int gatesPassed,
            int gatesTotal,
            boolean published,
            List<String> droppedFields,
            List<String> rewrittenFields,
            List<String> gateMessages,
            List<Long> versionIds,
            List<String> notices) {

        public Result {
            notices = notices == null ? List.of() : List.copyOf(notices);
        }

        Result withNotices(List<String> extra) {
            return new Result(packCode, packVersion, industryTag, packYaml, gatesPassed, gatesTotal,
                    published, droppedFields, rewrittenFields, gateMessages, versionIds, extra);
        }
    }
}
