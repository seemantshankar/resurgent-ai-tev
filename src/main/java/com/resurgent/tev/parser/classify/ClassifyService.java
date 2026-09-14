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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Packet classification application service: Layer A disposition and Layer B
 * nomenclature bindings for one parse run. Consumes derived Packets; does not
 * rewrite Candidate geometry. Peers are out of scope for #107.
 */
public final class ClassifyService {

    static final int LAYER_B_PARALLELISM = 8;

    private final ClassifierLlm llm;
    private final DiscoverService discover;

    public ClassifyService(ClassifierLlm llm) {
        this(llm, new DiscoverService());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.discover = Objects.requireNonNull(discover, "discover");
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
            // hold a SQLite write lock. Layer A stays ordered (parent before child);
            // Layer B LLM fans out, then bindings materialize serially.
            List<PacketDisposition> dispositions = new ArrayList<>();
            List<LayerBJob> layerBJobs = new ArrayList<>();
            Map<Long, LayerAJudgment> judged = new HashMap<>();
            Map<Long, LayerAJudgment> coverageByWorksheet = new HashMap<>();
            int coverageParents = 0;
            for (CandidateRow candidate : orderForLayerA(candidates)) {
                boolean cheapPass = "coverage_parent".equals(candidate.candidateKind());
                if (cheapPass) {
                    coverageParents++;
                }
                Packet packet = discover.buildPacket(repo, candidate.candidateId());
                Packet redacted = PacketRedactor.redact(packet, cheapPass);
                LayerAJudgment parent = cheapPass
                        ? null
                        : parentContext(candidate, judged, coverageByWorksheet);
                LayerAJudgment judgment = requireJudgment(
                        llm.classifyLayerA(new LayerAPrompt(redacted, slice, parent, cheapPass)),
                        candidate.candidateId());
                judged.put(candidate.candidateId(), judgment);
                if (cheapPass) {
                    coverageByWorksheet.put(candidate.worksheetId(), judgment);
                }
                dispositions.add(new PacketDisposition(
                        candidate.candidateId(),
                        parseRunId,
                        judgment.scheduleFamily(),
                        judgment.triage(),
                        judgment.relevance(),
                        judgment.rowLabels(),
                        judgment.columnHeaders(),
                        judgment.packetDefaultHead(),
                        candidate.parentCandidateId(),
                        cheapPass));
                if (!cheapPass && LayerBAmountSupport.hasAmountCells(redacted)) {
                    layerBJobs.add(new LayerBJob(
                            candidate,
                            packet,
                            new LayerBPrompt(redacted, slice, judgment, parent)));
                }
            }

            List<NomenclatureBinding> bindings;
            LayerBBindingStats layerBStats = new LayerBBindingStats();
            bindings = materializeLayerB(catalog, mandateId, slice, parseRunId, layerBJobs, layerBStats);

            db.connection().setAutoCommit(false);
            try {
                List<CandidateRow> current = repo.selectCandidatesForParseRun(parseRunId);
                if (!sameCandidateIds(candidates, current)) {
                    throw new ClassifyException(
                            "Candidates changed during classify for parse run " + parseRunId
                                    + "; re-run discover then classify");
                }
                repo.deleteNomenclatureBindingsForParseRun(parseRunId);
                repo.deletePacketDispositionsForParseRun(parseRunId);
                for (PacketDisposition disposition : dispositions) {
                    repo.insertPacketDisposition(disposition);
                }
                for (NomenclatureBinding binding : bindings) {
                    repo.insertNomenclatureBinding(binding);
                }
                repo.commit();
                return new ClassifySummary(
                        parseRunId,
                        dispositions.size(),
                        coverageParents,
                        bindings.size(),
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
     * Layer B LLM calls fan out in parallel against the pre-Layer-B ontology slice.
     * Soft leaves created by earlier packets are <em>not</em> visible to concurrent
     * prompts; reconciliation happens here during serial materialize: reload the
     * slice after each accept, and if {@code putSoftLeaf} races on an already-created
     * path, treat it as an existing leaf.
     */
    private List<NomenclatureBinding> materializeLayerB(
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice,
            long parseRunId,
            List<LayerBJob> jobs,
            LayerBBindingStats stats) throws ClassifyException {
        if (jobs.isEmpty()) {
            return List.of();
        }
        List<List<LayerBLineJudgment>> judgmentsByJob = invokeLayerBParallel(jobs);
        List<NomenclatureBinding> bindings = new ArrayList<>();
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
                stats.addAccepted();
                currentSlice = catalog.sliceForMandate(mandateId);
            }
        }
        return bindings;
    }

    private List<List<LayerBLineJudgment>> invokeLayerBParallel(List<LayerBJob> jobs)
            throws ClassifyException {
        int workers = Math.min(LAYER_B_PARALLELISM, jobs.size());
        if (workers <= 1) {
            List<List<LayerBLineJudgment>> out = new ArrayList<>(jobs.size());
            for (LayerBJob job : jobs) {
                out.add(llm.classifyLayerB(job.prompt()));
            }
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<List<LayerBLineJudgment>>> futures = new ArrayList<>(jobs.size());
            for (LayerBJob job : jobs) {
                futures.add(pool.submit(() -> llm.classifyLayerB(job.prompt())));
            }
            List<List<LayerBLineJudgment>> out = new ArrayList<>(jobs.size());
            for (Future<List<LayerBLineJudgment>> future : futures) {
                try {
                    out.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ClassifyException("Layer B interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new ClassifyException(
                            "Layer B failed: " + cause.getMessage(), cause);
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private record LayerBJob(CandidateRow candidate, Packet packet, LayerBPrompt prompt) {}

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

    private static List<CandidateRow> orderForLayerA(List<CandidateRow> candidates) {
        List<CandidateRow> ordered = new ArrayList<>();
        for (CandidateRow candidate : candidates) {
            if ("coverage_parent".equals(candidate.candidateKind())) {
                ordered.add(candidate);
            }
        }
        for (CandidateRow candidate : candidates) {
            if (!"coverage_parent".equals(candidate.candidateKind())) {
                ordered.add(candidate);
            }
        }
        return ordered;
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
                    judgment.packetDefaultHead());
        }
        return judgment;
    }
}
