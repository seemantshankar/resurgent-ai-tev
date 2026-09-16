package com.resurgent.tev.parser.classify;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final ClassifyLimits limits;

    public ClassifyService(ClassifierLlm llm) {
        this(llm, new DiscoverService(), new InterpretationWriter(), ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover) {
        this(llm, discover, new InterpretationWriter(), ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover, ClassifyLimits limits) {
        this(llm, discover, new InterpretationWriter(), limits);
    }

    public ClassifyService(
            ClassifierLlm llm, DiscoverService discover, InterpretationWriter interpretationWriter) {
        this(llm, discover, interpretationWriter, ClassifyLimits.defaults());
    }

    public ClassifyService(
            ClassifierLlm llm,
            DiscoverService discover,
            InterpretationWriter interpretationWriter,
            ClassifyLimits limits) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.discover = Objects.requireNonNull(discover, "discover");
        this.interpretationWriter =
                Objects.requireNonNull(interpretationWriter, "interpretationWriter");
        this.limits = Objects.requireNonNull(limits, "limits");
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
            OntologySlice slice = catalog.sliceForMandate(mandateId);
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
            if (candidates.isEmpty()) {
                throw new ClassifyException("no Candidates for parse run " + parseRunId
                        + "; run discover first");
            }

            // LLM calls stay outside the write transaction so long classify runs do not
            // hold a SQLite write lock. Packets are built serially; Layer A/B run
            // concurrently with parent-before-child and A→B pipelining; bindings
            // materialize serially after all LLM work completes or the run is incomplete.
            List<PreparedPacket> prepared = new ArrayList<>();
            int coverageParents = 0;
            for (CandidateRow candidate : candidates) {
                boolean cheapPass = "coverage_parent".equals(candidate.candidateKind());
                if (cheapPass) {
                    coverageParents++;
                }
                Packet packet = discover.buildPacket(repo, candidate.candidateId());
                Packet redacted = PacketRedactor.redact(packet, cheapPass);
                prepared.add(new PreparedPacket(candidate, packet, redacted, cheapPass));
            }

            LlmPhaseResult llmPhase = runLlmPhase(slice, parseRunId, prepared);
            LayerBBindingStats layerBStats = new LayerBBindingStats();
            LayerBMaterializeResult layerB = materializeLayerB(
                    catalog,
                    mandateId,
                    slice,
                    parseRunId,
                    llmPhase.jobs(),
                    llmPhase.judgments(),
                    layerBStats);
            List<PacketDisposition> dispositions = llmPhase.dispositions();
            List<ProjectFactBinding> factBindings = llmPhase.facts();
            List<NomenclatureBinding> bindings = layerB.bindings();
            List<BindingPeer> bindingPeers;
            try {
                bindingPeers = BindingPeerWriter.buildPeers(
                        repo, parseRunId, bindings, layerB.pendingPeers());
            } catch (PeerCoordResolver.PeerCoordException e) {
                throw new ClassifyException(e.getMessage(), e);
            }

            db.connection().setAutoCommit(false);
            try {
                List<CandidateRow> current = repo.selectCandidatesForParseRun(parseRunId);
                if (!sameCandidateIds(candidates, current)) {
                    throw new ClassifyException(
                            "Candidates changed during classify for parse run " + parseRunId
                                    + "; re-run discover then classify");
                }
                repo.deleteBindingPeersForParseRun(parseRunId);
                repo.deleteProjectFactBindingsForParseRun(parseRunId);
                repo.deleteNomenclatureBindingsForParseRun(parseRunId);
                repo.deletePacketDispositionsForParseRun(parseRunId);
                for (PacketDisposition disposition : dispositions) {
                    repo.insertPacketDisposition(disposition);
                }
                for (ProjectFactBinding fact : factBindings) {
                    repo.insertProjectFactBinding(fact);
                }
                for (NomenclatureBinding binding : bindings) {
                    repo.insertNomenclatureBinding(binding);
                }
                for (BindingPeer peer : bindingPeers) {
                    repo.insertBindingPeer(peer);
                }
                int interpretationCount =
                        interpretationWriter.write(repo, parseRunId, bindings);
                repo.commit();
                return new ClassifySummary(
                        parseRunId,
                        dispositions.size(),
                        coverageParents,
                        bindings.size(),
                        interpretationCount,
                        layerBStats);
            } catch (ClassifyException e) {
                repo.rollback();
                throw e;
            } catch (Exception e) {
                repo.rollback();
                throw new ClassifyException("classify failed: " + e.getMessage(), e);
            } finally {
                db.connection().setAutoCommit(true);
            }
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("classify failed: " + msg, e);
        }
    }

    /**
     * Layer B LLM calls are submitted as each Packet's Layer A finishes so A and B
     * overlap. Soft leaves created by earlier packets are <em>not</em> visible to
     * concurrent prompts; reconciliation happens here during serial materialize:
     * reload the slice after each accept, and if {@code putSoftLeaf} races on an
     * already-created path, treat it as an existing leaf.
     */
    private record LayerBMaterializeResult(
            List<NomenclatureBinding> bindings,
            List<BindingPeerWriter.PendingPeerLine> pendingPeers) {}

    private LayerBMaterializeResult materializeLayerB(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            long parseRunId,
            List<LayerBJob> jobs,
            List<List<LayerBLineJudgment>> judgmentsByJob,
            LayerBBindingStats stats) {
        if (jobs.isEmpty()) {
            return new LayerBMaterializeResult(List.of(), List.of());
        }
        List<NomenclatureBinding> bindings = new ArrayList<>();
        List<BindingPeerWriter.PendingPeerLine> pendingPeers = new ArrayList<>();
        Set<Long> boundCells = new HashSet<>();
        OntologySlice currentSlice = slice;
        for (int i = 0; i < jobs.size(); i++) {
            LayerBJob job = jobs.get(i);
            List<LayerBLineJudgment> lines = judgmentsByJob.get(i);
            stats.addProposed(lines.size());
            for (LayerBLineJudgment line : lines) {
                MaterializeResult result = tryMaterializeBinding(
                        catalog,
                        mandateId,
                        currentSlice,
                        job.packet(),
                        job.candidate(),
                        parseRunId,
                        line);
                if (result.binding() == null) {
                    stats.addRejected(result.rejectReason());
                    continue;
                }
                if (!boundCells.add(result.binding().cellId())) {
                    stats.addDuplicate();
                    continue;
                }
                bindings.add(result.binding());
                if (!line.peers().isEmpty()) {
                    pendingPeers.add(new BindingPeerWriter.PendingPeerLine(
                            result.binding().cellId(),
                            job.candidate().worksheetId(),
                            result.binding().path(),
                            line.peers()));
                }
                stats.addAccepted();
                currentSlice = catalog.sliceForMandate(mandateId);
            }
        }
        return new LayerBMaterializeResult(bindings, pendingPeers);
    }

    private record LayerBJob(CandidateRow candidate, Packet packet, LayerBPrompt prompt) {}

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
            long deadlineNanos) throws ClassifyException {
        CandidateRow candidate = prepared.candidate();
        LayerAJudgment judgment = requireJudgment(
                callLlm(
                        () -> llm.classifyLayerA(new LayerAPrompt(
                                prepared.redacted(), slice, parent, prepared.cheapPass())),
                        "Layer A candidate " + candidate.candidateId(),
                        watchdog,
                        deadlineNanos),
                candidate.candidateId());
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

    private record LlmPhaseResult(
            List<PacketDisposition> dispositions,
            List<ProjectFactBinding> facts,
            List<LayerBJob> jobs,
            List<List<LayerBLineJudgment>> judgments) {}

    @FunctionalInterface
    private interface ClassifyTask {
        void run() throws ClassifyException;
    }

    private LlmPhaseResult runLlmPhase(
            OntologySlice slice, long parseRunId, List<PreparedPacket> prepared)
            throws ClassifyException {
        long deadlineNanos = System.nanoTime() + limits.classifyDeadline().toNanos();
        Map<Long, LayerAJudgment> judged = new ConcurrentHashMap<>();
        Map<Long, LayerAJudgment> coverageByWorksheet = new ConcurrentHashMap<>();
        Map<Long, PacketDisposition> dispositions = new ConcurrentHashMap<>();
        Map<Long, List<ProjectFactBinding>> facts = new ConcurrentHashMap<>();
        Map<Long, LayerBJob> jobs = new ConcurrentHashMap<>();
        Map<Long, List<LayerBLineJudgment>> judgments = new ConcurrentHashMap<>();
        Set<Long> submittedA = ConcurrentHashMap.newKeySet();
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
                                    == null) {
                        submittedA.remove(id);
                        continue;
                    }
                    submitTask(phaser, pool, failure, deadlineNanos, () -> {
                        LayerAJudgment parent = parentContext(
                                packet.candidate(), judged, coverageByWorksheet);
                        LayerAWork work = layerAWork(
                                slice, parseRunId, packet, parent, watchdog, deadlineNanos);
                        judged.put(id, work.judgment());
                        if (packet.cheapPass()) {
                            coverageByWorksheet.put(
                                    packet.candidate().worksheetId(), work.judgment());
                        }
                        dispositions.put(id, work.disposition());
                        facts.put(id, work.facts());
                        if (!packet.cheapPass()
                                && LayerBAmountSupport.hasAmountCells(packet.redacted())) {
                            LayerBJob job = new LayerBJob(
                                    packet.candidate(),
                                    packet.packet(),
                                    new LayerBPrompt(
                                            packet.redacted(),
                                            slice,
                                            work.judgment(),
                                            parent));
                            jobs.put(id, job);
                            submitTask(phaser, pool, failure, deadlineNanos, () -> {
                                List<LayerBLineJudgment> lines = callLlm(
                                        () -> llm.classifyLayerB(job.prompt()),
                                        "Layer B candidate " + id,
                                        watchdog,
                                        deadlineNanos);
                                judgments.put(id, lines);
                            });
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
            return assemblePhase(prepared, dispositions, facts, jobs, judgments);
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
        long remainingClassify = deadlineNanos - System.nanoTime();
        if (remainingClassify <= 0) {
            throw new ClassifyException("incomplete: classify deadline exceeded");
        }
        long attemptNanos = Math.min(limits.attemptDeadline().toNanos(), remainingClassify);
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
                throw new ClassifyException(
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
            Map<Long, LayerBJob> jobs,
            Map<Long, List<LayerBLineJudgment>> judgments) throws ClassifyException {
        List<PacketDisposition> orderedDispositions = new ArrayList<>();
        List<ProjectFactBinding> orderedFacts = new ArrayList<>();
        List<LayerBJob> orderedJobs = new ArrayList<>();
        List<List<LayerBLineJudgment>> orderedJudgments = new ArrayList<>();
        for (PreparedPacket packet : prepared) {
            long id = packet.candidate().candidateId();
            PacketDisposition disposition = dispositions.get(id);
            if (disposition == null) {
                throw new ClassifyException(
                        "incomplete: missing Layer A for candidate " + id);
            }
            orderedDispositions.add(disposition);
            orderedFacts.addAll(facts.getOrDefault(id, List.of()));
            LayerBJob job = jobs.get(id);
            if (job != null) {
                List<LayerBLineJudgment> lines = judgments.get(id);
                if (lines == null) {
                    throw new ClassifyException(
                            "incomplete: missing Layer B for candidate " + id);
                }
                orderedJobs.add(job);
                orderedJudgments.add(lines);
            }
        }
        return new LlmPhaseResult(
                orderedDispositions, orderedFacts, orderedJobs, orderedJudgments);
    }

    private record MaterializeResult(NomenclatureBinding binding, String rejectReason) {
        static MaterializeResult ok(NomenclatureBinding binding) {
            return new MaterializeResult(binding, null);
        }

        static MaterializeResult reject(String reason) {
            return new MaterializeResult(null, reason);
        }
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
            return MaterializeResult.ok(materializeBinding(
                    catalog, mandateId, slice, packet, candidate, parseRunId, line));
        } catch (ClassifyException e) {
            return MaterializeResult.reject(e.getMessage());
        }
    }

    private static NomenclatureBinding materializeBinding(
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
            if (LayerBAmountSupport.isFormulaNumeric(cell)) {
                throw new ClassifyException(
                        "formula amount at " + line.coord()
                                + " requires helper|total role, got " + role);
            }
            throw new ClassifyException(
                    "Layer B binding requires an amount cell at " + line.coord());
        }
        String path = line.path().trim();
        boolean viaAlias = slice.aliases().stream()
                .anyMatch(alias -> OntologySlice.normalize(alias.aliasText())
                        .equals(OntologySlice.normalize(line.verbatim()))
                        && alias.leafPath().equals(path));
        boolean softLeaf = false;
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
            NomenclatureNode parent = slice.node(parentPath).orElse(null);
            if (parent == null || parent.leaf()) {
                throw new ClassifyException(
                        "cannot invent mid-level '" + parentPath
                                + "'; soft leaves attach under known mid-levels");
            }
            try {
                catalog.putSoftLeaf(mandateId, parentPath, leafName, line.aliases());
                softLeaf = true;
            } catch (NomenclatureException e) {
                // Parallel Layer B may have already created this soft leaf serially earlier.
                OntologySlice refreshed = catalog.sliceForMandate(mandateId);
                Optional<NomenclatureNode> raced = refreshed.node(path);
                if (raced.isPresent() && raced.get().leaf()) {
                    softLeaf = NomenclatureNode.LAYER_MANDATE_SOFT.equals(raced.get().layer());
                } else {
                    throw new ClassifyException(e.getMessage(), e);
                }
            }
        }
        return new NomenclatureBinding(
                cell.cellId(),
                parseRunId,
                candidate.candidateId(),
                line.verbatim(),
                path,
                role,
                softLeaf,
                viaAlias,
                line.confidence());
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

    private static LayerAJudgment requireJudgment(LayerAJudgment judgment, long candidateId)
            throws ClassifyException {
        if (judgment == null
                || judgment.scheduleFamily() == null || judgment.scheduleFamily().isBlank()
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
