package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.Progress;
import com.resurgent.tev.parser.db.BindCellRow;
import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CandidateWrite;
import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import com.resurgent.tev.parser.nomenclature.ProjectFactField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Classify: (1) LLM proposes main/helper/scratch regions and replaces narrow
 * Candidates, (2) Layer A + about on main/helper only.
 */
public final class ClassifyService {

    private final ClassifierLlm llm;
    private final DiscoverService discover;
    private final ClassifyLimits limits;

    public ClassifyService(ClassifierLlm llm) {
        this(llm, new DiscoverService(), ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover) {
        this(llm, discover, ClassifyLimits.defaults());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover, ClassifyLimits limits) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.discover = Objects.requireNonNull(discover, "discover");
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
            List<CandidateRow> existing = repo.selectCandidatesForParseRun(parseRunId);
            if (existing.isEmpty()) {
                throw new ClassifyException(
                        "no Candidates for parse run " + parseRunId + "; run discover first");
            }

            Progress.phase("classify", "LLM region layout");
            materializeLlmRegions(repo, parseRunId);

            List<CandidateRow> all = repo.selectCandidatesForParseRun(parseRunId);
            Map<Long, String> sheetNames = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
                sheetNames.put(sheet.worksheetId(), sheet.sheetName());
            }

            List<CandidateRow> eligible = new ArrayList<>();
            int skipped = 0;
            for (CandidateRow candidate : all) {
                if (isEligible(candidate)) {
                    eligible.add(candidate);
                } else {
                    skipped++;
                }
            }

            ScheduleFamilyCatalog families = ScheduleFamilyCatalog.seeded();
            families.restore(repo.selectScheduleFamilies());

            DiscoverService.PacketSession packetSession = discover.packetSession(repo);
            Progress.phase("classify", "building packets for " + eligible.size() + " candidates");
            List<PreparedPacket> prepared = new ArrayList<>();
            int progress = 0;
            for (CandidateRow candidate : eligible) {
                Progress.step("classify", "packets", ++progress, eligible.size(), 25);
                Packet packet = packetSession.build(candidate.candidateId());
                Packet redacted = PacketRedactor.redact(packet, false);
                prepared.add(new PreparedPacket(
                        candidate,
                        redacted,
                        sheetNames.getOrDefault(candidate.worksheetId(), "")));
            }

            long deadlineNanos = System.nanoTime() + limits.classifyDeadline().toNanos();
            List<PacketDisposition> dispositions =
                    runLayerA(prepared, families, deadlineNanos);

            db.connection().setAutoCommit(false);
            try {
                List<CandidateRow> current = repo.selectCandidatesForParseRun(parseRunId);
                if (!sameCandidateIds(all, current)) {
                    throw new ClassifyException(
                            "Candidates changed during classify for parse run " + parseRunId
                                    + "; re-run discover then classify");
                }
                repo.deletePacketDispositionsForParseRun(parseRunId);
                for (String admitted : families.admitted()) {
                    repo.insertScheduleFamily(admitted);
                }
                for (PacketDisposition disposition : dispositions) {
                    repo.insertPacketDisposition(disposition);
                }
                new CellReadingWriter().replace(repo, parseRunId);
                db.connection().commit();
            } catch (Exception e) {
                db.connection().rollback();
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }

            return new ClassifySummary(
                    parseRunId, dispositions.size(), skipped, eligible.size());
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("classify failed: " + msg, e);
        }
    }

    /**
     * Replace narrow Candidates with LLM-proposed regions. Empty proposal list
     * leaves discover geometry unchanged (test fakes).
     */
    void materializeLlmRegions(WorkspaceRepository repo, long parseRunId)
            throws SQLException, ClassifyException {
        List<WorksheetRef> sheets = repo.selectWorksheetsForParseRun(parseRunId);
        Map<Long, CandidateRow> coverageBySheet = new HashMap<>();
        for (CandidateRow c : repo.selectCandidatesForParseRun(parseRunId)) {
            if ("coverage_parent".equals(c.candidateKind())) {
                coverageBySheet.put(c.worksheetId(), c);
            }
        }
        boolean anySheetProposals = false;
        Map<Long, List<RegionProposal>> bySheet = new HashMap<>();
        for (WorksheetRef sheet : sheets) {
            String dump = cellDump(repo.selectCellPacketViewsForWorksheet(sheet.worksheetId()));
            List<RegionProposal> proposals =
                    llm.proposeRegions(new RegionLayoutPrompt(sheet.sheetName(), dump));
            if (!proposals.isEmpty()) {
                anySheetProposals = true;
            }
            bySheet.put(sheet.worksheetId(), proposals);
        }
        if (!anySheetProposals) {
            return;
        }

        repo.deletePacketDispositionsForParseRun(parseRunId);
        repo.deleteNarrowCandidatesForParseRun(parseRunId);
        for (WorksheetRef sheet : sheets) {
            CandidateRow coverage = coverageBySheet.get(sheet.worksheetId());
            if (coverage == null) {
                throw new ClassifyException(
                        "missing coverage parent for worksheet " + sheet.sheetName());
            }
            List<RegionProposal> proposals = bySheet.getOrDefault(sheet.worksheetId(), List.of());
            for (RegionProposal proposal : proposals) {
                A1Bbox.Bounds bounds;
                try {
                    bounds = A1Bbox.parse(proposal.bbox());
                } catch (IllegalArgumentException e) {
                    throw new ClassifyException(
                            "invalid region bbox '" + proposal.bbox() + "': " + e.getMessage(), e);
                }
                List<Long> members = repo.selectCellIdsInBbox(
                        sheet.worksheetId(),
                        bounds.minRow(),
                        bounds.minCol(),
                        bounds.maxRow(),
                        bounds.maxCol());
                if (members.isEmpty()) {
                    throw new ClassifyException(
                            "region " + proposal.bbox() + " on " + sheet.sheetName()
                                    + " matched no cells");
                }
                String label = proposal.label() != null ? proposal.label() : proposal.bbox();
                String why = proposal.why() != null ? proposal.why() : "";
                CandidateWrite write = new CandidateWrite(
                        parseRunId,
                        sheet.worksheetId(),
                        "child",
                        coverage.candidateId(),
                        bounds.minRow(),
                        bounds.minCol(),
                        bounds.maxRow(),
                        bounds.maxCol(),
                        null,
                        null,
                        null,
                        false,
                        0.9,
                        "llm region layout",
                        "LLM region (" + proposal.structuralRole() + ") " + label
                                + (why.isBlank() ? "" : ": " + why),
                        proposal.structuralRole());
                repo.insertCandidate(write, members);
            }
        }
    }

    private static String cellDump(List<com.resurgent.tev.parser.db.CellPacketView> cells) {
        StringBuilder sb = new StringBuilder();
        for (var cell : cells) {
            String val;
            if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                String ft = cell.formulaText();
                val = ft.startsWith("=") ? ft : "=" + ft;
            } else if (cell.textValue() != null && !cell.textValue().isBlank()) {
                val = cell.textValue().replace('\n', ' ');
                if (val.length() > 80) {
                    val = val.substring(0, 80);
                }
            } else if (cell.numericValue() != null && !cell.numericValue().isBlank()) {
                val = cell.numericValue();
            } else if (cell.displayValue() != null && !cell.displayValue().isBlank()) {
                val = cell.displayValue();
            } else {
                val = "<" + (cell.valueType() != null ? cell.valueType() : "empty") + ">";
            }
            sb.append(cell.coord()).append('\t').append(val).append('\n');
        }
        return sb.toString();
    }

    private List<PacketDisposition> runLayerA(
            List<PreparedPacket> prepared,
            ScheduleFamilyCatalog families,
            long deadlineNanos)
            throws ClassifyException {
        if (prepared.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(limits.parallelism(), prepared.size()));
        try {
            List<Future<PacketDisposition>> futures = new ArrayList<>();
            for (PreparedPacket item : prepared) {
                futures.add(pool.submit(() -> classifyOne(item, families, deadlineNanos)));
            }
            List<PacketDisposition> dispositions = new ArrayList<>(prepared.size());
            int done = 0;
            for (Future<PacketDisposition> future : futures) {
                Progress.step("classify", "layer-a", ++done, futures.size(), 10);
                long remainingMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                try {
                    dispositions.add(future.get(remainingMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new ClassifyException(
                            "classify deadline exceeded after " + done + " of " + futures.size()
                                    + " Layer A calls",
                            e);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    throw new ClassifyException("Layer A failed: " + msg, cause);
                }
            }
            return dispositions;
        } finally {
            pool.shutdownNow();
        }
    }

    private PacketDisposition classifyOne(
            PreparedPacket item, ScheduleFamilyCatalog families, long deadlineNanos)
            throws Exception {
        if (System.nanoTime() > deadlineNanos) {
            throw new ClassifyException("classify deadline exceeded");
        }
        List<String> offered;
        synchronized (families) {
            offered = families.names();
        }
        LayerAPrompt prompt = new LayerAPrompt(
                item.redacted(),
                item.candidate().structuralRole(),
                item.sheetName(),
                offered);
        Callable<LayerAJudgment> call = () -> llm.classifyLayerA(prompt);
        LayerAJudgment judgment;
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<LayerAJudgment> future = one.submit(call);
            judgment = future.get(limits.attemptDeadline().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AttemptDeadlineException(
                    "Layer A attempt deadline exceeded for candidate "
                            + item.candidate().candidateId());
        } finally {
            one.shutdownNow();
        }
        synchronized (families) {
            if (!ScheduleFamily.isKnown(judgment.scheduleFamily())) {
                families.admit(judgment.scheduleFamily());
            }
        }
        return new PacketDisposition(
                item.candidate().candidateId(),
                item.candidate().parseRunId(),
                judgment.scheduleFamily(),
                judgment.triage(),
                judgment.relevance(),
                judgment.rowLabels(),
                judgment.columnHeaders(),
                judgment.packetDefaultHead(),
                judgment.about(),
                item.candidate().parentCandidateId(),
                false);
    }

    /**
     * Layer B for the named sheets only. Uses the Layer A about already stored
     * on each main/helper Candidate. Does not redo region layout. Unbound cells
     * are a successful result — the living ontology grows only when a new leaf
     * fits under a known root.
     */
    public BindSummary bindSheets(Path dbPath, long parseRunId, List<String> sheetNames)
            throws ClassifyException {
        Objects.requireNonNull(dbPath, "dbPath");
        if (sheetNames == null || sheetNames.isEmpty()) {
            throw new ClassifyException("bind requires at least one sheet");
        }
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
            Map<String, Long> wanted = new HashMap<>();
            for (String name : sheetNames) {
                if (name != null && !name.isBlank()) {
                    wanted.put(name, null);
                }
            }
            Map<Long, String> sheetById = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
                if (wanted.containsKey(sheet.sheetName())) {
                    wanted.put(sheet.sheetName(), sheet.worksheetId());
                    sheetById.put(sheet.worksheetId(), sheet.sheetName());
                }
            }
            List<String> missingSheets = new ArrayList<>();
            for (Map.Entry<String, Long> entry : wanted.entrySet()) {
                if (entry.getValue() == null) {
                    missingSheets.add(entry.getKey());
                }
            }
            if (!missingSheets.isEmpty()) {
                throw new ClassifyException("sheet not in parse run: " + String.join(", ", missingSheets));
            }
            Map<Long, PacketDisposition> aboutByCandidate = new HashMap<>();
            for (PacketDisposition disposition : repo.selectPacketDispositionsForParseRun(parseRunId)) {
                aboutByCandidate.put(disposition.candidateId(), disposition);
            }
            LivingOntology living = LivingOntology.from(slice);
            int bound = 0;
            int skipped = 0;
            List<CandidateRow> boundCandidates = new ArrayList<>();
            Map<Long, List<LayerBBinder.Draft>> draftsByCandidate = new LinkedHashMap<>();
            Map<Long, List<BindCellRow>> cellsByCandidate = new LinkedHashMap<>();
            db.connection().setAutoCommit(false);
            try {
                new CellReadingWriter().replace(repo, parseRunId);
                for (CandidateRow candidate : repo.selectCandidatesForParseRun(parseRunId)) {
                    if (!sheetById.containsKey(candidate.worksheetId())) {
                        continue;
                    }
                    if (!isBindEligible(candidate, aboutByCandidate.get(candidate.candidateId()))) {
                        if ("scratch".equals(candidate.structuralRole())
                                || isSoftTriage(aboutByCandidate.get(candidate.candidateId()))) {
                            skipped += repo.selectBindCells(candidate.candidateId()).size();
                            repo.deleteNomenclatureBindings(parseRunId, candidate.candidateId());
                        }
                        continue;
                    }
                    List<BindCellRow> cells = repo.selectBindCells(candidate.candidateId());
                    for (BindCellRow cell : cells) {
                        if (cell.error()) {
                            skipped++;
                        }
                    }
                    PacketDisposition disposition = aboutByCandidate.get(candidate.candidateId());
                    String about = disposition != null ? disposition.about() : "";
                    String family = disposition != null ? disposition.scheduleFamily() : "";
                    String sheetName = sheetById.get(candidate.worksheetId());
                    BindResult result = bindCandidate(
                            cells, sheetName, family, about, living, catalog, mandateId);
                    living = result.living();
                    skipped += result.unbound();
                    repo.deleteNomenclatureBindings(parseRunId, candidate.candidateId());
                    for (LayerBBinder.Draft draft : result.drafts()) {
                        repo.insertNomenclatureBinding(new NomenclatureBinding(
                                parseRunId,
                                candidate.candidateId(),
                                draft.cellId(),
                                draft.coord(),
                                draft.pathRoot(),
                                draft.path(),
                                draft.amountRole(),
                                draft.verbatim()));
                        bound++;
                    }
                    draftsByCandidate.put(candidate.candidateId(), result.drafts());
                    cellsByCandidate.put(candidate.candidateId(), cells);
                    boundCandidates.add(candidate);
                }
                int helpers = applyCrossSheetHelpers(
                        repo, parseRunId, boundCandidates, cellsByCandidate, draftsByCandidate);
                bound += helpers;
                HeaderBindingWriter.write(repo, parseRunId, boundCandidates);
                db.connection().commit();
            } catch (Exception e) {
                db.connection().rollback();
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }
            return new BindSummary(parseRunId, bound, skipped);
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("bind failed: " + msg, e);
        }
    }

    private BindResult bindCandidate(
            List<BindCellRow> cells,
            String sheetName,
            String family,
            String about,
            LivingOntology living,
            NomenclatureCatalog catalog,
            long mandateId)
            throws ClassifyException {
        Map<String, String> aliases = living.aliases();
        List<String> paths = living.paths();
        List<LayerBBinder.Draft> proved = LayerBBinder.bind(cells, List.of(), aliases);
        List<BindCellRow> missing = LayerBBinder.unbound(cells, proved);
        List<LayerBAssignment> assignments = new ArrayList<>();
        if (!missing.isEmpty()) {
            List<LayerBAssignment> first =
                    askLayerB(sheetName, family, about, cells, missing, paths, false);
            living = absorbNewLeaves(living, catalog, mandateId, first);
            aliases = living.aliases();
            paths = living.paths();
            assignments.addAll(first);
            proved = LayerBBinder.bind(cells, assignments, aliases);
            missing = LayerBBinder.unbound(cells, proved);
        }
        if (!missing.isEmpty()) {
            List<LayerBAssignment> second =
                    askLayerB(sheetName, family, about, cells, missing, paths, true);
            living = absorbNewLeaves(living, catalog, mandateId, second);
            aliases = living.aliases();
            assignments.addAll(second);
            proved = LayerBBinder.bind(cells, assignments, aliases);
            missing = LayerBBinder.unbound(cells, proved);
        }
        return new BindResult(proved, missing.size(), living);
    }

    private LivingOntology absorbNewLeaves(
            LivingOntology living,
            NomenclatureCatalog catalog,
            long mandateId,
            List<LayerBAssignment> assignments) {
        LivingOntology next = living;
        for (LayerBAssignment assignment : assignments) {
            if (assignment == null || assignment.path() == null || assignment.pathRoot() == null) {
                continue;
            }
            if (!"economic".equals(assignment.pathRoot()) && !"identity".equals(assignment.pathRoot())) {
                continue;
            }
            String path = assignment.path();
            if (next.contains(path) || !LayerBBinder.allowed(assignment.pathRoot(), path)) {
                continue;
            }
            int sep = path.lastIndexOf(" > ");
            if (sep <= 0) {
                continue;
            }
            String parent = path.substring(0, sep);
            String leaf = path.substring(sep + 3);
            try {
                catalog.putSoftLeaf(mandateId, parent, leaf, List.of(leaf));
                next = next.withLeaf(path, leaf);
            } catch (RuntimeException ignored) {
                // Parent missing or alias collision — keep the assignment for this
                // run when LayerBBinder.allowed already accepted it, without
                // growing the master set.
            }
        }
        return next;
    }

    private int applyCrossSheetHelpers(
            WorkspaceRepository repo,
            long parseRunId,
            List<CandidateRow> candidates,
            Map<Long, List<BindCellRow>> cellsByCandidate,
            Map<Long, List<LayerBBinder.Draft>> draftsByCandidate)
            throws SQLException {
        Map<String, LayerBBinder.Draft> bySheetCoord = new HashMap<>();
        Map<Long, String> sheetByCandidate = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
                if (sheet.worksheetId() == candidate.worksheetId()) {
                    sheetByCandidate.put(candidate.candidateId(), sheet.sheetName());
                }
            }
            for (LayerBBinder.Draft draft : draftsByCandidate.getOrDefault(candidate.candidateId(), List.of())) {
                String sheet = sheetByCandidate.get(candidate.candidateId());
                if (sheet != null) {
                    bySheetCoord.put(sheet + "!" + draft.coord(), draft);
                }
            }
        }
        int added = 0;
        for (CandidateRow candidate : candidates) {
            String sheet = sheetByCandidate.get(candidate.candidateId());
            if (sheet == null) {
                continue;
            }
            Map<String, LayerBBinder.Draft> local = new LinkedHashMap<>();
            for (LayerBBinder.Draft draft :
                    draftsByCandidate.getOrDefault(candidate.candidateId(), List.of())) {
                local.put(draft.coord(), draft);
            }
            for (BindCellRow cell : cellsByCandidate.getOrDefault(candidate.candidateId(), List.of())) {
                if (cell.error() || local.containsKey(cell.coord().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                String formula = cell.formulaText() == null ? "" : cell.formulaText().trim();
                if (formula.startsWith("=")) {
                    formula = formula.substring(1).trim();
                }
                if (!formula.matches("(?i)\\+?(?:'[^']+'|[A-Za-z][A-Za-z0-9_ ]*)!\\$?[A-Z]{1,3}\\$?\\d+")) {
                    continue;
                }
                int bang = formula.indexOf('!');
                String sourceSheet = formula.substring(0, bang).replace("'", "").replace("+", "").trim();
                String sourceCoord = formula.substring(bang + 1).replace("$", "").toUpperCase(Locale.ROOT);
                LayerBBinder.Draft source = bySheetCoord.get(sourceSheet + "!" + sourceCoord);
                if (source == null || !"economic".equals(source.pathRoot())) {
                    continue;
                }
                String verbatim = cell.textValue() != null ? cell.textValue() : cell.formulaText();
                LayerBBinder.Draft helper = new LayerBBinder.Draft(
                        cell.cellId(),
                        cell.coord().toUpperCase(Locale.ROOT),
                        source.pathRoot(),
                        source.path(),
                        "helper",
                        verbatim);
                repo.insertNomenclatureBinding(new NomenclatureBinding(
                        parseRunId,
                        candidate.candidateId(),
                        helper.cellId(),
                        helper.coord(),
                        helper.pathRoot(),
                        helper.path(),
                        helper.amountRole(),
                        helper.verbatim()));
                local.put(helper.coord(), helper);
                bySheetCoord.put(sheet + "!" + helper.coord(), helper);
                added++;
            }
            draftsByCandidate.put(candidate.candidateId(), List.copyOf(local.values()));
        }
        return added;
    }

    private List<LayerBAssignment> askLayerB(
            String sheetName,
            String family,
            String about,
            List<BindCellRow> cells,
            List<BindCellRow> missing,
            List<String> catalog,
            boolean retry)
            throws ClassifyException {
        Set<String> unbound = new HashSet<>();
        for (BindCellRow cell : missing) {
            unbound.add(cell.coord().toUpperCase(Locale.ROOT));
        }
        LayerBPrompt prompt = new LayerBPrompt(
                sheetName,
                family,
                about == null || about.isBlank() ? "No Layer A about stored." : about,
                LayerBPromptAssembler.grid(cells, unbound),
                catalog,
                retry);
        try {
            List<LayerBAssignment> assignments = llm.bindLayerB(prompt);
            return assignments == null ? List.of() : assignments;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("Layer B failed on " + sheetName + ": " + msg, e);
        }
    }

    static boolean isEligible(CandidateRow candidate) {
        String role = candidate.structuralRole();
        return "main".equals(role) || "helper".equals(role);
    }

    /**
     * Layer B bind eligibility. Structural main/helper still qualify for Layer A,
     * but a helper bbox Layer A triaged as scratch or orphan is not bound.
     */
    static boolean isBindEligible(CandidateRow candidate, PacketDisposition disposition) {
        if (!isEligible(candidate)) {
            return false;
        }
        return !isSoftTriage(disposition);
    }

    private static boolean isSoftTriage(PacketDisposition disposition) {
        return disposition != null && Triage.isSoft(disposition.triage());
    }

    private static boolean sameCandidateIds(List<CandidateRow> expected, List<CandidateRow> actual) {
        Set<Long> left = new HashSet<>();
        Set<Long> right = new HashSet<>();
        for (CandidateRow row : expected) {
            left.add(row.candidateId());
        }
        for (CandidateRow row : actual) {
            right.add(row.candidateId());
        }
        return left.equals(right);
    }

    private record PreparedPacket(CandidateRow candidate, Packet redacted, String sheetName) {}

    private record BindResult(List<LayerBBinder.Draft> drafts, int unbound, LivingOntology living) {}

    /** In-memory view of the living master ontology for one bind run. */
    static final class LivingOntology {
        private final Map<String, String> aliases;
        private final List<String> paths;

        private LivingOntology(Map<String, String> aliases, List<String> paths) {
            this.aliases = Map.copyOf(aliases);
            this.paths = List.copyOf(paths);
        }

        static LivingOntology from(OntologySlice slice) {
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
            return new LivingOntology(LayerBBinder.aliasIndex(living), LayerBBinder.allowedPaths(paths));
        }

        Map<String, String> aliases() {
            return aliases;
        }

        List<String> paths() {
            return paths;
        }

        boolean contains(String path) {
            return paths.contains(path);
        }

        LivingOntology withLeaf(String path, String aliasText) {
            Map<String, String> nextAliases = new LinkedHashMap<>(aliases);
            nextAliases.put(LayerBBinder.norm(aliasText), path);
            nextAliases.put(LayerBBinder.norm(path), path);
            List<String> nextPaths = new ArrayList<>(paths);
            if (!nextPaths.contains(path)) {
                nextPaths.add(path);
            }
            return new LivingOntology(nextAliases, nextPaths);
        }
    }
}
