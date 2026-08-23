package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.safety.SafetyEnforcer;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application service behind {@code tev-parse ingest}: parses the input file and
 * lands the graph in the workspace DB in a single transaction — failure leaves
 * no partial graph behind.
 */
public final class IngestService {

    private static final String PARSER_VERSION = "0.1.0-SNAPSHOT";

    /**
     * Error-consuming functions (§10.8): a cell whose formula's function set intersects
     * this set is an error barrier — its dependents do not inherit error_descendant/
     * cell_error_root through it, unless the barrier cell is itself still is_error.
     */
    private static final Set<String> BARRIER_FUNCTIONS = Set.of(
            "IFERROR", "IFNA", "ISERROR", "ISNA", "ISERR", "COUNT", "COUNTA", "AGGREGATE", "SUBTOTAL");

    private final CsvSniffer sniffer = new CsvSniffer();
    private final CsvAdapter csvAdapter = new CsvAdapter();
    private final XlsxAdapter xlsxAdapter = new XlsxAdapter();
    private final XlsAdapter xlsAdapter = new XlsAdapter();
    private final CellContextEnricher enricher = new CellContextEnricher();
    private final SafetyEnforcer safetyEnforcer = new SafetyEnforcer();
    private final RegionDetector regionDetector = new RegionDetector();
    private final RegionHeaderAnalyzer regionHeaderAnalyzer = new RegionHeaderAnalyzer();
    private static final double CLASSIFICATION_REVIEW_CONFIDENCE = 0.5;

    /** Convenience overload that uses embedded defaults. */
    public IngestSummary ingest(Path input, long mandateId, Path dbPath)
            throws IOException, SQLException {
        return ingest(input, mandateId, dbPath, ParserConfig.embeddedDefaults());
    }

    public IngestSummary ingest(Path input, long mandateId, Path dbPath, ParserConfig config)
            throws IOException, SQLException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileName = input.getFileName().toString();
        String fileHash = sha256(input);

        FileType fileType = rejectIfPolicyViolation(input, mandateId, dbPath,
                fileName, fileHash, config);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            safetyEnforcer.check(input, fileType, config);

            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                WorkspaceRepository repo = new WorkspaceRepository(connection);
                String now = Timestamps.now();

                Long existingRunId = ParseRunIdentity.findExistingParseRunId(
                        mandateId, fileHash, PARSER_VERSION, config.configHash(), repo);
                if (existingRunId != null) {
                    Long existingSourceFileId = repo.findSourceFileId(mandateId, fileHash);
                    connection.commit();
                    String metricsJson = repo.selectParseRunMetrics(existingRunId);
                    return IngestSummary.fromExistingRun(
                            fileName, fileHash, existingSourceFileId, existingRunId, dbPath, metricsJson);
                }

                XlsxWorkbook xlsxWorkbook = null;
                if (fileType == FileType.FM_XLSX) {
                    xlsxWorkbook = xlsxAdapter.parseWorkbook(input);
                } else if (fileType == FileType.FM_XLS) {
                    xlsxWorkbook = xlsAdapter.parseWorkbook(input);
                }
                String rawMetadata = rawMetadataJson(input, fileType, xlsxWorkbook);

                long sourceFileId = ParseRunIdentity.ensureSourceFile(mandateId, fileName,
                        fileHash, fileType.value(), PARSER_VERSION, rawMetadata, now, repo);

                return switch (fileType) {
                    case FM_XLSX, FM_XLS -> ingestXlsx(xlsxWorkbook, mandateId, dbPath,
                            fileName, fileHash, sourceFileId, repo, now, config);
                    case FM_CSV -> ingestCsv(input, mandateId, dbPath,
                            fileName, fileHash, sourceFileId, repo, now, config);
                };
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (IngestRejectionException e) {
            persistRejection(dbPath, mandateId, fileName, fileHash, e);
            throw e;
        }
    }

    private FileType rejectIfPolicyViolation(Path input, long mandateId, Path dbPath,
            String fileName, String fileHash, ParserConfig config) throws IOException, SQLException {
        long fileSize = Files.size(input);
        if (fileSize > config.maxFileSizeBytes()) {
            IngestRejectionException rejection = new IngestRejectionException(
                    RejectionReason.FILE_TOO_LARGE,
                    config.maxFileSizeBytes(), fileSize,
                    "file size " + fileSize + " bytes exceeds configured limit "
                            + config.maxFileSizeBytes() + " bytes");
            persistRejection(dbPath, mandateId, fileName, fileHash, rejection);
            throw rejection;
        }

        FileType fileType = FileType.fromPath(input);
        if (fileType == FileType.FM_XLS && !config.xlsEnabled()) {
            IngestRejectionException rejection = new IngestRejectionException(
                    RejectionReason.XLS_DISABLED,
                    config.xlsEnabled(), fileType.value(),
                    ".xls intake is disabled by default; set xlsEnabled to true to enable");
            persistRejection(dbPath, mandateId, fileName, fileHash, rejection);
            throw rejection;
        }
        return fileType;
    }

    private void persistRejection(Path dbPath, long mandateId, String fileName,
            String fileHash, IngestRejectionException rejection) throws SQLException {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                WorkspaceRepository repo = new WorkspaceRepository(connection);
                String now = Timestamps.now();
                String detail;
                try {
                    detail = Jsonb.toJson(Map.of(
                            "reasonCode", rejection.reasonCode(),
                            "configuredLimit", rejection.configuredLimit(),
                            "observedValue", rejection.observedValue()));
                } catch (IOException ex) {
                    detail = "{}";
                }
                repo.insertIngestRejection(null, mandateId, fileName, fileHash,
                        rejection.reasonCode(), detail, now);
                String auditPayload;
                try {
                    auditPayload = Jsonb.toJson(Map.of(
                            "reasonCode", rejection.reasonCode(),
                            "mandateId", mandateId,
                            "fileName", fileName,
                            "fileHash", fileHash));
                } catch (IOException ex) {
                    auditPayload = "{}";
                }
                repo.insertAuditLog(null, "ingest_rejected", now, auditPayload, "warning");
                repo.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private IngestSummary ingestCsv(Path input, long mandateId, Path dbPath,
            String fileName, String fileHash, long sourceFileId,
            WorkspaceRepository repo, String now, ParserConfig config) throws IOException, SQLException {
        CsvDialect dialect = sniffer.sniff(input);
        CsvSheet sheet = csvAdapter.parse(input, dialect);
        int rowCount = sheet.rows().size();
        int cellCount = sheet.rows().stream().mapToInt(List::size).sum();

        // Placeholder row: worksheet/cell inserts below need a parse_run_id to hang off.
        // The real status and metrics, once known, overwrite this via updateParseRunResult
        // before commit, so nothing but this transaction ever observes the placeholder.
        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                config.configHash(), now, null, "success", "{}");
        repo.insertAuditLog(parseRunId, "parse_run_started", now,
                Jsonb.toJson(Map.of("fileName", fileName, "fileHash", fileHash)), "info");
        long worksheetId = repo.insertWorksheet(parseRunId, sheet.sheetName(), 0);

        List<NormalizedCell> cells = new ArrayList<>();
        int rowNum = 1;
        for (List<String> row : sheet.rows()) {
            int colNum = 1;
            for (String field : row) {
                cells.add(csvCell(rowNum, colNum, field));
                colNum++;
            }
            rowNum++;
        }
        List<NormalizedCell> enriched = enricher.enrich(cells);
        int cellsWritten = 0;
        Map<Long, RegionDetector.RegionCell> cellsById = new LinkedHashMap<>();
        for (NormalizedCell cell : enriched) {
            long cellId = repo.insertCell(worksheetId, cell);
            recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(), cell);
            cellsById.put(cellId, new RegionDetector.RegionCell(cell, null));
            cellsWritten++;
        }
        persistRegions(repo, worksheetId, parseRunId, sheet.sheetName(), cellsById, config);

        // CSV has no formulas or structural references, so those reconciliation buckets
        // are trivially 0/0/0 and never force a partial/failed status on their own.
        RegionQaStats regionQa = repo.selectRegionQaStats(parseRunId, CLASSIFICATION_REVIEW_CONFIDENCE);
        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten, 0, 0, 0, 0, 0, 0, 0,
                regionQa.cellsWithoutRegion(), regionQa.regionsUnaccounted());
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, cellsWritten, coercedCount(enriched), errorCount(enriched),
                0, 0, 0, 0, 0, 0, 0, regionQa, qa);
        repo.updateParseRunResult(parseRunId, Timestamps.now(), qa.status(), metricsJson);
        repo.insertAuditLog(parseRunId, "parse_run_completed", Timestamps.now(),
                Jsonb.toJson(Map.of("status", qa.status(), "cellsWritten", cellsWritten)),
                auditSeverity(qa.status()));
        repo.commit();
        return new IngestSummary(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath, qa.status(), metricsJson);
    }

    private IngestSummary ingestXlsx(XlsxWorkbook workbook, long mandateId, Path dbPath,
            String fileName, String fileHash, long sourceFileId,
            WorkspaceRepository repo, String now, ParserConfig config) throws IOException, SQLException {
        List<XlsxSheet> sheets = workbook.sheets();
        WorkbookMetadata metadata = workbook.metadata();
        int rowCount = sheets.stream().mapToInt(s -> maxPopulatedRowNum(s)).sum();
        int cellCount = sheets.stream().mapToInt(s -> s.cells().size()).sum();

        // Placeholder row, overwritten with the real status/metrics via
        // updateParseRunResult once the sheet loop below finishes (see ingestCsv).
        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                config.configHash(), now, null, "success", "{}");
        repo.insertAuditLog(parseRunId, "parse_run_started", now,
                Jsonb.toJson(Map.of("fileName", fileName, "fileHash", fileHash)), "info");

        Set<String> referencedNames = collectReferencedDefinedNames(sheets, metadata.definedNames().keySet());
        long workbookId = repo.insertWorkbook(sourceFileId,
                metadata.applicationName(), metadata.applicationVersion(),
                metadata.sheetCount(), Jsonb.toJson(metadata.sheetNames()),
                Jsonb.toJson(new ArrayList<>(referencedNames)),
                Jsonb.toJson(metadata.properties()), metadata.isProtected(),
                metadata.createdAt(), metadata.modifiedAt());

        Map<Integer, Long> linkIndexToId = new HashMap<>();
        for (ExternalLinkIn link : metadata.externalLinks()) {
            long linkId = repo.insertExternalLink(workbookId, "external",
                    link.linkIndex(), link.targetUri(),
                    link.refreshError() ? "broken" : "unchecked", now);
            linkIndexToId.put(link.linkIndex(), linkId);
        }

        int sheetIndex = 0;
        int cellsWritten = 0;
        int cellsCoerced = 0;
        int cellsError = 0;
        int formulaCellsTotal = 0;
        int formulaCellsTokenized = 0;
        int formulaCellsParseError = 0;
        int formulaCellsUnavailable = 0;

        // B3: built once alongside sheetNameToId so ReferenceResolver never needs to
        // linear-scan for a worksheet's sheet name.
        Map<String, Long> sheetNameToId = new HashMap<>();
        Map<Long, String> worksheetIdToSheetName = new HashMap<>();
        Map<String, Map<String, Long>> cellCoordMap = new HashMap<>();
        List<PendingCellTokens> pendingTokensList = new ArrayList<>();

        for (XlsxSheet sheet : sheets) {
            long worksheetId = repo.insertWorksheet(parseRunId, sheet.sheetName(),
                    sheetIndex, sheet.sheetState(),
                    sheet.bboxMinRow(), sheet.bboxMinCol(),
                    sheet.bboxMaxRow(), sheet.bboxMaxCol(),
                    sheet.dimensionsDeclared(), sheet.realContentRows(),
                    sheet.declaredMerged());
            sheetNameToId.put(sheet.sheetName(), worksheetId);
            worksheetIdToSheetName.put(worksheetId, sheet.sheetName());
            Map<String, Long> coordMap = new HashMap<>();
            cellCoordMap.put(sheet.sheetName(), coordMap);
            Map<Long, RegionDetector.RegionCell> cellsById = new LinkedHashMap<>();

            List<NormalizedCell> enriched = enricher.enrich(sheet.cells());
            for (NormalizedCell cell : enriched) {
                NormalizedCell cellToInsert = cell;
                FormulaTokenizerResult tokRes = null;
                String skeleton = null;
                boolean isBarrierForCurrentCell = false;
                if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                    tokRes = FormulaTokenizer.tokenize(cell.formulaText(), cell.rowNum(), cell.colNum(), metadata.definedNames());
                    isBarrierForCurrentCell = isBarrierFormula(tokRes);
                    // POI misclassifies a handful of functions (observed for IFERROR/IFNA) as a
                    // "NameX" external-name reference instead of an AbstractFunctionPtg, which
                    // would otherwise pollute both the skeleton and the persisted reference graph
                    // with a bogus "external reference" to the bare function name. Strip those
                    // pseudo-tokens out before they're used for anything downstream.
                    tokRes = new FormulaTokenizerResult(tokRes.formulaState(),
                            stripFunctionNamePseudoTokens(tokRes.tokens()), tokRes.functionTokens());
                    skeleton = FormulaSkeletonGenerator.generate(cell.formulaText(), tokRes.tokens());

                    BigDecimal evaluatedNumeric = cell.numericValue();
                    boolean isErr = cell.isError();
                    String errType = cell.errorType();

                    if (tokRes.tokens().isEmpty()) {
                        ConstantFormulaEvaluator.EvalResult evalRes = ConstantFormulaEvaluator.evaluate(cell.formulaText(), tokRes.tokens());
                        if (evalRes != null) {
                            if (evalRes.numericValue() != null) {
                                evaluatedNumeric = evalRes.numericValue();
                            }
                            if (evalRes.isError()) {
                                isErr = true;
                                errType = evalRes.errorType();
                            }
                        }
                    }

                    cellToInsert = new NormalizedCell(
                            cell.coord(), cell.rowNum(), cell.colNum(),
                            cell.rawValue(), cell.rawType(), cell.valueType(),
                            cell.textValue(), cell.displayValue(), evaluatedNumeric,
                            cell.boolValue(), cell.dateValue(), cell.formulaText(),
                            cell.formulaNormalized(), tokRes.formulaState(),
                            cell.cachedValue(), cell.cacheState(), cell.coercedFromText(),
                            cell.parsedQuantity(), isErr, errType,
                            cell.rowLabel(), cell.colLabel(), cell.isMergedAnchor(),
                            cell.isMergedParticipant(), cell.mergedRange(), cell.valueSource(),
                            cell.rowHidden(), cell.colHidden(), cell.sheetHidden(),
                            cell.isBold(), cell.hasFill(), cell.hasBorder(), cell.numberFormat());
                }

                long cellId = repo.insertCell(worksheetId, cellToInsert);
                coordMap.put(cellToInsert.coord(), cellId);
                cellsById.put(cellId, new RegionDetector.RegionCell(cellToInsert, skeleton));
                recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(), cellToInsert);

                if (skeleton != null) {
                    repo.updateCellSkeleton(cellId, skeleton);
                }
                if (tokRes != null && !tokRes.tokens().isEmpty()) {
                    pendingTokensList.add(new PendingCellTokens(cellId, worksheetId, tokRes.tokens()));
                }
                if (isBarrierForCurrentCell) {
                    repo.updateCellErrorBarrier(cellId, true);
                }

                // Formula reconciliation (§12/C6): every formula cell's tokenization state
                // is accounted for as exactly one of tokenized/parse_error/unavailable.
                if ("formula".equals(cellToInsert.rawType())) {
                    formulaCellsTotal++;
                    String state = cellToInsert.formulaState();
                    if ("ok".equals(state)) {
                        formulaCellsTokenized++;
                    } else if ("parse_error".equals(state)) {
                        formulaCellsParseError++;
                    } else if ("unavailable".equals(state)) {
                        formulaCellsUnavailable++;
                    }
                }

                cellsWritten++;
                if (cellToInsert.coercedFromText()) {
                    cellsCoerced++;
                }
                if (cellToInsert.isError()) {
                    cellsError++;
                }
            }
            persistRegions(repo, worksheetId, parseRunId, sheet.sheetName(), cellsById, config);
            sheetIndex++;
        }

        // Pass 2: Resolve reference tokens and persist cell_reference rows.
        ReferenceResolver resolver = new ReferenceResolver(repo);
        ReferenceStats refStats = new ReferenceStats();
        ReferenceResolutionContext refCtx = new ReferenceResolutionContext(sheetNameToId,
                worksheetIdToSheetName, linkIndexToId, cellCoordMap,
                metadata.definedNames().keySet(), parseRunId, now);
        for (PendingCellTokens pct : pendingTokensList) {
            resolver.resolveAndPersist(pct.cellId, pct.worksheetId, pct.tokens, refCtx, refStats);
        }

        // Pass 3: Graph construction and Tarjan SCC cycle detection. Both this pass and
        // pass 4 share one adjacency map (ranges expanded in memory, clamped to each
        // worksheet's real bbox) built once here rather than each running its own query.
        Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
        DependencyGraphEngine graphEngine = new DependencyGraphEngine(repo);
        graphEngine.processWorkbookGraph(workbookId, parseRunId, adjacency, metadata.iterativeCalc());

        // Pass 4: Error cascade tracing — root error cells and their non-error descendants.
        ErrorCascadeEngine errorEngine = new ErrorCascadeEngine(repo);
        errorEngine.processErrorCascades(parseRunId, adjacency);

        // Calc metadata persistence (#19 / C5): cellsError is only known now that the
        // sheet loop above has finished, so this can't happen right after insertWorkbook.
        repo.updateWorkbookCalcMetadata(workbookId, metadata.calculationMode(),
                metadata.fullCalcOnLoad(), metadata.calcChainPresent(), metadata.iterativeCalc(),
                metadata.iterativeCount(), cellsError);

        RegionQaStats regionQa = repo.selectRegionQaStats(parseRunId, CLASSIFICATION_REVIEW_CONFIDENCE);
        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten,
                refStats.total(), refStats.resolved(), refStats.unresolved(),
                formulaCellsTotal, formulaCellsTokenized, formulaCellsParseError, formulaCellsUnavailable,
                regionQa.cellsWithoutRegion(), regionQa.regionsUnaccounted());
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, primarySheetName(sheets),
                rowCount, cellCount, cellsWritten, cellsCoerced, cellsError,
                refStats.total(), refStats.resolved(), refStats.unresolved(),
                formulaCellsTotal, formulaCellsTokenized, formulaCellsParseError, formulaCellsUnavailable,
                regionQa, qa);
        repo.updateParseRunResult(parseRunId, Timestamps.now(), qa.status(), metricsJson);
        repo.insertAuditLog(parseRunId, "parse_run_completed", Timestamps.now(),
                Jsonb.toJson(Map.of("status", qa.status(), "cellsWritten", cellsWritten)),
                auditSeverity(qa.status()));
        repo.commit();
        return new IngestSummary(fileName, fileHash, primarySheetName(sheets), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath, qa.status(), metricsJson);
    }

    /**
     * A cell is an error barrier when its formula's function set intersects
     * {@link #BARRIER_FUNCTIONS}. POI's {@link FormulaTokenizer} normally surfaces a
     * function name via {@code functionTokens} (an {@code AbstractFunctionPtg}), but for
     * a handful of functions POI's parser doesn't have builtin metadata for in this parse
     * context (observed for IFERROR/IFNA) it instead emits a name-lookup reference token
     * with {@code refKind="external"} whose raw text is the bare function name — so both
     * sources are checked here, not just functionTokens.
     */
    private static boolean isBarrierFormula(FormulaTokenizerResult tokRes) {
        if (tokRes.functionTokens() != null && tokRes.functionTokens().stream()
                .map(String::toUpperCase).anyMatch(BARRIER_FUNCTIONS::contains)) {
            return true;
        }
        return tokRes.tokens() != null && tokRes.tokens().stream()
                .anyMatch(IngestService::isFunctionNamePseudoToken);
    }

    /** True for a token that is actually a barrier function's bare name, not a real reference. */
    private static boolean isFunctionNamePseudoToken(FormulaToken t) {
        return "external".equals(t.refKind()) && t.targetSheetName() == null
                && t.rawToken() != null && BARRIER_FUNCTIONS.contains(t.rawToken().toUpperCase());
    }

    private static List<FormulaToken> stripFunctionNamePseudoTokens(List<FormulaToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return tokens;
        }
        List<FormulaToken> cleaned = new ArrayList<>(tokens.size());
        for (FormulaToken t : tokens) {
            if (!isFunctionNamePseudoToken(t)) {
                cleaned.add(t);
            }
        }
        return cleaned;
    }

    private void persistRegions(WorkspaceRepository repo, long worksheetId, long parseRunId,
            String sheetName, Map<Long, RegionDetector.RegionCell> cellsById, ParserConfig config)
            throws SQLException, IOException {
        for (RegionDetector.DetectedRegion region : regionDetector.detect(sheetName, cellsById,
                config.regionBreakThreshold())) {
            List<Map.Entry<Long, RegionDetector.RegionCell>> regionEntries = region.cellIds().stream()
                    .map(id -> Map.entry(id, cellsById.get(id)))
                    .toList();
            List<NormalizedCell> regionCells = regionEntries.stream()
                    .map(entry -> entry.getValue().cell())
                    .toList();
            RegionHeaderContext headerContext = regionHeaderAnalyzer.analyze(regionCells,
                    new RegionHeaderAnalyzer.Bounds(region.startRow(), region.endRow(),
                            region.startCol(), region.endCol()));
            RegionClassification classification = new RegionClassifier(
                    com.resurgent.tev.parser.config.RegionWeights.defaults(),
                    config.classificationEvidenceFloor()).classify(
                    new RegionClassifier.RegionBounds(region.startRow(), region.endRow(),
                            region.startCol(), region.endCol()),
                    regionEntries.stream().map(entry -> classifierCell(entry.getValue().cell())).toList(),
                    new RegionClassifier.HeaderContext(headerContext.headerRows(),
                            new ArrayList<>(headerContext.columnLabelsByColumn().values())));
            long regionId = repo.insertRegion(worksheetId, parseRunId, region.key(),
                    region.startRow(), region.endRow(), region.startCol(), region.endCol(),
                    Jsonb.toJson(headerContext.headerRows()), classification.type().databaseValue(),
                    classification.confidence(), classification.costHeadCode(),
                    Jsonb.toJson(headerContext.periodAxisByColumn()), Jsonb.toJson(classification.reasons()));
            for (long cellId : region.cellIds()) {
                repo.updateCellRegion(cellId, regionId);
            }
            persistRegionLabels(repo, regionEntries, headerContext);
            queueClassificationReview(repo, parseRunId, regionId, region, classification);
            persistCoherence(repo, region, cellsById);
        }
    }

    private static RegionClassifier.RegionCell classifierCell(NormalizedCell cell) {
        return new RegionClassifier.RegionCell(cell.rowNum(), cell.colNum(), cell.displayValue(),
                cell.formulaText() != null && !cell.formulaText().isBlank(), cell.numericValue() != null);
    }

    private static void persistRegionLabels(WorkspaceRepository repo,
            List<Map.Entry<Long, RegionDetector.RegionCell>> regionEntries,
            RegionHeaderContext headerContext) throws SQLException {
        for (Map.Entry<Long, RegionDetector.RegionCell> entry : regionEntries) {
            NormalizedCell cell = entry.getValue().cell();
            String rowLabel = headerContext.rowLabelsByRow().getOrDefault(cell.rowNum(), cell.rowLabel());
            String colLabel = headerContext.columnLabelsByColumn().getOrDefault(cell.colNum(), cell.colLabel());
            repo.updateCellLabels(entry.getKey(), rowLabel, colLabel);
        }
    }

    private static void queueClassificationReview(WorkspaceRepository repo, long parseRunId, long regionId,
            RegionDetector.DetectedRegion region, RegionClassification classification)
            throws SQLException, IOException {
        if (classification.type() != RegionType.UNKNOWN
                && classification.confidence() >= CLASSIFICATION_REVIEW_CONFIDENCE) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("regionId", regionId);
        detail.put("regionKey", region.key());
        detail.put("regionType", classification.type().databaseValue());
        detail.put("regionConfidence", classification.confidence());
        detail.put("reasonCodes", classification.reasons().stream()
                .map(reason -> reason.code().name()).toList());
        repo.insertReviewQueue(parseRunId, "region_classification",
                "Region classification requires review: " + region.key(), Jsonb.toJson(detail),
                "Pending", false, Timestamps.now(), null);
    }

    private static void persistCoherence(WorkspaceRepository repo, RegionDetector.DetectedRegion region,
            Map<Long, RegionDetector.RegionCell> cellsById) throws SQLException, IOException {
        Map<Long, RegionDetector.RegionCell> regionCells = new LinkedHashMap<>();
        for (long id : region.cellIds()) {
            regionCells.put(id, cellsById.get(id));
        }
        for (Map.Entry<Long, RegionDetector.RegionCell> entry : regionCells.entrySet()) {
            if (entry.getValue().formulaSkeleton() == null) {
                continue;
            }
            Map<String, Double> dirs = RegionDetector.coherenceDirections(entry.getKey(), regionCells);
            java.util.OptionalDouble average = dirs.values().stream().filter(value -> value > 0)
                    .mapToDouble(Double::doubleValue).average();
            Double score = average.isPresent() ? average.getAsDouble() : null;
            repo.updateCellCoherence(entry.getKey(), score, Jsonb.toJson(dirs));
        }
    }

    private static void recordCellProvenance(WorkspaceRepository repo, long cellId,
            long sourceFileId, long parseRunId, String sheetName, NormalizedCell cell)
            throws SQLException {
        repo.insertProvenance("cell", cellId, sourceFileId, parseRunId,
                sheetName + "!" + cell.coord(), cell.rawValue(), 1.0, false, null);
    }

    private static String auditSeverity(String qaStatus) {
        return switch (qaStatus) {
            case "success" -> "info";
            case "partial" -> "warning";
            default -> "error";
        };
    }

    private record PendingCellTokens(long cellId, long worksheetId, List<FormulaToken> tokens) {}

    private static int coercedCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::coercedFromText).count();
    }

    private static int errorCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::isError).count();
    }

    private Set<String> collectReferencedDefinedNames(List<XlsxSheet> sheets, Collection<String> definedNames) {
        Set<String> referenced = new HashSet<>();
        for (XlsxSheet sheet : sheets) {
            for (NormalizedCell cell : sheet.cells()) {
                if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                    FormulaReferences refs = FormulaReferenceExtractor.extract(cell.formulaText(), definedNames);
                    if (refs.definedNameRefs() != null) {
                        referenced.addAll(refs.definedNameRefs());
                    }
                }
            }
        }
        return referenced;
    }

    private static NormalizedCell csvCell(int rowNum, int colNum, String field) {
        String coord = coord(rowNum, colNum);
        CellValue value = CellNormalizer.normalize(field);
        return new NormalizedCell(
                coord, rowNum, colNum,
                field,
                value.rawType(),
                value.valueType(),
                value.textValue(),
                value.displayValue(),
                value.numericValue(),
                value.boolValue(),
                value.dateValue(),
                null,
                null,
                null,
                null,
                null,
                value.coercedFromText(),
                value.parsedQuantity(),
                value.isError(),
                value.errorType(),
                null, null,
                false, false, null, "cell", false, false, false);
    }

    private String rawMetadataJson(Path input, FileType fileType, XlsxWorkbook workbook)
            throws IOException {
        if (fileType == FileType.FM_XLSX || fileType == FileType.FM_XLS) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", fileType == FileType.FM_XLSX ? "xlsx" : "xls");
            if (fileType == FileType.FM_XLS) {
                map.put("style_capture_reason", "xls_style_capture_not_supported");
            }
            if (workbook != null) {
                WorkbookMetadata metadata = workbook.metadata();
                map.put("sheetCount", metadata.sheetCount());
                map.put("sheetNames", metadata.sheetNames());
                map.put("definedNames", metadata.definedNames());
                map.put("applicationName", metadata.applicationName());
                map.put("applicationVersion", metadata.applicationVersion());
                map.put("isProtected", metadata.isProtected());
            }
            return Jsonb.toJson(map);
        }
        CsvDialect dialect = sniffer.sniff(input);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("encoding", dialect.encoding());
        map.put("delimiter", String.valueOf(dialect.delimiter()));
        map.put("hasBom", dialect.hasBom());
        map.put("detectedBy", dialect.detectedBy());
        map.put("style_capture_reason", "csv_has_no_cell_styles");
        return Jsonb.toJson(map);
    }

    private static String primarySheetName(List<XlsxSheet> sheets) {
        return sheets.isEmpty() ? "" : sheets.get(0).sheetName();
    }

    private static int maxPopulatedRowNum(XlsxSheet sheet) {
        return sheet.cells().stream().mapToInt(NormalizedCell::rowNum).max().orElse(0);
    }

    /** A1-style coordinate: 1-based row and column. */
    private static String coord(int rowNum, int colNum) {
        StringBuilder col = new StringBuilder();
        int c = colNum;
        while (c > 0) {
            col.insert(0, (char) ('A' + (c - 1) % 26));
            c = (c - 1) / 26;
        }
        return col.toString() + rowNum;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
