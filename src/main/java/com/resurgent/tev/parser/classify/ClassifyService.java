package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.resurgent.tev.parser.discover.PacketCell;
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

    /**
     * Pause before each retry of a region-layout call; its length is the number of retries
     * (so each sheet gets length + 1 attempts). Mutable so tests can retry without waiting.
     */
    long[] regionRetryBackoffMillis = {1_000L, 3_000L};

    private ClassifyTuning tuning = ClassifyTuning.sequential();
    private java.util.Set<String> scopeSheetNames; // lower-case; null means the whole workbook
    /** Sheets whose cells may go to the model for typing: the named sheets and every sheet their formulas read. */
    private java.util.Set<Long> scopeWorksheetIds; // resolved per run; null means every sheet
    /**
     * Sheets that get the structure steps (region layout, Layer A, header geometry, binding): only
     * the sheets that were named. Dependency sheets are typed so formula types can flow, but laying
     * out, describing and binding them is most of a run and is not what was asked for.
     */
    private java.util.Set<Long> structureWorksheetIds; // null means every sheet

    /** Batch sizes and concurrency for the LLM stages. */
    public ClassifyService withTuning(ClassifyTuning tuning) {
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        return this;
    }

    /**
     * Send only these sheets (plus any sheet their formulas read) to the model. Every cell
     * and formula link stays loaded, so formulas still resolve against the whole workbook.
     */
    public ClassifyService withSheetScope(java.util.Collection<String> sheetNames) {
        this.scopeSheetNames = sheetNames == null || sheetNames.isEmpty()
                ? null
                : sheetNames.stream()
                        .map(n -> n.trim().toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return this;
    }

    private static long secondsSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000L;
    }

    /** Resolve and remember the sheet scope for this run. */
    void useScope(WorkspaceRepository repo, long parseRunId) throws SQLException, ClassifyException {
        scopeWorksheetIds = resolveScope(repo, parseRunId);
    }

    private void deleteDispositions(WorkspaceRepository repo, long parseRunId) throws SQLException {
        if (structureWorksheetIds == null) {
            repo.deletePacketDispositionsForParseRun(parseRunId);
        } else {
            repo.deletePacketDispositionsForWorksheets(parseRunId, structureWorksheetIds);
        }
    }

    private CellReadingWriter cellReader() {
        return new CellReadingWriter().withTuning(tuning, scopeWorksheetIds);
    }

    private boolean inScope(long worksheetId) {
        return structureWorksheetIds == null || structureWorksheetIds.contains(worksheetId);
    }

    /**
     * Resolve the scope to worksheet ids and pull in every sheet the scoped sheets' formulas
     * read, transitively: a formula over unparsed sheets cannot be typed from labels alone.
     */
    java.util.Set<Long> resolveScope(WorkspaceRepository repo, long parseRunId)
            throws SQLException, ClassifyException {
        if (scopeSheetNames == null) {
            structureWorksheetIds = null;
            return null;
        }
        List<WorksheetRef> sheets = repo.selectWorksheetsForParseRun(parseRunId);
        Map<String, WorksheetRef> byName = new HashMap<>();
        for (WorksheetRef sheet : sheets) {
            byName.put(sheet.sheetName().trim().toLowerCase(java.util.Locale.ROOT), sheet);
        }
        java.util.Set<Long> scope = new java.util.LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : scopeSheetNames) {
            WorksheetRef sheet = byName.get(name);
            if (sheet == null) {
                missing.add(name);
            } else {
                scope.add(sheet.worksheetId());
            }
        }
        if (!missing.isEmpty()) {
            throw new ClassifyException("sheet not in parse run: " + String.join(", ", missing));
        }
        structureWorksheetIds = new java.util.LinkedHashSet<>(scope); // the named sheets, before dependencies join
        Map<Long, Long> sheetOfCell = new HashMap<>();
        for (var cell : repo.selectInterpretationCellsForParseRun(parseRunId)) {
            sheetOfCell.put(cell.cellId(), cell.worksheetId());
        }
        List<com.resurgent.tev.parser.db.FormulaLink> links = repo.selectFormulaLinksForParseRun(parseRunId);
        java.util.Set<Long> pulledIn = new java.util.LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var link : links) {
                Long from = sheetOfCell.get(link.fromCellId());
                Long to = sheetOfCell.get(link.toCellId());
                if (from != null && to != null && scope.contains(from) && !scope.contains(to)) {
                    scope.add(to);
                    pulledIn.add(to);
                    grew = true;
                }
            }
        }
        Map<Long, String> names = new HashMap<>();
        sheets.forEach(sh -> names.put(sh.worksheetId(), sh.sheetName()));
        System.err.println("[classify] Scope: " + String.join(", ", scopeSheetNames)
                + (pulledIn.isEmpty() ? " (no other sheet is read by their formulas)"
                        : " + " + pulledIn.stream().map(names::get).toList()
                                + " typed too, because their formulas read them (they are not laid out, described or bound)"));
        System.err.flush();
        return scope;
    }

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

            // Check if classify already completed (idempotency)
            List<PacketDisposition> existingDispositions = repo.selectPacketDispositionsForParseRun(parseRunId);
            if (!existingDispositions.isEmpty()) {
                System.err.println("[classify] Resuming from prior run: " + existingDispositions.size() + " dispositions already present");
                System.err.flush();
                // Layer A and region layout already done, skip to Layer B (cell reading)
                return resumeFromLayerB(repo, parseRunId, existingDispositions, db);
            }

            long runStarted = System.nanoTime();
            useScope(repo, parseRunId);
            Progress.phase("classify", "settings: " + tuning.cellBatchSize() + " cells per call, "
                    + tuning.layerABatchSize() + " candidates per call, " + tuning.concurrency()
                    + " calls at a time; scope: "
                    + (scopeSheetNames == null ? "whole workbook" : String.join(", ", scopeSheetNames)));
            Progress.phase("classify", "STAGE 1/3 START - LLM region layout");
            long stageStarted = System.nanoTime();
            LlmStats.GLOBAL.enterStage("region-layout");
            java.time.Instant stageInstant = java.time.Instant.now();
            int sheetsTotal = repo.selectWorksheetsForParseRun(parseRunId).size();
            int candidatesBefore = repo.selectCandidatesForParseRun(parseRunId).size();
            materializeLlmRegions(repo, parseRunId);
            int sheetsInScope = (int) repo.selectWorksheetsForParseRun(parseRunId).stream()
                    .filter(sheet -> inScope(sheet.worksheetId())).count();
            LlmStats.GLOBAL.put("region-layout", "sheets_total", sheetsTotal);
            LlmStats.GLOBAL.put("region-layout", "sheets_sent_to_llm", sheetsInScope);
            LlmStats.GLOBAL.put("region-layout", "candidates_before", candidatesBefore);
            LlmStats.GLOBAL.put("region-layout", "candidates_after",
                    repo.selectCandidatesForParseRun(parseRunId).size());
            LlmStats.GLOBAL.timing("region-layout", stageInstant, java.time.Instant.now(), sheetsInScope);
            Progress.phase("classify", "STAGE 1/3 DONE - region layout took " + secondsSince(stageStarted) + "s");

            List<CandidateRow> all = repo.selectCandidatesForParseRun(parseRunId);
            Map<Long, String> sheetNames = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
                sheetNames.put(sheet.worksheetId(), sheet.sheetName());
            }

            List<CandidateRow> eligible = new ArrayList<>();
            int skipped = 0;
            for (CandidateRow candidate : all) {
                if (isEligible(candidate) && inScope(candidate.worksheetId())) {
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
            Progress.phase("classify", "STAGE 2/3 START - Layer A (what each region is about), "
                    + prepared.size() + " candidates");
            stageStarted = System.nanoTime();
            LlmStats.GLOBAL.enterStage("layer-a");
            stageInstant = java.time.Instant.now();
            List<PacketDisposition> dispositions =
                    runLayerA(prepared, families, deadlineNanos);
            LlmStats.GLOBAL.put("layer-a", "candidates_total", all.size());
            LlmStats.GLOBAL.put("layer-a", "candidates_eligible", eligible.size());
            LlmStats.GLOBAL.put("layer-a", "candidates_skipped", skipped);
            LlmStats.GLOBAL.put("layer-a", "dispositions", dispositions.size());
            LlmStats.GLOBAL.timing("layer-a", stageInstant, java.time.Instant.now(), eligible.size());
            Progress.phase("classify", "STAGE 2/3 DONE - Layer A took " + secondsSince(stageStarted) + "s: "
                    + dispositions.size() + " of " + prepared.size() + " candidates classified");

            db.connection().setAutoCommit(false);
            try {
                List<CandidateRow> current = repo.selectCandidatesForParseRun(parseRunId);
                if (!sameCandidateIds(all, current)) {
                    throw new ClassifyException(
                            "Candidates changed during classify for parse run " + parseRunId
                                    + "; re-run discover then classify");
                }
                deleteDispositions(repo, parseRunId);
                for (String admitted : families.admitted()) {
                    repo.insertScheduleFamily(admitted);
                }
                for (PacketDisposition disposition : dispositions) {
                    repo.insertPacketDisposition(disposition);
                }
                findHeaderGeometry(repo, parseRunId, dispositions);
                Progress.phase("classify", "STAGE 3/3 START - Layer B (typing every numeric cell)");
                stageStarted = System.nanoTime();
                cellReader().replace(repo, parseRunId, llm);
                saveCellLabels(repo, parseRunId);
                autoBind(repo, parseRunId);
                Progress.phase("classify", "STAGE 3/3 DONE - Layer B took " + secondsSince(stageStarted)
                        + "s; saving results");
                db.connection().commit();
            } catch (Exception e) {
                db.connection().rollback();
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }
            Progress.phase("classify", "FINISHED - total " + secondsSince(runStarted) + "s, results saved");

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
     * Ask the LLM, once per region that matters, where its column headers and row labels are, and
     * store the answer; the label resolver builds every cell's labels from it. Runs in the caller's
     * transaction, after Layer A and before any cell is typed.
     */
    private void findHeaderGeometry(WorkspaceRepository repo, long parseRunId, List<PacketDisposition> dispositions)
            throws SQLException {
        Map<Long, CandidateRow> candidates = new HashMap<>();
        for (CandidateRow candidate : repo.selectCandidatesForParseRun(parseRunId)) {
            candidates.put(candidate.candidateId(), candidate);
        }
        Map<Long, String> sheetNames = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheetNames.put(sheet.worksheetId(), sheet.sheetName());
        }
        Map<Long, List<com.resurgent.tev.parser.db.InterpretationCellView>> cellsBySheet = new HashMap<>();
        for (com.resurgent.tev.parser.db.InterpretationCellView cell :
                repo.selectInterpretationCellsForParseRun(parseRunId)) {
            cellsBySheet.computeIfAbsent(cell.worksheetId(), id -> new ArrayList<>()).add(cell);
        }
        List<HeaderGeometryStage.Region> regions = new ArrayList<>();
        for (PacketDisposition disposition : dispositions) {
            CandidateRow candidate = candidates.get(disposition.candidateId());
            if (candidate == null || Triage.isSoft(disposition.triage()) || !inScope(candidate.worksheetId())
                    || candidate.bboxMinRow() == null || candidate.bboxMaxRow() == null
                    || candidate.bboxMinCol() == null || candidate.bboxMaxCol() == null) {
                continue;
            }
            regions.add(new HeaderGeometryStage.Region(
                    candidate,
                    sheetNames.getOrDefault(candidate.worksheetId(), ""),
                    cellsBySheet.getOrDefault(candidate.worksheetId(), List.of())));
        }
        Progress.phase("classify", "finding header rows and label columns in " + regions.size() + " regions");
        long started = System.nanoTime();
        LlmStats.GLOBAL.enterStage("layer-a"); // runs right after Layer A; the stats tables accept no other new stage
        Map<Long, HeaderGeometry> found = HeaderGeometryStage.ask(regions, llm, tuning.concurrency());
        for (HeaderGeometry geometry : found.values()) {
            repo.insertHeaderGeometry(parseRunId, geometry);
        }
        Progress.phase("classify", "header geometry found for " + found.size() + " of " + regions.size()
                + " regions in " + secondsSince(started) + "s");
    }

    /**
     * Replace narrow Candidates with LLM-proposed regions. Empty proposal list
     * leaves discover geometry unchanged (test fakes).
     */
    private ClassifySummary resumeFromLayerB(
            WorkspaceRepository repo, long parseRunId, List<PacketDisposition> dispositions, WorkspaceDatabase db)
            throws SQLException, ClassifyException {
        System.err.println("[classify] Skipping region layout and Layer A (already completed)");
        System.err.flush();

        // Restore schedule families from DB
        ScheduleFamilyCatalog families = ScheduleFamilyCatalog.seeded();
        families.restore(repo.selectScheduleFamilies());

        // Run Layer B (cell reading/classification)
        db.connection().setAutoCommit(false);
        try {
            if (repo.selectHeaderGeometry(parseRunId).isEmpty()) {
                findHeaderGeometry(repo, parseRunId, dispositions);
            }
            System.err.println("[classify] Running Layer B (cell type classification) with batching...");
            System.err.flush();
            cellReader().replace(repo, parseRunId, llm);
            saveCellLabels(repo, parseRunId);
            autoBind(repo, parseRunId);
            db.connection().commit();
        } catch (Exception e) {
            db.connection().rollback();
            throw new ClassifyException("Layer B failed: " + e.getMessage(), e);
        } finally {
            db.connection().setAutoCommit(true);
        }

        return new ClassifySummary(
                parseRunId, dispositions.size(), 0, dispositions.size());
    }

    /**
     * Final classify stage: seed the spine if needed and bind every sheet in scope, so one run
     * leaves cells with nomenclature paths. Runs in the caller's transaction, after Layer B.
     */
    private BindSummary autoBind(WorkspaceRepository repo, long parseRunId) throws SQLException, ClassifyException {
        long started = System.nanoTime();
        Progress.phase("classify", "binding cells to nomenclature");
        long mandateId = repo.selectParseRunMandateId(parseRunId);
        NomenclatureCatalog catalog = new NomenclatureCatalog(repo);
        OntologySlice slice = catalog.sliceForMandate(mandateId);
        Map<Long, String> sheetById = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            // A run scoped to some sheets binds those only; the rest of the workbook costs no model calls.
            if (inScope(sheet.worksheetId())) {
                sheetById.put(sheet.worksheetId(), sheet.sheetName());
            }
        }
        BindSummary summary = bindCandidates(repo, parseRunId, sheetById, catalog, mandateId, slice);
        LlmStats.GLOBAL.put("layer-b", "bind_cells_bound", summary.boundCells());
        LlmStats.GLOBAL.put("layer-b", "bind_cells_unbound", summary.skippedCells());
        Progress.phase("classify", "bound " + summary.boundCells() + " cells, " + summary.skippedCells()
                + " left unbound in " + secondsSince(started) + "s");
        return summary;
    }

    /**
     * Save, for every cell, the row header, column header and period/scale/currency/unit evidence
     * it was read under, so a stored cell can be understood without re-running the resolver.
     * Every candidate is passed, so the coverage parents make this every cell of the parse run.
     * Runs in the caller's transaction.
     */
    private void saveCellLabels(WorkspaceRepository repo, long parseRunId) throws SQLException {
        List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
        Progress.phase("classify", "saving row/column labels for every cell");
        long started = System.nanoTime();
        HeaderBindingWriter.Written written = HeaderBindingWriter.write(repo, parseRunId, candidates);
        LlmStats.GLOBAL.put("layer-b", "label_cells_saved", written.cells());
        LlmStats.GLOBAL.put("layer-b", "label_evidence_rows_saved", written.evidenceRows());
        Progress.phase("classify", "saved " + written.evidenceRows() + " label rows for " + written.cells()
                + " cells in " + secondsSince(started) + "s");
    }

    void materializeLlmRegions(WorkspaceRepository repo, long parseRunId)
            throws SQLException, ClassifyException {
        List<WorksheetRef> sheets = new ArrayList<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            if (inScope(sheet.worksheetId())) {
                sheets.add(sheet);
            }
        }
        Map<Long, CandidateRow> coverageBySheet = new HashMap<>();
        for (CandidateRow c : repo.selectCandidatesForParseRun(parseRunId)) {
            if ("coverage_parent".equals(c.candidateKind())) {
                coverageBySheet.put(c.worksheetId(), c);
            }
        }

        // Load all cell dumps upfront for batching
        Map<Long, List<com.resurgent.tev.parser.db.CellPacketView>> cellsBySheet =
                new HashMap<>();
        for (WorksheetRef sheet : sheets) {
            List<com.resurgent.tev.parser.db.CellPacketView> cellViews =
                    repo.selectCellPacketViewsForWorksheet(sheet.worksheetId());
            cellsBySheet.put(sheet.worksheetId(), cellViews);
        }

        boolean anySheetProposals = false;
        int failedSheets = 0;
        Map<Long, List<RegionProposal>> bySheet = new HashMap<>();

        // Sheets go to the model 12 at a time; a few of those batch calls run at once.
        int batchSize = 12;
        List<List<WorksheetRef>> sheetBatches = new ArrayList<>();
        for (int start = 0; start < sheets.size(); start += batchSize) {
            sheetBatches.add(sheets.subList(start, Math.min(start + batchSize, sheets.size())));
        }
        for (int waveStart = 0; waveStart < sheetBatches.size(); waveStart += tuning.concurrency()) {
            List<List<WorksheetRef>> wave = sheetBatches.subList(
                    waveStart, Math.min(waveStart + tuning.concurrency(), sheetBatches.size()));
            List<List<RegionLayoutPrompt>> promptsByBatch = new ArrayList<>();
            List<java.util.concurrent.Callable<Map<String, List<RegionProposal>>>> tasks = new ArrayList<>();
            for (List<WorksheetRef> batchSheets : wave) {
                List<RegionLayoutPrompt> batchPrompts = new ArrayList<>();
                for (WorksheetRef sheet : batchSheets) {
                    batchPrompts.add(new RegionLayoutPrompt(
                            sheet.sheetName(), cellDump(cellsBySheet.get(sheet.worksheetId()))));
                }
                promptsByBatch.add(batchPrompts);
                tasks.add(() -> llm.proposeRegionsBatch(batchPrompts));
            }
            System.err.println("[region-layout] Sending " + wave.size() + " batch call(s) covering "
                    + wave.stream().mapToInt(List::size).sum() + " sheets");
            System.err.flush();
            long llmStart = System.nanoTime();
            List<ParallelCalls.Outcome<Map<String, List<RegionProposal>>>> outcomes =
                    ParallelCalls.run(tasks, tuning.concurrency());
            long llmMs = (System.nanoTime() - llmStart) / 1_000_000;

            for (int i = 0; i < wave.size(); i++) {
                List<WorksheetRef> batchSheets = wave.get(i);
                Map<String, Long> sheetNameToId = new LinkedHashMap<>();
                for (WorksheetRef sheet : batchSheets) {
                    sheetNameToId.put(sheet.sheetName(), sheet.worksheetId());
                }
                var outcome = outcomes.get(i);
                if (!outcome.ok()) {
                    System.err.println("[region-layout] Batch LLM call FAILED after " + llmMs + "ms: "
                            + outcome.error().getClass().getSimpleName() + ": " + outcome.error().getMessage());
                    System.err.println("[region-layout] Falling back to individual calls for batch");
                    System.err.flush();
                    for (WorksheetRef sheet : batchSheets) {
                        if (!callRegionLayoutIndividual(sheet, cellsBySheet, bySheet)) {
                            failedSheets++;
                        }
                        anySheetProposals = true;
                    }
                    continue;
                }
                Map<String, List<RegionProposal>> batchResults = outcome.value();
                System.err.println("[region-layout] Batch LLM returned in " + llmMs + "ms");
                System.err.flush();
                if (batchResults.isEmpty()) {
                    // Batch not supported, fall back to individual calls
                    System.err.println("[region-layout] Batch not supported, falling back to individual calls");
                    System.err.flush();
                    for (WorksheetRef sheet : batchSheets) {
                        if (!callRegionLayoutIndividual(sheet, cellsBySheet, bySheet)) {
                            failedSheets++;
                        }
                        anySheetProposals = true;
                    }
                } else {
                    for (Map.Entry<String, List<RegionProposal>> entry : batchResults.entrySet()) {
                        Long worksheetId = sheetNameToId.get(entry.getKey());
                        if (worksheetId != null) {
                            if (!entry.getValue().isEmpty()) {
                                anySheetProposals = true;
                            }
                            bySheet.put(worksheetId, entry.getValue());
                        }
                    }
                    // For sheets not in batch results, fall back to individual
                    for (WorksheetRef sheet : batchSheets) {
                        if (!bySheet.containsKey(sheet.worksheetId())
                                && !callRegionLayoutIndividual(sheet, cellsBySheet, bySheet)) {
                            failedSheets++;
                        }
                    }
                }
            }
        }
        if (failedSheets > 0) {
            System.err.println("[region-layout] " + failedSheets + " of " + sheets.size()
                    + " sheets could not be laid out by the model; they keep their structural regions only");
            System.err.flush();
        }
        if (!anySheetProposals) {
            return;
        }

        // Check every proposal now (retrying only the sheet that needs it) so a bad answer
        // costs one sheet a retry, and nothing is written until all sheets are settled.
        Map<Long, List<ValidRegion>> validBySheet = new HashMap<>();
        for (WorksheetRef sheet : sheets) {
            validBySheet.put(sheet.worksheetId(), resolveRegions(
                    repo, sheet, bySheet.getOrDefault(sheet.worksheetId(), List.of()), cellsBySheet));
        }

        deleteDispositions(repo, parseRunId);
        if (structureWorksheetIds == null) {
            repo.deleteNarrowCandidatesForParseRun(parseRunId);
        } else {
            repo.deleteNarrowCandidatesForWorksheets(parseRunId, structureWorksheetIds);
        }
        for (WorksheetRef sheet : sheets) {
            CandidateRow coverage = coverageBySheet.get(sheet.worksheetId());
            if (coverage == null) {
                throw new ClassifyException(
                        "missing coverage parent for worksheet " + sheet.sheetName());
            }
            List<ValidRegion> regions = withFringe(
                    validBySheet.getOrDefault(sheet.worksheetId(), List.of()),
                    cellsBySheet.getOrDefault(sheet.worksheetId(), List.of()));
            for (ValidRegion region : regions) {
                RegionProposal proposal = region.proposal();
                A1Bbox.Bounds bounds = region.bounds();
                List<Long> members = region.members();
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
            insertResidualRegions(repo, parseRunId, sheet, coverage, regions, cellsBySheet);
        }
    }

    /**
     * Each region grows to take in the units, remarks and notes hugging its edge that the model's
     * box left out (see {@link ResidualRegions#absorbFringe}).
     */
    private static List<ValidRegion> withFringe(
            List<ValidRegion> regions, List<com.resurgent.tev.parser.db.CellPacketView> sheetCells) {
        if (regions.isEmpty()) {
            return regions;
        }
        List<ResidualRegions.Extent> extents = new ArrayList<>();
        for (ValidRegion region : regions) {
            A1Bbox.Bounds b = region.bounds();
            extents.add(new ResidualRegions.Extent(
                    b.minRow(), b.minCol(), b.maxRow(), b.maxCol(), new HashSet<>(region.members())));
        }
        List<ResidualRegions.Extent> grown = ResidualRegions.absorbFringe(sheetCells, extents);
        List<ValidRegion> out = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            ResidualRegions.Extent e = grown.get(i);
            ValidRegion region = regions.get(i);
            out.add(e.memberIds().size() == region.members().size()
                    ? region
                    : new ValidRegion(
                            region.proposal(),
                            new A1Bbox.Bounds(e.minRow(), e.minCol(), e.maxRow(), e.maxCol()),
                            new ArrayList<>(e.memberIds())));
        }
        return out;
    }

    /**
     * Numbers the model's regions skipped become regions of their own, so they get a description
     * and header geometry like everything else instead of being read with no context at all.
     */
    private void insertResidualRegions(
            WorkspaceRepository repo,
            long parseRunId,
            WorksheetRef sheet,
            CandidateRow coverage,
            List<ValidRegion> regions,
            Map<Long, List<com.resurgent.tev.parser.db.CellPacketView>> cellsBySheet)
            throws SQLException {
        if (regions.isEmpty()) {
            return; // the model did not lay this sheet out; it keeps its structural region
        }
        Set<Long> owned = new HashSet<>();
        for (ValidRegion region : regions) {
            owned.addAll(region.members());
        }
        List<ResidualRegions.Block> blocks =
                ResidualRegions.find(cellsBySheet.getOrDefault(sheet.worksheetId(), List.of()), owned);
        for (ResidualRegions.Block block : blocks) {
            repo.insertCandidate(
                    new CandidateWrite(
                            parseRunId,
                            sheet.worksheetId(),
                            "child",
                            coverage.candidateId(),
                            block.minRow(),
                            block.minCol(),
                            block.maxRow(),
                            block.maxCol(),
                            null,
                            null,
                            null,
                            false,
                            0.5,
                            "residual cells no model region claimed",
                            "Residual region: cells the layout left unclaimed",
                            "helper"),
                    block.memberIds());
        }
        if (!blocks.isEmpty()) {
            LlmStats.GLOBAL.add("region-layout", "residual_regions", blocks.size());
        }
    }

    /**
     * One sheet's region layout, retried on transient errors. Returns whether the model
     * answered; on final failure the sheet is left with no proposals (its structural
     * coverage region still stands) instead of aborting the whole run.
     */
    private boolean callRegionLayoutIndividual(
            WorksheetRef sheet,
            Map<Long, List<com.resurgent.tev.parser.db.CellPacketView>> cellsBySheet,
            Map<Long, List<RegionProposal>> bySheet) {
        String dump = cellDump(cellsBySheet.get(sheet.worksheetId()));
        int attempts = regionRetryBackoffMillis.length + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                System.err.println("[region-layout] Calling LLM individually for sheet: "
                        + sheet.sheetName() + (attempt > 1 ? " (attempt " + attempt + "/" + attempts + ")" : ""));
                System.err.flush();
                long llmStart = System.nanoTime();
                List<RegionProposal> proposals =
                        llm.proposeRegions(new RegionLayoutPrompt(sheet.sheetName(), dump));
                long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
                System.err.println("[region-layout] Sheet " + sheet.sheetName() + ": LLM returned "
                        + proposals.size() + " regions in " + llmMs + "ms");
                System.err.flush();
                bySheet.put(sheet.worksheetId(), proposals);
                return true;
            } catch (Exception e) {
                System.err.println("[region-layout] Call for sheet " + sheet.sheetName() + " FAILED (attempt "
                        + attempt + "/" + attempts + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.err.flush();
                if (attempt < attempts) {
                    pauseBeforeRetry(attempt - 1);
                }
            }
        }
        bySheet.put(sheet.worksheetId(), List.of());
        return false;
    }

    private void pauseBeforeRetry(int retryIndex) {
        long millis = regionRetryBackoffMillis[Math.min(retryIndex, regionRetryBackoffMillis.length - 1)];
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ValidRegion(RegionProposal proposal, A1Bbox.Bounds bounds, List<Long> members) {}

    private record CheckedRegions(List<ValidRegion> valid, List<String> problems) {}

    /** Split a sheet's proposals into usable regions and human-readable problems. */
    private CheckedRegions checkRegions(
            WorkspaceRepository repo, WorksheetRef sheet, List<RegionProposal> proposals) throws SQLException {
        List<ValidRegion> valid = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (RegionProposal proposal : proposals) {
            A1Bbox.Bounds bounds;
            try {
                bounds = A1Bbox.parse(proposal.bbox());
            } catch (IllegalArgumentException e) {
                problems.add("'" + proposal.bbox() + "' is not a valid A1 range");
                continue;
            }
            List<Long> members = repo.selectCellIdsInBbox(
                    sheet.worksheetId(), bounds.minRow(), bounds.minCol(), bounds.maxRow(), bounds.maxCol());
            if (members.isEmpty()) {
                problems.add(proposal.bbox() + " contains no cells");
                continue;
            }
            valid.add(new ValidRegion(proposal, bounds, members));
        }
        return new CheckedRegions(valid, problems);
    }

    /**
     * Usable regions for one sheet. Unusable proposals (bad range, no cells) trigger a retry
     * of just this sheet, telling the model what was wrong; if they persist they are dropped
     * with a warning while the valid regions are kept.
     */
    private List<ValidRegion> resolveRegions(
            WorkspaceRepository repo,
            WorksheetRef sheet,
            List<RegionProposal> proposals,
            Map<Long, List<com.resurgent.tev.parser.db.CellPacketView>> cellsBySheet)
            throws SQLException {
        CheckedRegions best = checkRegions(repo, sheet, proposals);
        String dump = cellDump(cellsBySheet.get(sheet.worksheetId()));
        int retries = regionRetryBackoffMillis.length;
        for (int retry = 0; retry < retries && !best.problems().isEmpty(); retry++) {
            System.err.println("[region-layout] Sheet " + sheet.sheetName() + ": unusable regions "
                    + best.problems() + "; retrying (" + (retry + 1) + "/" + retries + ")");
            System.err.flush();
            pauseBeforeRetry(retry);
            String note = "\n\nNOTE: your previous answer for this sheet contained regions that cannot be used: "
                    + String.join("; ", best.problems())
                    + ". Every region must be a valid A1 range that contains at least one non-empty cell of"
                    + " this sheet. Return the complete corrected list of regions.\n";
            try {
                CheckedRegions again = checkRegions(repo, sheet, llm.proposeRegions(
                        new RegionLayoutPrompt(sheet.sheetName(), dump + note)));
                if (again.problems().size() < best.problems().size()
                        || (again.problems().size() == best.problems().size()
                                && again.valid().size() > best.valid().size())) {
                    best = again;
                }
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("[region-layout] Retry for sheet " + sheet.sheetName() + " FAILED: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.err.flush();
            }
        }
        if (!best.problems().isEmpty()) {
            System.err.println("[region-layout] WARNING sheet " + sheet.sheetName() + ": dropping unusable regions "
                    + best.problems() + " after " + retries + " retries; keeping " + best.valid().size()
                    + " valid region(s)");
            System.err.flush();
        }
        return best.valid();
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

        System.err.println("[classify] Layer A (packet disposition) - batching " + prepared.size() + " candidates");
        System.err.flush();
        List<PacketDisposition> dispositions = new ArrayList<>(prepared.size());
        int skippedCandidates = 0;

        // Candidates go to the model in batches; a few batch calls run at once.
        int size = tuning.layerABatchSize();
        List<List<PreparedPacket>> batches = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i += size) {
            batches.add(prepared.subList(i, Math.min(i + size, prepared.size())));
        }
        for (int waveStart = 0; waveStart < batches.size(); waveStart += tuning.concurrency()) {
            List<List<PreparedPacket>> wave =
                    batches.subList(waveStart, Math.min(waveStart + tuning.concurrency(), batches.size()));
            List<java.util.concurrent.Callable<List<LayerAJudgment>>> tasks = new ArrayList<>();
            for (List<PreparedPacket> batch : wave) {
                tasks.add(() -> classifyBatchLayerA(batch, families, deadlineNanos));
            }
            List<ParallelCalls.Outcome<List<LayerAJudgment>>> outcomes =
                    ParallelCalls.run(tasks, tuning.concurrency());
            for (int w = 0; w < wave.size(); w++) {
                List<PreparedPacket> batch = wave.get(w);
                var outcome = outcomes.get(w);
                String failure = null;
                if (!outcome.ok()) {
                    Throwable cause = outcome.error().getCause() != null
                            ? outcome.error().getCause() : outcome.error();
                    failure = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                } else if (outcome.value().size() != batch.size()) {
                    failure = "expected " + batch.size() + " judgments, got " + outcome.value().size();
                }
                if (failure == null) {
                    List<PreparedPacket> unanswered = new ArrayList<>();
                    for (int j = 0; j < batch.size(); j++) {
                        LayerAJudgment judgment = outcome.value().get(j);
                        if (judgment == null) {
                            unanswered.add(batch.get(j));
                        } else {
                            dispositions.add(dispositionFor(batch.get(j), judgment, families));
                        }
                    }
                    if (!unanswered.isEmpty()) {
                        System.err.println("[layer-a] Batch answered " + (batch.size() - unanswered.size())
                                + " of " + batch.size() + " candidates; asking again only for the "
                                + unanswered.size() + " missing");
                        System.err.flush();
                        skippedCandidates += redoOneByOne(unanswered, families, deadlineNanos, dispositions);
                    }
                } else {
                    // The batch could not be used. Redo only these candidates one at a time (each
                    // already tries every configured model); a candidate that still fails is
                    // skipped so the rest of the run is never lost.
                    System.err.println("[layer-a] Batch failed (" + failure + "); retrying its "
                            + batch.size() + " candidates one at a time");
                    System.err.flush();
                    skippedCandidates += redoOneByOne(batch, families, deadlineNanos, dispositions);
                }
                Progress.step("classify", "layer-a", dispositions.size() + skippedCandidates, prepared.size(), 10);
            }
        }
        if (skippedCandidates > 0) {
            System.err.println("[layer-a] " + skippedCandidates + " of " + prepared.size()
                    + " candidates were skipped (no model could classify them); the rest completed");
            System.err.flush();
        }

        return dispositions;
    }

    /** Classify candidates one at a time; returns how many no model could classify (skipped). */
    private int redoOneByOne(
            List<PreparedPacket> items,
            ScheduleFamilyCatalog families,
            long deadlineNanos,
            List<PacketDisposition> into) {
        int skipped = 0;
        for (PreparedPacket item : items) {
            try {
                into.add(classifyOne(item, families, deadlineNanos));
            } catch (Exception ex) {
                skipped++;
                System.err.println("[layer-a] Skipping candidate " + item.candidate().candidateId()
                        + " on " + item.sheetName() + ": " + ex.getMessage());
                System.err.flush();
            }
        }
        return skipped;
    }

    private PacketDisposition dispositionFor(
            PreparedPacket item, LayerAJudgment judgment, ScheduleFamilyCatalog families) {
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
                false,
                judgment.statedScale(),
                judgment.scaleEvidenceCell());
    }

    private List<LayerAJudgment> classifyBatchLayerA(
            List<PreparedPacket> batch,
            ScheduleFamilyCatalog families,
            long deadlineNanos)
            throws Exception {
        if (System.nanoTime() > deadlineNanos) {
            throw new ClassifyException("classify deadline exceeded");
        }

        System.err.println("[layer-a-batch] Batch of " + batch.size() + " candidates, calling LLM...");
        System.err.flush();

        List<String> offered;
        synchronized (families) {
            offered = families.names();
        }

        String userMessage = formatBatchLayerAPrompt(batch, offered);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyLayerAJson(userMessage, 4096);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        System.err.println("[layer-a-batch] LLM responded in " + llmMs + "ms");
        System.err.flush();

        List<LayerAJudgment> judgments = parseBatchLayerAResponse(jsonResponse, batch.size());
        System.err.println("[layer-a-batch] Parsed " + judgments.size() + " judgments from batch response (expected " + batch.size() + ")");
        if (judgments.size() < batch.size()) {
            throw new IllegalArgumentException("Expected at least " + batch.size() + " judgments, got " + judgments.size());
        }
        System.err.flush();
        // Use only the first batch.size() judgments
        return judgments.subList(0, batch.size());
    }

    private String formatBatchLayerAPrompt(List<PreparedPacket> batch, List<String> scheduleFamilies) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("scheduleFamilies", String.join(", ", scheduleFamilies));
            ArrayNode candidates = root.putArray("candidates");

            for (int i = 0; i < batch.size(); i++) {
                PreparedPacket item = batch.get(i);
                Packet packet = item.redacted();
                ObjectNode candidate = candidates.addObject();
                candidate.put("index", i + 1);
                candidate.put("sheetName", item.sheetName());
                candidate.put("structuralRole", item.candidate().structuralRole());
                candidate.put("candidateKind", packet.candidateKind());

                ArrayNode cells = candidate.putArray("cells");
                for (PacketCell cell : packet.cells()) {
                    ObjectNode cellNode = cells.addObject();
                    cellNode.put("coord", cell.coord());
                    cellNode.put("role", cell.role());
                    if (cell.valueType() != null) cellNode.put("type", cell.valueType());
                    if (cell.displayValue() != null) cellNode.put("display", cell.displayValue());
                    if (cell.textValue() != null) cellNode.put("text", cell.textValue());
                }
            }

            String userMsg = mapper.writeValueAsString(root);
            StringBuilder sb = new StringBuilder();
            sb.append("BATCH CLASSIFICATION - Classify exactly ").append(batch.size()).append(" regions.\n\n");
            sb.append("INPUT:\n").append(userMsg).append("\n\n");
            sb.append("TASK: For each candidate (by index 1-").append(batch.size()).append("), provide:\n");
            sb.append("1. scheduleFamily: one of {").append(String.join(", ", scheduleFamilies)).append("}\n");
            sb.append("2. triage: MAIN or HELPER only\n");
            sb.append("3. relevance: PRIMARY, SECONDARY, or TERTIARY only\n");
            sb.append("4. rowLabels: array of row label strings (can be empty [])\n");
            sb.append("5. columnHeaders: array of column header strings (can be empty [])\n");
            sb.append("6. packetDefaultHead: string or null\n");
            sb.append("7. about: string (1-2 sentences)\n");
            sb.append("8. statedScale: unit|thousand|lakh|million|crore|billion, ONLY if a cell in that candidate")
                    .append(" literally says the money unit (\"Rs. In Lacs\" -> lakh, \"Amount in Rs\" -> unit); else null.")
                    .append(" scaleCell: the coord of that cell (e.g. \"J6\"), else null\n");
            sb.append("Also echo each candidate's own \"index\" number (as given above) in its result;")
                    .append(" return exactly one result per candidate.\n");
            sb.append("\nOUTPUT: Return ONLY JSON. No markdown, no text, no explanations.\n");
            sb.append("Wrap the ").append(batch.size()).append(" classification objects in a JSON object with key 'results':\n");
            sb.append("{\n");
            sb.append("  \"results\": [\n");
            sb.append("    {\"index\":1,\"scheduleFamily\":\"assets\",\"triage\":\"MAIN\",\"relevance\":\"PRIMARY\",\"rowLabels\":[\"Fixed Assets\"],\"columnHeaders\":[],\"packetDefaultHead\":\"Assets\",\"statedScale\":null,\"scaleCell\":null,\"about\":\"List of company assets.\"},\n");
            sb.append("    {\"index\":2,\"scheduleFamily\":\"liabilities\",\"triage\":\"HELPER\",\"relevance\":\"SECONDARY\",\"rowLabels\":[],\"columnHeaders\":[],\"packetDefaultHead\":null,\"statedScale\":null,\"scaleCell\":null,\"about\":\"Supporting detail.\"}\n");
            sb.append("  ]\n");
            sb.append("}\n\n");
            sb.append("CRITICAL: Return ONLY the JSON object with 'results' key containing the array. Nothing else.\n");
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to format batch Layer A prompt: " + e.getMessage(), e);
        }
    }

    private List<LayerAJudgment> parseBatchLayerAResponse(String jsonResponse, int expectedCount) throws Exception {
        List<LayerAJudgment> judgments = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode nodes = mapper.readTree(jsonResponse);
        if (Boolean.getBoolean("tev.trace")) {
    System.err.println("[layer-a-batch-debug] Full response: " + jsonResponse.substring(0, Math.min(1000, jsonResponse.length())));
        }

        // Handle multiple formats: [...], {"results": [...]}, etc.
        if (!nodes.isArray()) {
            if (nodes.isObject()) {
                if (nodes.has("results")) nodes = nodes.get("results");
                else if (nodes.has("candidates")) nodes = nodes.get("candidates");
                else if (nodes.has("data")) nodes = nodes.get("data");
                else {
                    // Find first array in object
                    for (com.fasterxml.jackson.databind.JsonNode field : nodes) {
                        if (field.isArray()) {
                            nodes = field;
                            break;
                        }
                    }
                }
            }
            if (!nodes.isArray()) {
                throw new IllegalArgumentException("Expected JSON array or object with array");
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode node = nodes.get(i);

            // Extract fields with null-safety
            String scheduleFamily = getTextField(node, "scheduleFamily");
            String suggestedFamily = getTextField(node, "suggestedFamily", "suggested_family");
            String triage = getTextField(node, "triage");
            String relevance = getTextField(node, "relevance");
            String packetDefaultHead = getTextField(node, "packetDefaultHead", "packet_default_head");
            String about = getTextField(node, "about");

            // Resolve family using same logic as individual parser
            String resolvedFamily = scheduleFamily;
            if (scheduleFamily == null || scheduleFamily.isBlank()) {
                resolvedFamily = suggestedFamily;
            }

            // Normalize and map triage and relevance
            if (triage != null) {
                triage = triage.toLowerCase(java.util.Locale.ROOT);
                // Map LLM triage values to database values
                if (triage.equals("main")) {
                    triage = "main";
                } else if (triage.equals("helper")) {
                    triage = "scratch";
                } else if (!triage.equals("scratch") && !triage.equals("orphan")) {
                    triage = "scratch"; // Default unknown values to scratch
                }
            }
            if (relevance != null) {
                relevance = relevance.toLowerCase(java.util.Locale.ROOT);
                // Map LLM relevance values to database values
                if (relevance.equals("primary")) {
                    relevance = Relevance.PRIMARY;
                } else if (relevance.equals("secondary") || relevance.equals("tertiary")) {
                    relevance = Relevance.SUPPORTING;
                } else if (relevance.equals("noise")) {
                    relevance = Relevance.NOISE;
                }
            }

            java.util.List<String> rowLabels = new java.util.ArrayList<>();
            if (node.has("rowLabels") && node.get("rowLabels").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode label : node.get("rowLabels")) {
                    rowLabels.add(label.asText());
                }
            }

            java.util.List<String> columnHeaders = new java.util.ArrayList<>();
            if (node.has("columnHeaders") && node.get("columnHeaders").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode header : node.get("columnHeaders")) {
                    columnHeaders.add(header.asText());
                }
            }

            // Validate required fields
            if (resolvedFamily == null || resolvedFamily.isBlank()
                    || !Triage.isKnown(triage)
                    || !Relevance.isKnown(relevance)
                    || about == null || about.isBlank()) {
                System.err.println("[layer-a-batch] Candidate " + i + " has invalid fields: family=" + resolvedFamily
                    + " triage=" + triage + " relevance=" + relevance + " about=" + (about == null ? "null" : about.substring(0, Math.min(50, about.length()))));
                // Skip this candidate and continue with next, or throw?
                // For now, use defaults to avoid breaking
                if (resolvedFamily == null) resolvedFamily = "assumptions";
                if (triage == null) triage = "scratch";
                if (relevance == null) relevance = "supporting";
                if (about == null) about = "Unclassified";
            }

            judgments.add(new LayerAJudgment(resolvedFamily, triage, relevance, rowLabels, columnHeaders, packetDefaultHead, about.trim(),
                    LayerAResponseParser.statedScale(getTextField(node, "statedScale", "stated_scale")),
                    LayerAResponseParser.cellCoord(getTextField(node, "scaleCell", "scale_cell"))));
            indexes.add(node.hasNonNull("index") && node.get("index").canConvertToInt() ? node.get("index").asInt() : null);
        }

        // Numbered answers are matched to candidates by number: a missing, extra or reordered
        // answer then affects only its own candidate. A null entry means "not answered".
        boolean numbered = !judgments.isEmpty() && indexes.stream().allMatch(java.util.Objects::nonNull);
        if (numbered) {
            List<LayerAJudgment> byIndex = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
            for (int i = 0; i < judgments.size(); i++) {
                int slot = indexes.get(i) - 1;
                if (slot >= 0 && slot < expectedCount && byIndex.get(slot) == null) {
                    byIndex.set(slot, judgments.get(i));
                }
            }
            return byIndex;
        }
        return judgments;
    }

    private static String getTextField(com.fasterxml.jackson.databind.JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName)) {
                com.fasterxml.jackson.databind.JsonNode field = node.get(fieldName);
                if (field != null && !field.isNull()) {
                    String text = field.asText().trim();
                    return text.isBlank() ? null : text;
                }
            }
        }
        return null;
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
                false,
                judgment.statedScale(),
                judgment.scaleEvidenceCell());
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
            db.connection().setAutoCommit(false);
            try {
                cellReader().replace(repo, parseRunId, llm);
                BindSummary summary = bindCandidates(repo, parseRunId, sheetById, catalog, mandateId, slice);
                db.connection().commit();
                return summary;
            } catch (Exception e) {
                db.connection().rollback();
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("bind failed: " + msg, e);
        }
    }

    /**
     * Binds every bind-eligible Candidate on the given sheets to the ontology and rewrites its cell labels.
     * Runs in the caller's transaction; the caller owns commit, rollback and any Layer B re-read.
     */
    private BindSummary bindCandidates(
            WorkspaceRepository repo,
            long parseRunId,
            Map<Long, String> sheetById,
            NomenclatureCatalog catalog,
            long mandateId,
            OntologySlice slice)
            throws SQLException, ClassifyException {
        Map<Long, PacketDisposition> aboutByCandidate = new HashMap<>();
        for (PacketDisposition disposition : repo.selectPacketDispositionsForParseRun(parseRunId)) {
            aboutByCandidate.put(disposition.candidateId(), disposition);
        }
        // Clear first so a re-bind never collides with a binding another region wrote earlier.
        for (CandidateRow candidate : repo.selectCandidatesForParseRun(parseRunId)) {
            if (sheetById.containsKey(candidate.worksheetId())) {
                repo.deleteNomenclatureBindings(parseRunId, candidate.candidateId());
            }
        }
        RunCellContext runContext = RunCellContext.create(repo, parseRunId);
        Set<Long> claimedCells = new HashSet<>();
        LivingOntology living = LivingOntology.from(slice);
        int bound = 0;
        int skipped = 0;
        List<CandidateRow> boundCandidates = new ArrayList<>();
        Map<Long, List<LayerBBinder.Draft>> draftsByCandidate = new LinkedHashMap<>();
        Map<Long, List<BindCellRow>> cellsByCandidate = new LinkedHashMap<>();
        List<BindJob> jobs = new ArrayList<>();
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
            jobs.add(new BindJob(
                    candidate,
                    cells,
                    sheetById.get(candidate.worksheetId()),
                    disposition != null ? disposition.scheduleFamily() : "",
                    disposition != null ? disposition.about() : ""));
        }
        // Binding runs in two rounds of concurrent model calls; see bindAll.
        BindRound round = bindAll(jobs, living, catalog, mandateId, runContext::inline);
        living = round.living();
        for (int i = 0; i < jobs.size(); i++) {
            BindJob job = jobs.get(i);
            CandidateRow candidate = job.candidate();
            List<BindCellRow> cells = job.cells();
            BindResult result = round.results().get(i);
            skipped += result.unbound();
            repo.deleteNomenclatureBindings(parseRunId, candidate.candidateId());
            List<LayerBBinder.Draft> fresh = new ArrayList<>();
            for (LayerBBinder.Draft draft : result.drafts()) {
                // A cell can sit in two regions (a helper band inside a main schedule); the first binding wins.
                if (!claimedCells.add(draft.cellId())) {
                    continue;
                }
                fresh.add(draft);
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
            draftsByCandidate.put(candidate.candidateId(), fresh);
            cellsByCandidate.put(candidate.candidateId(), cells);
            boundCandidates.add(candidate);
        }
        int helpers = applyCrossSheetHelpers(
                repo, parseRunId, boundCandidates, cellsByCandidate, draftsByCandidate);
        bound += helpers;
        HeaderBindingWriter.write(repo, parseRunId, boundCandidates);
        return new BindSummary(parseRunId, bound, skipped);
    }

    /** One region to bind: its cells and the Layer A brief the model reads with them. */
    private record BindJob(
            CandidateRow candidate, List<BindCellRow> cells, String sheetName, String family, String about) {}

    /**
     * Ask the first binding question for every region at once, from the catalog as it stands.
     * An entry is null when the region needed no question or its call failed; the sequential
     * pass then asks again, so a failed call costs time and never a binding.
     */
    private List<List<LayerBAssignment>> askFirstRound(
            List<BindJob> jobs, LivingOntology living, java.util.function.LongFunction<String> contextOf) {
        List<java.util.concurrent.Callable<List<LayerBAssignment>>> tasks = new ArrayList<>();
        Map<String, String> aliases = living.aliases();
        List<String> paths = living.paths();
        for (BindJob job : jobs) {
            tasks.add(() -> {
                List<BindCellRow> missing = LayerBBinder.unbound(
                        job.cells(), LayerBBinder.bind(job.cells(), List.of(), aliases));
                return missing.isEmpty()
                        ? null
                        : askLayerB(job.sheetName(), job.family(), job.about(), job.cells(), missing, paths,
                                false, contextOf);
            });
        }
        List<List<LayerBAssignment>> answers = new ArrayList<>();
        for (ParallelCalls.Outcome<List<LayerBAssignment>> outcome : ParallelCalls.run(tasks, tuning.concurrency())) {
            if (!outcome.ok()) {
                System.err.println("[layer-b] first binding round failed for a region, asking again in order: "
                        + outcome.error());
                System.err.flush();
            }
            answers.add(outcome.ok() ? outcome.value() : null);
        }
        return answers;
    }

    /**
     * Bind every region in two rounds of model calls. Round one asks each region its first
     * question; round two asks the retry for every region that still has unbound cells. Within a
     * round the calls go out together under {@code --parallelism}, so one slow call holds up only
     * itself; between rounds the answers are applied in region order, which keeps the result
     * independent of which call finished first. A region sees the catalog as it stood when its
     * round began, so a leaf minted by another region in the same round is not offered to it.
     */
    private BindRound bindAll(
            List<BindJob> jobs,
            LivingOntology living,
            NomenclatureCatalog catalog,
            long mandateId,
            java.util.function.LongFunction<String> contextOf)
            throws ClassifyException {
        List<List<LayerBAssignment>> firstAnswers = askFirstRound(jobs, living, contextOf);
        List<List<LayerBAssignment>> assignments = new ArrayList<>();
        List<List<BindCellRow>> open = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            BindJob job = jobs.get(i);
            List<LayerBAssignment> mine = new ArrayList<>();
            List<BindCellRow> missing = LayerBBinder.unbound(
                    job.cells(), LayerBBinder.bind(job.cells(), List.of(), living.aliases()));
            if (!missing.isEmpty()) {
                List<LayerBAssignment> first = firstAnswers.get(i) != null
                        ? firstAnswers.get(i)
                        : askLayerB(job.sheetName(), job.family(), job.about(), job.cells(), missing,
                                living.paths(), false, contextOf);
                living = absorbNewLeaves(living, catalog, mandateId, first);
                mine.addAll(first);
                missing = LayerBBinder.unbound(job.cells(), LayerBBinder.bind(job.cells(), mine, living.aliases()));
            }
            assignments.add(mine);
            open.add(missing);
        }
        List<Integer> retried = new ArrayList<>();
        List<java.util.concurrent.Callable<List<LayerBAssignment>>> tasks = new ArrayList<>();
        List<String> paths = living.paths();
        for (int i = 0; i < jobs.size(); i++) {
            if (open.get(i).isEmpty()) {
                continue;
            }
            BindJob job = jobs.get(i);
            List<BindCellRow> missing = open.get(i);
            retried.add(i);
            tasks.add(() -> askLayerB(
                    job.sheetName(), job.family(), job.about(), job.cells(), missing, paths, true, contextOf));
        }
        List<ParallelCalls.Outcome<List<LayerBAssignment>>> outcomes = ParallelCalls.run(tasks, tuning.concurrency());
        for (int k = 0; k < retried.size(); k++) {
            int i = retried.get(k);
            if (!outcomes.get(k).ok()) {
                System.err.println("[layer-b] retry failed for a region; its cells stay unbound: "
                        + outcomes.get(k).error());
                System.err.flush();
                continue;
            }
            living = absorbNewLeaves(living, catalog, mandateId, outcomes.get(k).value());
            assignments.get(i).addAll(outcomes.get(k).value());
        }
        List<BindResult> results = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            BindJob job = jobs.get(i);
            List<LayerBBinder.Draft> proved = LayerBBinder.bind(job.cells(), assignments.get(i), living.aliases());
            results.add(new BindResult(proved, LayerBBinder.unbound(job.cells(), proved).size(), living));
        }
        return new BindRound(results, living);
    }

    private record BindRound(List<BindResult> results, LivingOntology living) {}

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
            boolean retry,
            java.util.function.LongFunction<String> contextOf)
            throws ClassifyException {
        Set<String> unbound = new HashSet<>();
        for (BindCellRow cell : missing) {
            unbound.add(cell.coord().toUpperCase(Locale.ROOT));
        }
        LayerBPrompt prompt = new LayerBPrompt(
                sheetName,
                family,
                about == null || about.isBlank() ? "No Layer A about stored." : about,
                LayerBPromptAssembler.grid(cells, unbound, contextOf),
                catalog,
                retry);
        try {
            List<LayerBAssignment> assignments = llm.bindLayerB(prompt);
            return assignments == null ? List.of() : assignments;
        } catch (RuntimeException e) {
            // No configured model could answer: leave these cells unbound rather than stop the run.
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println("[layer-b] No model could bind " + missing.size() + " cell(s) on "
                    + sheetName + ": " + msg + " - leaving them unbound");
            System.err.flush();
            return List.of();
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
