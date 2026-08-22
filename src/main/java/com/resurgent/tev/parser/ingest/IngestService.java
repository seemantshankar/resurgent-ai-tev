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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application service behind {@code tev-parse ingest}: parses the input file and
 * lands the graph in the workspace DB in a single transaction — failure leaves
 * no partial graph behind.
 */
public final class IngestService {

    private static final String PARSER_VERSION = "0.1.0-SNAPSHOT";

    private static final Pattern EXTERNAL_LINK_INDEX_PATTERN = Pattern.compile("^\\[(\\d+)\\]");

    private final CsvSniffer sniffer = new CsvSniffer();
    private final CsvAdapter csvAdapter = new CsvAdapter();
    private final XlsxAdapter xlsxAdapter = new XlsxAdapter();
    private final XlsAdapter xlsAdapter = new XlsAdapter();
    private final CellContextEnricher enricher = new CellContextEnricher();
    private final SafetyEnforcer safetyEnforcer = new SafetyEnforcer();

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
        for (NormalizedCell cell : enriched) {
            long cellId = repo.insertCell(worksheetId, cell);
            recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(), cell);
            cellsWritten++;
        }

        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten, 0, 0, 0);
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, cellsWritten, coercedCount(enriched), errorCount(enriched),
                0, 0, 0, qa);
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
        ExternalRefStats refStats = new ExternalRefStats();

        Map<String, Long> sheetNameToId = new HashMap<>();
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
            Map<String, Long> coordMap = new HashMap<>();
            cellCoordMap.put(sheet.sheetName(), coordMap);

            List<NormalizedCell> enriched = enricher.enrich(sheet.cells());
            for (NormalizedCell cell : enriched) {
                NormalizedCell cellToInsert = cell;
                FormulaTokenizerResult tokRes = null;
                String skeleton = null;
                if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                    tokRes = FormulaTokenizer.tokenize(cell.formulaText(), cell.rowNum(), cell.colNum(), metadata.definedNames());
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
                            cell.rowHidden(), cell.colHidden(), cell.sheetHidden());
                }

                NormalizedCell resolved = resolveExternalRefs(cellToInsert, sheet.sheetName(),
                        parseRunId, linkIndexToId, repo, now, refStats);
                long cellId = repo.insertCell(worksheetId, resolved);
                coordMap.put(resolved.coord(), cellId);
                recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(), resolved);

                if (skeleton != null) {
                    repo.updateCellSkeleton(cellId, skeleton);
                }
                if (tokRes != null && !tokRes.tokens().isEmpty()) {
                    pendingTokensList.add(new PendingCellTokens(cellId, tokRes.tokens()));
                }

                cellsWritten++;
                if (resolved.coercedFromText()) {
                    cellsCoerced++;
                }
                if (resolved.isError()) {
                    cellsError++;
                }
            }
            sheetIndex++;
        }

        // Pass 2: Resolve reference tokens and persist cell_reference rows
        ReferenceResolver resolver = new ReferenceResolver(repo);
        for (PendingCellTokens pct : pendingTokensList) {
            resolver.resolveAndPersist(pct.cellId, pct.tokens, parseRunId, sheetNameToId, linkIndexToId, cellCoordMap, now);
        }

        // Pass 3: Graph construction and Tarjan SCC cycle detection
        DependencyGraphEngine graphEngine = new DependencyGraphEngine(repo);
        graphEngine.processWorkbookGraph(workbookId, parseRunId);

        // Pass 4: Error cascade tracing and root error barriers
        ErrorCascadeEngine errorEngine = new ErrorCascadeEngine(repo);
        errorEngine.processErrorCascades(parseRunId);

        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten,
                refStats.total, refStats.resolved, refStats.queued);
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, primarySheetName(sheets),
                rowCount, cellCount, cellsWritten, cellsCoerced, cellsError,
                refStats.total, refStats.resolved, refStats.queued, qa);
        repo.updateParseRunResult(parseRunId, Timestamps.now(), qa.status(), metricsJson);
        repo.insertAuditLog(parseRunId, "parse_run_completed", Timestamps.now(),
                Jsonb.toJson(Map.of("status", qa.status(), "cellsWritten", cellsWritten)),
                auditSeverity(qa.status()));
        repo.commit();
        return new IngestSummary(fileName, fileHash, primarySheetName(sheets), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath, qa.status(), metricsJson);
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

    /** Mutable running totals for external-reference reconciliation across a workbook's sheets. */
    private static final class ExternalRefStats {
        int total;
        int resolved;
        int queued;
    }

    private record PendingCellTokens(long cellId, List<FormulaToken> tokens) {}

    private static int coercedCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::coercedFromText).count();
    }

    private static int errorCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::isError).count();
    }

    private Set<String> collectReferencedDefinedNames(List<XlsxSheet> sheets, java.util.Collection<String> definedNames) {
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

    private NormalizedCell resolveExternalRefs(NormalizedCell cell, String sheetName,
            long parseRunId, Map<Integer, Long> linkIndexToId,
            WorkspaceRepository repo, String now, ExternalRefStats stats)
            throws IOException, SQLException {
        String formulaText = cell.formulaText();
        if (formulaText == null || formulaText.isBlank()) {
            return cell;
        }

        FormulaReferences refs = FormulaReferenceExtractor.extract(formulaText);
        Long firstLinkId = null;
        for (String ref : refs.externalRefs()) {
            Integer index = extractLinkIndex(ref);
            if (index == null) {
                continue;
            }
            stats.total++;
            Long linkId = linkIndexToId.get(index);
            if (linkId == null) {
                queueUnresolvableExternalRef(parseRunId, ref, sheetName, cell.coord(), repo, now);
                stats.queued++;
            } else {
                stats.resolved++;
                if (firstLinkId == null) {
                    firstLinkId = linkId;
                }
            }
        }
        return cell;
    }

    private static Integer extractLinkIndex(String externalRef) {
        Matcher m = EXTERNAL_LINK_INDEX_PATTERN.matcher(externalRef);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static void queueUnresolvableExternalRef(long parseRunId, String externalRef,
            String sheetName, String coord, WorkspaceRepository repo, String now)
            throws IOException, SQLException {
        String summary = "Unresolvable external reference: " + externalRef;
        String detail = Jsonb.toJson(Map.of(
                "externalRef", externalRef,
                "sheet", sheetName,
                "coord", coord));
        repo.insertReviewQueue(parseRunId, "external_link", summary, detail, "Pending",
                false, now, null);
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
