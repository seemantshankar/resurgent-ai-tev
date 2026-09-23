package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.Progress;
import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.NomenclatureException;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Packet classification application service: Layer A disposition and Layer B
 * nomenclature bindings for one parse run. Consumes derived Packets; does not
 * rewrite Candidate geometry. Peers, ProjectFacts, and Cell interpretations
 * persist in separate tables.
 */
public final class ClassifyService {

    private final ClassifierLlm llm;
    private final DiscoverService discover;
    private final InterpretationWriter interpretationWriter;
    private final FormulaGlossLlm formulaGlossLlm;
    private final ClassifyLimits limits;

    public ClassifyService(ClassifierLlm llm) {
        this(llm, new DiscoverService(), new InterpretationWriter(),
                glossPortFor(llm), ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover) {
        this(llm, discover, new InterpretationWriter(),
                glossPortFor(llm), ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover, ClassifyLimits limits) {
        this(llm, discover, new InterpretationWriter(), glossPortFor(llm), limits);
    }

    public ClassifyService(
            ClassifierLlm llm, DiscoverService discover, FormulaGlossLlm formulaGlossLlm) {
        this(llm, discover, new InterpretationWriter(), formulaGlossLlm, ClassifyLimits.defaults());
    }

    public ClassifyService(
            ClassifierLlm llm, DiscoverService discover, InterpretationWriter interpretationWriter) {
        this(llm, discover, interpretationWriter, glossPortFor(llm),
                ClassifyLimits.defaults());
    }

    public ClassifyService(
            ClassifierLlm llm,
            DiscoverService discover,
            InterpretationWriter interpretationWriter,
            ClassifyLimits limits) {
        this(llm, discover, interpretationWriter, glossPortFor(llm), limits);
    }

    public ClassifyService(
            ClassifierLlm llm,
            DiscoverService discover,
            InterpretationWriter interpretationWriter,
            FormulaGlossLlm formulaGlossLlm,
            ClassifyLimits limits) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.discover = Objects.requireNonNull(discover, "discover");
        this.interpretationWriter =
                Objects.requireNonNull(interpretationWriter, "interpretationWriter");
        this.formulaGlossLlm = Objects.requireNonNull(formulaGlossLlm, "formulaGlossLlm");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Live OpenRouter (and other dual-port adapters) carry gloss; fakes stay no-op. */
    private static FormulaGlossLlm glossPortFor(ClassifierLlm llm) {
        if (llm instanceof FormulaGlossLlm gloss) {
            return gloss;
        }
        return new NoOpFormulaGlossLlm();
    }

    public ClassifySummary classify(Path dbPath, long parseRunId) throws ClassifyException {
        Objects.requireNonNull(dbPath, "dbPath");
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new ClassifyException("database not found: " + absolute);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(absolute)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (!repo.parseRunExists(parseRunId)) {
                throw new ClassifyException("parse run not found: " + parseRunId);
            }
            long mandateId = repo.selectParseRunMandateId(parseRunId);
            NomenclatureCatalog catalog = new NomenclatureCatalog(repo);
            // Cut the overlay feedback loop before the slice is read: a soft leaf this
            // run invented last time must not be offered back as a selectable path. The
            // paths are only excluded from the slice here; the delete itself waits for
            // the write transaction below, so a run that never commits cannot orphan the
            // bindings that still justify a leaf.
            Set<String> orphanSoftLeafPaths = catalog.orphanSoftLeafPaths(mandateId, parseRunId);
            OntologySlice slice = catalog.withoutPaths(
                    catalog.sliceForMandate(mandateId), orphanSoftLeafPaths);
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
            ScheduleFamilyCatalog families = ScheduleFamilyCatalog.seeded();
            families.restore(repo.selectScheduleFamilies());
            if (candidates.isEmpty()) {
                throw new ClassifyException("no Candidates for parse run " + parseRunId
                        + "; run discover first");
            }

            // LLM calls stay outside the write transaction so long classify runs do not
            // hold a SQLite write lock. Packets are built serially; Layer A runs
            // concurrently with parent-before-child. Naming questions run after the
            // graph, once per label per worksheet.
            List<PreparedPacket> prepared = new ArrayList<>();
            int coverageParents = 0;
            // One session for the whole loop: Cell views and reference edges are shared
            // across Candidates instead of re-read per Packet.
            DiscoverService.PacketSession packetSession = discover.packetSession(repo);
            Progress.phase(
                    "classify", "building packets for " + candidates.size() + " candidates");
            int packetProgress = 0;
            for (CandidateRow candidate : candidates) {
                Progress.step(
                        "classify", "packets", ++packetProgress, candidates.size(), 25);
                boolean cheapPass = "coverage_parent".equals(candidate.candidateKind());
                if (cheapPass) {
                    coverageParents++;
                }
                Packet packet = packetSession.build(candidate.candidateId());
                Packet redacted = PacketRedactor.redact(packet, cheapPass);
                prepared.add(new PreparedPacket(candidate, packet, redacted, cheapPass));
            }

            long classifyDeadlineNanos = System.nanoTime() + limits.classifyDeadline().toNanos();
            LlmPhaseResult llmPhase = runLlmPhase(
                    slice, parseRunId, prepared, classifyDeadlineNanos, families);
            LayerBBindingStats layerBStats = new LayerBBindingStats();

            List<PacketDisposition> dispositions = llmPhase.dispositions();
            List<ProjectFactBinding> factBindings = llmPhase.facts();

            // The graph is read before the write transaction: cells and reference
            // edges do not change during classify, and holding a write lock over the
            // whole fixpoint would serialise every other writer behind it.
            Progress.phase("classify", "reading cell graph");
            CellGraph graph = new CellGraphBuilder().read(repo, parseRunId);
            Progress.phase("classify", "propagating types");
            CellTypes cellTypes = new TypePropagation().resolve(graph);
            Progress.phase("classify", "types resolved");
            Map<Long, Long> candidateByCell = candidateByCell(repo, parseRunId, candidates);

            // Roles the graph proves bind without asking. Names it cannot supply are
            // asked once per distinct label per worksheet, never once per cell.
            DeterministicBinder.Result deterministic = new DeterministicBinder().bind(
                    parseRunId, graph, cellTypes, slice, candidateByCell, Set.of());
            GapFillResult gapFill = fillLabelGaps(
                    catalog, mandateId, slice, parseRunId, graph, deterministic,
                    llmPhase.layerA(), layerBStats, classifyDeadlineNanos);
            for (String failure : gapFill.failures()) {
                layerBStats.addFailedCall(failure);
            }

            db.connection().setAutoCommit(false);
            int interpretationCount;
            List<NomenclatureBinding> bindings;
            try {
                List<CandidateRow> current = repo.selectCandidatesForParseRun(parseRunId);
                if (!sameCandidateIds(candidates, current)) {
                    throw new ClassifyException(
                            "Candidates changed during classify for parse run " + parseRunId
                                    + "; re-run discover then classify");
                }
                // Soft leaves gap fill staged in memory land now, atomically with the
                // bindings that name them; the earlier orphan purge only excluded paths
                // from the slice, so the delete itself happens here too.
                catalog.persistPendingSoftLeaves(gapFill.pendingSoftLeaves());
                repo.deleteOrphanMandateSoftLeaves(mandateId, parseRunId);
                repo.deleteBindingPeersForParseRun(parseRunId);
                repo.deleteProjectFactBindingsForParseRun(parseRunId);
                repo.deleteNomenclatureBindingsForParseRun(parseRunId);
                repo.deletePacketDispositionsForParseRun(parseRunId);
                for (String family : families.admitted()) {
                    repo.insertScheduleFamily(family);
                }
                for (PacketDisposition disposition : dispositions) {
                    repo.insertPacketDisposition(disposition);
                }
                for (ProjectFactBinding fact : factBindings) {
                    repo.insertProjectFactBinding(fact);
                }
                // The graph and its typing are the evidence behind Layer B: what each
                // formula composes, with what sign, and what unit every numeric cell
                // turned out to be. Persisted first so bindings can point at a group.
                Map<Long, Long> aggregationIds =
                        new CellGraphWriter().write(repo, graph, cellTypes);

                bindings = new ArrayList<>();
                Set<Long> graphBound = new HashSet<>();
                for (NomenclatureBinding binding : deterministic.bindings()) {
                    graphBound.add(binding.cellId());
                    bindings.add(withAggregation(binding, deterministic, aggregationIds));
                }
                for (NomenclatureBinding binding : gapFill.bindings()) {
                    if (!graphBound.add(binding.cellId())) {
                        continue;
                    }
                    bindings.add(withAggregation(binding, deterministic, aggregationIds));
                }
                layerBStats.addDeterministic(deterministic.bindings().size());

                for (NomenclatureBinding binding : bindings) {
                    repo.insertNomenclatureBinding(binding);
                }
                List<BindingPeer> bindingPeers;
                try {
                    bindingPeers = BindingPeerWriter.buildPeers(
                            repo, parseRunId, bindings, gapFill.pendingPeers());
                } catch (PeerCoordResolver.PeerCoordException e) {
                    throw new ClassifyException(e.getMessage(), e);
                }
                for (BindingPeer peer : bindingPeers) {
                    repo.insertBindingPeer(peer);
                }
                Map<Long, UnboundReason> unboundReasons =
                        new HashMap<>(deterministic.unboundReasons());
                unboundReasons.putAll(gapFill.reasons());
                Progress.phase("classify", "writing interpretations");
                interpretationCount = interpretationWriter.write(
                        repo, parseRunId, bindings, unboundReasons);
                repo.commit();
            } catch (ClassifyException e) {
                repo.rollback();
                throw e;
            } catch (Exception e) {
                repo.rollback();
                throw new ClassifyException("classify failed: " + e.getMessage(), e);
            } finally {
                db.connection().setAutoCommit(true);
            }

            // Gloss LLM stays after the interpretation/annotation commit so a
            // provider failure cannot roll back bindings. Lifecycle still follows
            // annotation presence: only formula cells with annotations are glossed.
            try {
                db.connection().setAutoCommit(false);
                new FormulaGlossWriter(formulaGlossLlm).write(repo, parseRunId);
                repo.commit();
            } catch (Exception e) {
                repo.rollback();
                // Gloss is optional explanation; do not fail classify after A/B succeeded.
            } finally {
                db.connection().setAutoCommit(true);
            }

            return new ClassifySummary(
                    parseRunId,
                    dispositions.size(),
                    coverageParents,
                    bindings.size(),
                    interpretationCount,
                    llmPhase.unclassifiedCandidateIds(),
                    layerBStats);
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("classify failed: " + msg, e);
        }
    }

    private record PreparedPacket(
            CandidateRow candidate, Packet packet, Packet redacted, boolean cheapPass) {}

    private record LayerAWork(
            PreparedPacket prepared,
            LayerAJudgment judgment,
            PacketDisposition disposition,
            List<ProjectFactBinding> facts) {}

    private LayerAWork layerAWork(
            OntologySlice slice,
            long parseRunId,
            PreparedPacket prepared,
            LayerAJudgment parent,
            ScheduledExecutorService watchdog,
            long deadlineNanos,
            ScheduleFamilyCatalog families) throws ClassifyException {
        CandidateRow candidate = prepared.candidate();
        LayerAJudgment raw = callLayerAWithRetry(
                slice, prepared, parent, watchdog, deadlineNanos, families);
        if (raw == null) {
            return null;
        }
        LayerAJudgment judgment = requireJudgment(raw, candidate.candidateId(), families);
        PacketDisposition disposition = new PacketDisposition(
                candidate.candidateId(),
                parseRunId,
                judgment.scheduleFamily(),
                judgment.triage(),
                judgment.relevance(),
                judgment.rowLabels(),
                judgment.columnHeaders(),
                judgment.packetDefaultHead(),
                candidate.parentCandidateId(),
                prepared.cheapPass());
        List<ProjectFactBinding> facts = new ArrayList<>();
        for (ProjectFactJudgment fact : judgment.facts()) {
            materializeFact(slice, candidate, parseRunId, prepared.packet(), fact)
                    .ifPresent(facts::add);
        }
        return new LayerAWork(prepared, judgment, disposition, facts);
    }

    /**
     * One Layer A attempt, then one retry at the shorter retry budget. Returns null when
     * both miss, leaving that Candidate unclassified: a single straggler used to reject
     * the whole run and discard every other Candidate's work, and the tail is per-request
     * provider scheduling (identical prompts run in 2s or 112s), so a fresh request has a
     * real chance of landing fast.
     */
    private LayerAJudgment callLayerAWithRetry(
            OntologySlice slice,
            PreparedPacket prepared,
            LayerAJudgment parent,
            ScheduledExecutorService watchdog,
            long deadlineNanos,
            ScheduleFamilyCatalog families) throws ClassifyException {
        try {
            return callLayerAOnce(
                    slice, prepared, parent, watchdog, deadlineNanos,
                    limits.attemptDeadline(), families);
        } catch (AttemptDeadlineException firstMiss) {
            System.err.println("Layer A candidate " + prepared.candidate().candidateId()
                    + " exceeded the " + limits.attemptDeadline().toMillis()
                    + "ms attempt deadline; retrying once at "
                    + limits.retryAttemptDeadline().toMillis() + "ms");
        }
        try {
            return callLayerAOnce(
                    slice, prepared, parent, watchdog, deadlineNanos,
                    limits.retryAttemptDeadline(), families);
        } catch (AttemptDeadlineException secondMiss) {
            System.err.println("Layer A candidate " + prepared.candidate().candidateId()
                    + " missed the retry deadline too; leaving it unclassified");
            return null;
        }
    }

    private LayerAJudgment callLayerAOnce(
            OntologySlice slice,
            PreparedPacket prepared,
            LayerAJudgment parent,
            ScheduledExecutorService watchdog,
            long deadlineNanos,
            Duration attemptDeadline,
            ScheduleFamilyCatalog families) throws ClassifyException {
        try {
            return callLlm(
                    () -> llm.classifyLayerA(new LayerAPrompt(
                            prepared.redacted(), slice, parent, prepared.cheapPass(),
                            families.names())),
                    "Layer A candidate " + prepared.candidate().candidateId(),
                    watchdog,
                    deadlineNanos,
                    attemptDeadline);
        } catch (OpenRouterClassifierLlm.TruncatedCompletionException truncated) {
            String family = parent != null
                    && ScheduleFamily.isKnown(parent.scheduleFamily())
                    ? parent.scheduleFamily()
                    : ScheduleFamily.ASSUMPTIONS;
            System.err.println("OpenRouter Layer A persistent truncation for candidate "
                    + prepared.candidate().candidateId() + ": " + truncated.getMessage()
                    + "; degrading to orphan/noise");
            return new LayerAJudgment(
                    family, Triage.ORPHAN, Relevance.NOISE,
                    List.of(), List.of(), null);
        } catch (RuntimeException invalid) {
            if (invalid.getMessage() != null
                    && invalid.getMessage().contains("scheduleFamily none without a category")) {
                System.err.println("Layer A candidate " + prepared.candidate().candidateId()
                        + " named no family and no new category; leaving it unclassified");
                return null;
            }
            throw invalid;
        }
    }

    private record LlmPhaseResult(
            List<PacketDisposition> dispositions,
            List<ProjectFactBinding> facts,
            Map<Long, LayerAJudgment> layerA,
            List<Long> unclassifiedCandidateIds) {}

    @FunctionalInterface
    private interface ClassifyTask {
        void run() throws ClassifyException;
    }

    private LlmPhaseResult runLlmPhase(
            OntologySlice slice,
            long parseRunId,
            List<PreparedPacket> prepared,
            long deadlineNanos,
            ScheduleFamilyCatalog families)
            throws ClassifyException {
        Map<Long, LayerAJudgment> judged = new ConcurrentHashMap<>();
        Map<Long, LayerAJudgment> coverageByWorksheet = new ConcurrentHashMap<>();
        Map<Long, PacketDisposition> dispositions = new ConcurrentHashMap<>();
        Map<Long, List<ProjectFactBinding>> facts = new ConcurrentHashMap<>();
        Set<Long> submittedA = ConcurrentHashMap.newKeySet();
        Set<Long> unclassified = ConcurrentHashMap.newKeySet();
        Set<Long> settledWorksheets = ConcurrentHashMap.newKeySet();
        AtomicReference<ClassifyException> failure = new AtomicReference<>();
        Object scheduleLock = new Object();

        ExecutorService pool = Executors.newFixedThreadPool(limits.parallelism());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "classify-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        Phaser phaser = new Phaser(1);
        Runnable[] submitReady = new Runnable[1];
        submitReady[0] = () -> {
            synchronized (scheduleLock) {
                for (PreparedPacket packet : prepared) {
                    long id = packet.candidate().candidateId();
                    if (!submittedA.add(id)) {
                        continue;
                    }
                    if (!packet.cheapPass()
                            && parentContext(packet.candidate(), judged, coverageByWorksheet)
                                    == null
                            && !settledWorksheets.contains(packet.candidate().worksheetId())) {
                        submittedA.remove(id);
                        continue;
                    }
                    submitTask(phaser, pool, failure, deadlineNanos, () -> {
                        LayerAJudgment parent = parentContext(
                                packet.candidate(), judged, coverageByWorksheet);
                        LayerAWork work = layerAWork(
                                slice, parseRunId, packet, parent, watchdog, deadlineNanos,
                                families);
                        if (work == null) {
                            unclassified.add(id);
                        } else {
                            judged.put(id, work.judgment());
                            if (packet.cheapPass()) {
                                coverageByWorksheet.put(
                                        packet.candidate().worksheetId(), work.judgment());
                            }
                            dispositions.put(id, work.disposition());
                            facts.put(id, work.facts());
                        }
                        if (packet.cheapPass()) {
                            // A coverage parent that never produced a judgment must still
                            // settle, or its children wait on a disposition that is not coming.
                            settledWorksheets.add(packet.candidate().worksheetId());
                        }
                        submitReady[0].run();
                    });
                }
            }
        };
        try {
            submitReady[0].run();
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new ClassifyException("incomplete: classify deadline exceeded");
            }
            int phase = phaser.arrive();
            try {
                phaser.awaitAdvanceInterruptibly(phase, remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                failure.compareAndSet(
                        null, new ClassifyException("incomplete: classify deadline exceeded"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClassifyException("classify interrupted", e);
            }
            if (failure.get() != null) {
                throw failure.get();
            }
            return assemblePhase(prepared, dispositions, facts, judged, unclassified);
        } finally {
            pool.shutdownNow();
            watchdog.shutdownNow();
        }
    }

    private static void submitTask(
            Phaser phaser,
            ExecutorService pool,
            AtomicReference<ClassifyException> failure,
            long deadlineNanos,
            ClassifyTask task) {
        if (failure.get() != null) {
            return;
        }
        if (System.nanoTime() >= deadlineNanos) {
            failure.compareAndSet(
                    null, new ClassifyException("incomplete: classify deadline exceeded"));
            return;
        }
        phaser.register();
        pool.submit(() -> {
            try {
                if (failure.get() != null) {
                    return;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    failure.compareAndSet(
                            null,
                            new ClassifyException("incomplete: classify deadline exceeded"));
                    return;
                }
                task.run();
            } catch (ClassifyException e) {
                failure.compareAndSet(null, e);
            } catch (RuntimeException e) {
                failure.compareAndSet(
                        null, new ClassifyException("classify failed: " + e.getMessage(), e));
            } finally {
                phaser.arriveAndDeregister();
            }
        });
    }

    private <T> T callLlm(
            Callable<T> call,
            String label,
            ScheduledExecutorService watchdog,
            long deadlineNanos) throws ClassifyException {
        return callLlm(call, label, watchdog, deadlineNanos, limits.attemptDeadline());
    }

    private <T> T callLlm(
            Callable<T> call,
            String label,
            ScheduledExecutorService watchdog,
            long deadlineNanos,
            Duration attemptDeadline) throws ClassifyException {
        long remainingClassify = deadlineNanos - System.nanoTime();
        if (remainingClassify <= 0) {
            throw new ClassifyException("incomplete: classify deadline exceeded");
        }
        long attemptNanos = Math.min(attemptDeadline.toNanos(), remainingClassify);
        // When the run-level budget is what clipped this attempt, the failure is the
        // classify deadline, not the attempt deadline: a per-chunk retry cannot help.
        boolean clippedByClassifyDeadline = remainingClassify <= attemptDeadline.toNanos();
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> abort = watchdog.schedule(
                worker::interrupt, attemptNanos, TimeUnit.NANOSECONDS);
        try {
            return call.call();
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            if (interrupted(e)) {
                Thread.currentThread().interrupt();
                if (clippedByClassifyDeadline) {
                    throw new ClassifyException("incomplete: classify deadline exceeded", e);
                }
                throw new AttemptDeadlineException(
                        "incomplete: " + label + " exceeded attempt deadline", e);
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ClassifyException("classify failed: " + e.getMessage(), e);
        } finally {
            abort.cancel(false);
            Thread.interrupted();
        }
    }

    private static boolean interrupted(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof InterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static LlmPhaseResult assemblePhase(
            List<PreparedPacket> prepared,
            Map<Long, PacketDisposition> dispositions,
            Map<Long, List<ProjectFactBinding>> facts,
            Map<Long, LayerAJudgment> judged,
            Set<Long> unclassified) throws ClassifyException {
        List<PacketDisposition> orderedDispositions = new ArrayList<>();
        List<ProjectFactBinding> orderedFacts = new ArrayList<>();
        Map<Long, LayerAJudgment> layerA = new LinkedHashMap<>();
        List<Long> unclassifiedIds = new ArrayList<>();
        for (PreparedPacket packet : prepared) {
            long id = packet.candidate().candidateId();
            PacketDisposition disposition = dispositions.get(id);
            if (disposition == null) {
                if (unclassified.contains(id)) {
                    unclassifiedIds.add(id);
                    continue;
                }
                // Neither classified nor deliberately skipped: a real scheduling bug.
                throw new ClassifyException(
                        "incomplete: missing Layer A for candidate " + id);
            }
            orderedDispositions.add(disposition);
            orderedFacts.addAll(facts.getOrDefault(id, List.of()));
            LayerAJudgment judgment = judged.get(id);
            if (judgment != null) {
                layerA.put(id, judgment);
            }
        }
        if (!unclassifiedIds.isEmpty()) {
            if (unclassifiedIds.size() == prepared.size()) {
                // Every candidate missed: that is a systemic outage, not a straggler. The
                // write below would delete the run's existing dispositions and insert
                // nothing, so abort and keep the previous snapshot instead.
                throw new ClassifyException(
                        "incomplete: every Layer A candidate missed its deadline ("
                                + unclassifiedIds.size() + "); previous snapshot kept");
            }
            System.err.println("Layer A left " + unclassifiedIds.size()
                    + " candidate(s) unclassified: " + unclassifiedIds);
        }
        return new LlmPhaseResult(
                orderedDispositions, orderedFacts, Map.copyOf(layerA), List.copyOf(unclassifiedIds));
    }

    private record MaterializeResult(
            NomenclatureBinding binding,
            String rejectReason,
            LeafSelectionOutcome leafSelection,
            OntologySlice slice,
            NomenclatureCatalog.PendingSoftLeaf mintedLeaf) {
        static MaterializeResult ok(
                NomenclatureBinding binding,
                LeafSelectionOutcome leafSelection,
                OntologySlice slice,
                NomenclatureCatalog.PendingSoftLeaf mintedLeaf) {
            return new MaterializeResult(binding, null, leafSelection, slice, mintedLeaf);
        }

        static MaterializeResult reject(String reason, OntologySlice slice) {
            return new MaterializeResult(null, reason, LeafSelectionOutcome.NONE, slice, null);
        }
    }

    enum LeafSelectionOutcome {
        NONE,
        CATALOG_PREFERRED,
        SOFT_GENERIC_KEPT,
        LEAF_AMBIGUOUS
    }

    private static MaterializeResult tryMaterializeBinding(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            Packet packet,
            CandidateRow candidate,
            long parseRunId,
            LayerBLineJudgment line) {
        try {
            return materializeBinding(
                    catalog, mandateId, slice, packet, candidate, parseRunId, line);
        } catch (ClassifyException e) {
            return MaterializeResult.reject(e.getMessage(), slice);
        }
    }

    private static MaterializeResult materializeBinding(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            Packet packet,
            CandidateRow candidate,
            long parseRunId,
            LayerBLineJudgment line) throws ClassifyException {
        String role = line.amountRole() == null
                ? null
                : line.amountRole().trim().toLowerCase(Locale.ROOT);
        if (!AmountRole.isKnown(role)) {
            throw new ClassifyException(
                    "invalid amount_role '" + line.amountRole()
                            + "' for coord " + line.coord());
        }
        PacketCell cell = findCell(packet, line.coord())
                .orElseThrow(() -> new ClassifyException(
                        "Layer B coord not in Packet: " + line.coord()));
        if (!LayerBAmountSupport.isBindableForRole(packet, cell, role)) {
            NumericKind kind = LayerBAmountSupport.classifyKind(packet, cell);
            if (!kind.allowsCostRole()
                    && (AmountRole.ADD.equals(role)
                            || AmountRole.DEDUCT.equals(role)
                            || AmountRole.TOTAL.equals(role))) {
                throw new ClassifyException(
                        "non_money_numeric kind=" + kind.name().toLowerCase(Locale.ROOT)
                                + " at " + line.coord()
                                + " cannot use cost role " + role);
            }
            throw new ClassifyException(
                    "Layer B binding requires an amount cell at " + line.coord());
        }
        String path = line.path().trim();
        LeafSelectionOutcome leafSelection = LeafSelectionOutcome.NONE;
        HardLeafChoice evidenceLeaf = hardCatalogLeafFromEvidence(slice, packet, cell, line, path);
        Optional<NomenclatureNode> midLevel = slice.node(path);
        if (midLevel.isPresent() && !midLevel.get().leaf()) {
            if (evidenceLeaf.kind() == HardLeafChoice.Kind.UNIQUE) {
                path = evidenceLeaf.path();
                leafSelection = LeafSelectionOutcome.CATALOG_PREFERRED;
            } else {
                // Mid-level without a unique hard-leaf match used to invent a leaf from
                // the row text. That copied suppliers and rates into the catalogue. The
                // model must name a category via soft[], or the amount stays unbound.
                throw new ClassifyException(TranscribedLeaf.REJECT_REASON
                        + ": mid-level '" + path
                        + "' needs a category leaf, not the row text");
            }
        } else if (wouldBindAsSoft(slice, path)) {
            if (evidenceLeaf.kind() == HardLeafChoice.Kind.UNIQUE) {
                path = evidenceLeaf.path();
                leafSelection = LeafSelectionOutcome.CATALOG_PREFERRED;
            } else if (evidenceLeaf.kind() == HardLeafChoice.Kind.AMBIGUOUS) {
                leafSelection = LeafSelectionOutcome.LEAF_AMBIGUOUS;
            } else {
                leafSelection = LeafSelectionOutcome.SOFT_GENERIC_KEPT;
            }
        }
        String resolvedPath = path;
        boolean viaAlias = slice.aliases().stream()
                .anyMatch(alias -> OntologySlice.normalize(alias.aliasText())
                        .equals(OntologySlice.normalize(line.verbatim()))
                        && alias.leafPath().equals(resolvedPath));
        if (!viaAlias && evidenceLeaf.kind() == HardLeafChoice.Kind.UNIQUE
                && path.equals(evidenceLeaf.path())) {
            viaAlias = evidenceMatchedViaAlias(slice, packet, cell, line, path);
        }
        boolean softLeaf = false;
        NomenclatureCatalog.PendingSoftLeaf mintedLeaf = null;
        Optional<NomenclatureNode> existing = slice.node(path);
        if (existing.isPresent()) {
            if (!existing.get().leaf()) {
                throw new ClassifyException(
                        "Layer B path must be a leaf join key, not mid-level: '" + path + "'");
            }
            softLeaf = NomenclatureNode.LAYER_MANDATE_SOFT.equals(existing.get().layer());
        } else {
            int sep = path.lastIndexOf(" > ");
            if (sep <= 0) {
                throw new ClassifyException(
                        "cannot invent mid-level path for Layer B binding: '" + path + "'");
            }
            String parentPath = path.substring(0, sep);
            String leafName = path.substring(sep + 3).trim();
            if (TranscribedLeaf.isTranscribed(leafName)) {
                throw new ClassifyException(TranscribedLeaf.REJECT_REASON
                        + ": leaf '" + leafName + "' is row evidence, not a category");
            }
            NomenclatureNode parent = slice.node(parentPath).orElse(null);
            if (parent == null || parent.leaf()) {
                throw new ClassifyException(
                        "cannot invent mid-level '" + parentPath
                                + "'; soft leaves attach under known mid-levels");
            }
            try {
                // Staged rather than written: the classify write transaction persists it,
                // so a run that later rolls back never leaves an unbound soft leaf behind
                // (ADR: the overlay must not outlive the binding it exists for).
                NomenclatureCatalog.StagedSoftLeaf staged = catalog.stagePendingSoftLeaf(
                        slice, mandateId, parentPath, leafName, line.aliases());
                slice = staged.slice();
                mintedLeaf = staged.pending();
                softLeaf = true;
            } catch (NomenclatureException e) {
                throw new ClassifyException(e.getMessage(), e);
            }
        }
        return MaterializeResult.ok(
                new NomenclatureBinding(
                        cell.cellId(),
                        parseRunId,
                        candidate.candidateId(),
                        line.verbatim(),
                        path,
                        role,
                        softLeaf,
                        viaAlias,
                        line.confidence()),
                leafSelection,
                slice,
                mintedLeaf);
    }

    private static void recordLeafSelection(
            LayerBBindingStats stats, LeafSelectionOutcome outcome) {
        if (outcome == null) {
            return;
        }
        switch (outcome) {
            case CATALOG_PREFERRED -> stats.addCatalogPreferred();
            case SOFT_GENERIC_KEPT -> stats.addSoftGenericKept();
            case LEAF_AMBIGUOUS -> stats.addLeafAmbiguous();
            case NONE -> {
            }
        }
    }

    /**
     * Soft/generic proposals (invented soft leaves, existing mandate soft leaves, or
     * mid-level recovery that would invent one) yield to an unambiguous hard catalog
     * leaf when row/header evidence supports it.
     */
    private static boolean wouldBindAsSoft(OntologySlice slice, String path) {
        Optional<NomenclatureNode> existing = slice.node(path);
        if (existing.isPresent()) {
            return existing.get().leaf()
                    && NomenclatureNode.LAYER_MANDATE_SOFT.equals(existing.get().layer());
        }
        return true;
    }

    private static HardLeafChoice hardCatalogLeafFromEvidence(
            OntologySlice slice,
            Packet packet,
            PacketCell cell,
            LayerBLineJudgment line,
            String proposedPath) {
        LinkedHashSet<String> hardLeaves = new LinkedHashSet<>();
        for (String evidence : evidenceTexts(packet, cell, line, proposedPath)) {
            Optional<String> resolved = slice.resolve(evidence);
            if (resolved.isEmpty()) {
                continue;
            }
            Optional<NomenclatureNode> node = slice.node(resolved.get());
            if (node.isEmpty() || !node.get().leaf()) {
                continue;
            }
            if (NomenclatureNode.LAYER_MANDATE_SOFT.equals(node.get().layer())) {
                continue;
            }
            hardLeaves.add(resolved.get());
        }
        if (hardLeaves.isEmpty()) {
            return HardLeafChoice.none();
        }
        if (hardLeaves.size() > 1) {
            return HardLeafChoice.ambiguous();
        }
        return HardLeafChoice.unique(hardLeaves.iterator().next());
    }

    private static List<String> evidenceTexts(
            Packet packet, PacketCell cell, LayerBLineJudgment line, String proposedPath) {
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        String rowLabel = LayerBAmountSupport.resolveRowLabel(packet, cell);
        if (rowLabel != null && !rowLabel.isBlank()) {
            texts.add(rowLabel.trim());
        }
        if (line.verbatim() != null && !line.verbatim().isBlank()) {
            texts.add(line.verbatim().trim());
        }
        String leafSegment = leafSegmentOf(proposedPath);
        if (leafSegment != null && !leafSegment.isBlank()) {
            texts.add(leafSegment.trim());
        }
        return List.copyOf(texts);
    }

    private static String leafSegmentOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int sep = path.lastIndexOf(" > ");
        return sep < 0 ? path.trim() : path.substring(sep + 3).trim();
    }

    private static boolean evidenceMatchedViaAlias(
            OntologySlice slice,
            Packet packet,
            PacketCell cell,
            LayerBLineJudgment line,
            String path) {
        for (String evidence : evidenceTexts(packet, cell, line, path)) {
            String needle = OntologySlice.normalize(evidence);
            boolean matched = slice.aliases().stream()
                    .anyMatch(alias -> OntologySlice.normalize(alias.aliasText()).equals(needle)
                            && alias.leafPath().equals(path));
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private record HardLeafChoice(Kind kind, String path) {
        enum Kind { NONE, UNIQUE, AMBIGUOUS }

        static HardLeafChoice none() {
            return new HardLeafChoice(Kind.NONE, null);
        }

        static HardLeafChoice unique(String path) {
            return new HardLeafChoice(Kind.UNIQUE, path);
        }

        static HardLeafChoice ambiguous() {
            return new HardLeafChoice(Kind.AMBIGUOUS, null);
        }
    }

    private static Optional<ProjectFactBinding> materializeFact(
            OntologySlice slice,
            CandidateRow candidate,
            long parseRunId,
            Packet packet,
            ProjectFactJudgment fact) {
        String path = fact.factPath().trim();
        if (path.startsWith("Project Cost")) {
            return Optional.empty();
        }
        if (slice.projectFactField(path).isEmpty()) {
            return Optional.empty();
        }
        Long cellId = null;
        if (fact.coord() != null) {
            cellId = findCell(packet, fact.coord()).map(PacketCell::cellId).orElse(null);
        }
        return Optional.of(new ProjectFactBinding(
                parseRunId, candidate.candidateId(), cellId, fact.verbatim(), path));
    }

    /** Bindings the group-level naming question produced, plus what it could not name. */
    private record GapFillResult(
            List<NomenclatureBinding> bindings,
            Map<Long, UnboundReason> reasons,
            List<String> failures,
            List<BindingPeerWriter.PendingPeerLine> pendingPeers,
            List<NomenclatureCatalog.PendingSoftLeaf> pendingSoftLeaves) {}

    /**
     * Ask once per distinct worksheet-scoped qualified label for the cells whose role
     * the graph proved but whose name the catalog does not hold, then apply each
     * answer to every cell sharing that label on that worksheet. Membership
     * re-projects by construction: the answer is keyed on the label, so two cells
     * with one label cannot diverge.
     */
    private GapFillResult fillLabelGaps(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            long parseRunId,
            CellGraph graph,
            DeterministicBinder.Result deterministic,
            Map<Long, LayerAJudgment> layerA,
            LayerBBindingStats stats,
            long deadlineNanos) throws ClassifyException {
        if (deterministic.queued().isEmpty()) {
            return new GapFillResult(List.of(), Map.of(), List.of(), List.of(), List.of());
        }
        List<LabelGapFiller.Queued> queued = new ArrayList<>();
        Set<String> skippedUnclassified = new LinkedHashSet<>();
        Map<String, List<DeterministicBinder.QueuedGroup>> byLabel = new LinkedHashMap<>();
        for (DeterministicBinder.QueuedGroup group : deterministic.queued()) {
            byLabel.computeIfAbsent(group.label().key(), key -> new ArrayList<>()).add(group);
        }
        for (Map.Entry<String, List<DeterministicBinder.QueuedGroup>> entry : byLabel.entrySet()) {
            DeterministicBinder.QueuedGroup representative = entry.getValue().get(0);
            GraphCell cell = graph.cells().get(representative.cellId());
            if (cell == null) {
                continue;
            }
            if (!layerA.containsKey(representative.candidateId())) {
                // This Candidate's Layer A never produced a disposition (retry missed).
                // Asking Layer B without one would invent context, so the group's names
                // stay unbound as LLM_UNAVAILABLE rather than being asked and declined.
                skippedUnclassified.add(entry.getKey());
                continue;
            }
            PacketCell amount = packetCellOf(cell);
            queued.add(new LabelGapFiller.Queued(
                    representative.label(),
                    amount,
                    LabelGapFiller.labelCellFor(amount, representative.label().memberLabel()),
                    representative.candidateId(),
                    parseRunId));
        }

        AtomicReference<ClassifyException> fatal = new AtomicReference<>();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "classify-gap-fill-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        LabelGapFiller filler = new LabelGapFiller(new ClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                throw new UnsupportedOperationException("gap fill does not call Layer A");
            }

            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                try {
                    return callLlm(
                            () -> llm.classifyLayerB(prompt),
                            "label gap fill",
                            watchdog,
                            deadlineNanos);
                } catch (AttemptDeadlineException e) {
                    throw new RuntimeException(e);
                } catch (ClassifyException e) {
                    fatal.compareAndSet(null, e);
                    throw new RuntimeException(e);
                }
            }
        });
        List<NomenclatureCatalog.PendingSoftLeaf> pendingSoftLeaves = new ArrayList<>();
        Map<String, LayerBLineJudgment> answers;
        try {
            answers = filler.fill(
                    queued,
                    slice,
                    candidateId -> layerA.get(candidateId),
                    (current, lines) -> {
                        OntologySlice next = current;
                        for (LayerBLineJudgment line : lines) {
                            next = admitCategoryForNextCell(
                                    catalog, mandateId, next, line, pendingSoftLeaves);
                        }
                        return next;
                    });
        } finally {
            watchdog.shutdownNow();
        }
        if (fatal.get() != null) {
            throw fatal.get();
        }

        List<NomenclatureBinding> bindings = new ArrayList<>();
        Map<Long, UnboundReason> reasons = new LinkedHashMap<>();
        List<BindingPeerWriter.PendingPeerLine> pendingPeers = new ArrayList<>();
        OntologySlice currentSlice = filler.currentSlice() != null ? filler.currentSlice() : slice;
        Map<String, MaterializeResult> minted = new LinkedHashMap<>();
        for (Map.Entry<String, List<DeterministicBinder.QueuedGroup>> entry : byLabel.entrySet()) {
            LayerBLineJudgment answer = answers.get(entry.getKey());
            if (answer == null) {
                UnboundReason reason = skippedUnclassified.contains(entry.getKey())
                        ? UnboundReason.LLM_UNAVAILABLE
                        : UnboundReason.LLM_DECLINED;
                for (DeterministicBinder.QueuedGroup group : entry.getValue()) {
                    reasons.put(group.cellId(), reason);
                }
                continue;
            }
            MaterializeResult mintedPath = minted.get(entry.getKey());
            if (mintedPath == null) {
                DeterministicBinder.QueuedGroup representative = entry.getValue().get(0);
                GraphCell cell = graph.cells().get(representative.cellId());
                if (cell == null) {
                    continue;
                }
                PacketCell amount = packetCellOf(cell);
                PacketCell labelCell = LabelGapFiller.labelCellFor(
                        amount, representative.label().memberLabel());
                List<PacketCell> packetCells = labelCell == null
                        ? List.of(amount)
                        : List.of(amount, labelCell);
                Packet packet = new Packet(
                        representative.candidateId(),
                        parseRunId,
                        cell.worksheetId(),
                        "child",
                        packetCells,
                        List.of(),
                        true);
                CandidateRow candidate = new CandidateRow(
                        representative.candidateId(), parseRunId, cell.worksheetId(),
                        "child", null, null, null, null, null, null, null, null,
                        false, null, null, null, null);
                String role = answer.amountRole() != null
                        ? answer.amountRole()
                        : representative.amountRole();
                LayerBLineJudgment line = new LayerBLineJudgment(
                        amount.coord(),
                        representative.label().memberLabel(),
                        answer.path(),
                        role,
                        answer.aliases(),
                        answer.confidence(),
                        answer.peers());
                mintedPath = tryMaterializeBinding(
                        catalog, mandateId, currentSlice, packet, candidate, parseRunId, line);
                stats.addProposed(1);
                minted.put(entry.getKey(), mintedPath);
                if (mintedPath.binding() != null) {
                    recordLeafSelection(stats, mintedPath.leafSelection());
                    currentSlice = mintedPath.slice();
                    if (mintedPath.mintedLeaf() != null) {
                        pendingSoftLeaves.add(mintedPath.mintedLeaf());
                    }
                }
            }
            if (mintedPath.binding() == null) {
                UnboundReason refuse = mintedPath.rejectReason() != null
                        && mintedPath.rejectReason().startsWith(TranscribedLeaf.REJECT_REASON)
                        ? UnboundReason.TRANSCRIBED_LABEL
                        : UnboundReason.LLM_DECLINED;
                for (DeterministicBinder.QueuedGroup group : entry.getValue()) {
                    reasons.put(group.cellId(), refuse);
                }
                stats.addRejected(mintedPath.rejectReason());
                continue;
            }
            String path = mintedPath.binding().path();
            boolean softLeaf = mintedPath.binding().softLeaf();
            for (DeterministicBinder.QueuedGroup group : entry.getValue()) {
                GraphCell cell = graph.cells().get(group.cellId());
                // The graph's role wins only where it is proven (an aggregation gave it).
                // A standalone cell's role there is only a default (ADD), so a line the
                // model judged otherwise — "Less: AC" as deduct — must still take that
                // judgment rather than being silently forced to add.
                String cellRole = deterministic.roleProven(group.cellId())
                        ? group.amountRole()
                        : mintedPath.binding().amountRole();
                bindings.add(new NomenclatureBinding(
                        group.cellId(),
                        parseRunId,
                        group.candidateId(),
                        group.label().memberLabel(),
                        path,
                        cellRole,
                        softLeaf,
                        mintedPath.binding().viaAlias(),
                        answer.confidence(),
                        BindingSource.LLM_LABEL,
                        entry.getKey(),
                        null));
                if (cell != null && !answer.peers().isEmpty()) {
                    pendingPeers.add(new BindingPeerWriter.PendingPeerLine(
                            group.cellId(),
                            cell.worksheetId(),
                            path,
                            answer.peers()));
                }
            }
        }
        stats.addLabelBindings(bindings.size());
        return new GapFillResult(
                bindings, reasons, filler.failures(), pendingPeers, pendingSoftLeaves);
    }

    /**
     * A category the model just named is on the slice before the next label is
     * asked. The seed catalog is unchanged. A transcribed row is not a category.
     */
    private static OntologySlice admitCategoryForNextCell(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            LayerBLineJudgment line,
            List<NomenclatureCatalog.PendingSoftLeaf> pending) {
        if (line.path() == null || slice.node(line.path().trim()).isPresent()) {
            return slice;
        }
        String path = line.path().trim();
        int sep = path.lastIndexOf(" > ");
        if (sep <= 0) {
            return slice;
        }
        String parentPath = path.substring(0, sep);
        String leafName = path.substring(sep + 3).trim();
        if (TranscribedLeaf.isTranscribed(leafName)) {
            return slice;
        }
        NomenclatureNode parent = slice.node(parentPath).orElse(null);
        if (parent == null || parent.leaf()) {
            return slice;
        }
        try {
            NomenclatureCatalog.StagedSoftLeaf staged = catalog.stagePendingSoftLeaf(
                    slice, mandateId, parentPath, leafName, line.aliases());
            pending.add(staged.pending());
            return staged.slice();
        } catch (NomenclatureException e) {
            return slice;
        }
    }

    private static String mintSoftLeaf(
            NomenclatureCatalog catalog, long mandateId, String parentPath, String leafName) {
        String path = parentPath + OntologySlice.SEPARATOR + leafName;
        try {
            catalog.putSoftLeaf(mandateId, parentPath, leafName, List.of());
            return path;
        } catch (NomenclatureException e) {
            // Already minted by an earlier label in this run; that is the same leaf.
            return catalog.sliceForMandate(mandateId).node(path)
                    .filter(NomenclatureNode::leaf)
                    .map(NomenclatureNode::path)
                    .orElse(null);
        }
    }

    /** The graph's view of a cell as a Packet cell, so the existing prompt path works. */
    private static PacketCell packetCellOf(GraphCell cell) {
        return new PacketCell(
                cell.cellId(),
                cell.worksheetId(),
                cell.coord(),
                cell.rowNum(),
                cell.colNum(),
                PacketCell.ROLE_CORE,
                cell.valueType(),
                null,
                cell.displayValue(),
                cell.numericValue(),
                cell.formulaText(),
                false,
                false);
    }

    /** Attach the persisted aggregation id once the graph write has assigned one. */
    private static NomenclatureBinding withAggregation(
            NomenclatureBinding binding,
            DeterministicBinder.Result deterministic,
            Map<Long, Long> aggregationIds) {
        Long headCellId = deterministic.headCellByCell().get(binding.cellId());
        if (headCellId == null) {
            return binding;
        }
        return binding.withSource(
                binding.source(), binding.labelKey(), aggregationIds.get(headCellId));
    }

    /**
     * One candidate per cell for binding provenance. A cell can sit in several
     * Candidates; the narrowest one is the most specific home, and the coverage
     * parent is only a backstop.
     */
    private static Map<Long, Long> candidateByCell(
            WorkspaceRepository repo, long parseRunId, List<CandidateRow> candidates)
            throws java.sql.SQLException {
        Map<Long, CandidateRow> byId = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            byId.put(candidate.candidateId(), candidate);
        }
        Map<Long, Long> byCell = new HashMap<>();
        for (long[] pair : repo.selectCandidateMembersForParseRun(parseRunId)) {
            long candidateId = pair[0];
            long cellId = pair[1];
            Long current = byCell.get(cellId);
            if (current == null || preferCandidate(byId.get(candidateId), byId.get(current))) {
                byCell.put(cellId, candidateId);
            }
        }
        return Map.copyOf(byCell);
    }

    private static boolean preferCandidate(CandidateRow candidate, CandidateRow current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        boolean candidateIsParent = "coverage_parent".equals(candidate.candidateKind());
        boolean currentIsParent = "coverage_parent".equals(current.candidateKind());
        if (candidateIsParent != currentIsParent) {
            return currentIsParent;
        }
        return candidate.candidateId() < current.candidateId();
    }

    private static Optional<PacketCell> findCell(Packet packet, String coord) {
        if (coord == null || coord.isBlank()) {
            return Optional.empty();
        }
        String needle = coord.trim().toUpperCase(Locale.ROOT);
        for (PacketCell cell : packet.cells()) {
            if (cell.coord() != null && cell.coord().toUpperCase(Locale.ROOT).equals(needle)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    private static boolean sameCandidateIds(List<CandidateRow> expected, List<CandidateRow> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        Map<Long, CandidateRow> byId = new HashMap<>();
        for (CandidateRow row : actual) {
            byId.put(row.candidateId(), row);
        }
        for (CandidateRow row : expected) {
            CandidateRow current = byId.get(row.candidateId());
            if (current == null
                    || !Objects.equals(row.candidateKind(), current.candidateKind())
                    || !Objects.equals(row.parentCandidateId(), current.parentCandidateId())
                    || row.worksheetId() != current.worksheetId()) {
                return false;
            }
        }
        return true;
    }

    private static LayerAJudgment parentContext(
            CandidateRow candidate,
            Map<Long, LayerAJudgment> judged,
            Map<Long, LayerAJudgment> coverageByWorksheet) {
        if (candidate.parentCandidateId() != null) {
            LayerAJudgment parent = judged.get(candidate.parentCandidateId());
            if (parent != null) {
                return parent;
            }
        }
        return coverageByWorksheet.get(candidate.worksheetId());
    }

    private static LayerAJudgment requireJudgment(
            LayerAJudgment judgment, long candidateId, ScheduleFamilyCatalog families)
            throws ClassifyException {
        if (judgment != null) {
            families.admit(judgment.scheduleFamily());
        }
        if (judgment == null
                || !families.contains(judgment.scheduleFamily())
                || !Triage.isKnown(judgment.triage())
                || !Relevance.isKnown(judgment.relevance())) {
            throw new ClassifyException(
                    "LLM returned an invalid Layer A judgment for candidate " + candidateId);
        }
        if (Triage.isSoft(judgment.triage()) && !Relevance.NOISE.equals(judgment.relevance())) {
            return new LayerAJudgment(
                    judgment.scheduleFamily(),
                    judgment.triage(),
                    Relevance.NOISE,
                    judgment.rowLabels(),
                    judgment.columnHeaders(),
                    judgment.packetDefaultHead(),
                    judgment.facts());
        }
        return judgment;
    }
}
