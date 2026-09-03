package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.CellStyle;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.safety.SafetyEnforcer;
import java.io.IOException;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application service behind {@code tev-parse ingest}: reads workbook bytes and
 * lands cells in the workspace DB in a single transaction.
 */
public final class IngestService {

    private static final String PARSER_VERSION = "0.1.2-SNAPSHOT";

    /**
     * POI sometimes emits barrier function names as NameX "external" tokens instead of
     * AbstractFunctionPtg. Strip those before persisting reference edges.
     */
    private static final Set<String> BARRIER_FUNCTIONS = Set.of(
            "IFERROR", "IFNA", "ISERROR", "ISNA", "ISERR", "COUNT", "COUNTA", "AGGREGATE",
            "SUBTOTAL");

    private final CsvSniffer sniffer = new CsvSniffer();
    private final CsvAdapter csvAdapter = new CsvAdapter();
    private final XlsxAdapter xlsxAdapter = new XlsxAdapter();
    private final XlsAdapter xlsAdapter = new XlsAdapter();
    private final SafetyEnforcer safetyEnforcer = new SafetyEnforcer();

    public IngestSummary ingest(Path input, long mandateId, Path dbPath)
            throws IOException, SQLException {
        return ingest(input, mandateId, dbPath, ParserConfig.embeddedDefaults());
    }

    public IngestSummary ingest(Path input, long mandateId, Path dbPath, ParserConfig config)
            throws IOException, SQLException {
        return ingest(input, mandateId, dbPath, config, WorkspaceDatabase.OpenOptions.defaults());
    }

    public IngestSummary ingest(Path input, long mandateId, Path dbPath, ParserConfig config,
            WorkspaceDatabase.OpenOptions openOptions) throws IOException, SQLException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileName = input.getFileName().toString();
        String fileHash = sha256(input);

        FileType fileType = rejectIfPolicyViolation(input, mandateId, dbPath,
                fileName, fileHash, config, openOptions);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath, openOptions)) {
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
            persistRejection(dbPath, mandateId, fileName, fileHash, e, openOptions);
            throw e;
        }
    }

    private FileType rejectIfPolicyViolation(Path input, long mandateId, Path dbPath,
            String fileName, String fileHash, ParserConfig config,
            WorkspaceDatabase.OpenOptions openOptions) throws IOException, SQLException {
        long fileSize = Files.size(input);
        if (fileSize > config.maxFileSizeBytes()) {
            IngestRejectionException rejection = new IngestRejectionException(
                    RejectionReason.FILE_TOO_LARGE,
                    config.maxFileSizeBytes(), fileSize,
                    "file size " + fileSize + " bytes exceeds configured limit "
                            + config.maxFileSizeBytes() + " bytes");
            persistRejection(dbPath, mandateId, fileName, fileHash, rejection, openOptions);
            throw rejection;
        }

        FileType fileType = FileType.fromPath(input);
        if (fileType == FileType.FM_XLS && !config.xlsEnabled()) {
            IngestRejectionException rejection = new IngestRejectionException(
                    RejectionReason.XLS_DISABLED,
                    config.xlsEnabled(), fileType.value(),
                    ".xls intake is disabled by default; set xlsEnabled to true to enable");
            persistRejection(dbPath, mandateId, fileName, fileHash, rejection, openOptions);
            throw rejection;
        }
        return fileType;
    }

    private void persistRejection(Path dbPath, long mandateId, String fileName,
            String fileHash, IngestRejectionException rejection,
            WorkspaceDatabase.OpenOptions openOptions) throws SQLException {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath, openOptions)) {
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
        int cellsWritten = 0;
        for (NormalizedCell cell : cells) {
            long cellId = repo.insertCell(worksheetId, cell, null, cell.formulaNormalized());
            recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(), cell);
            cellsWritten++;
        }

        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten);
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, cellsWritten, coercedCount(cells), errorCount(cells), qa);
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
        int rowCount = sheets.stream().mapToInt(IngestService::maxPopulatedRowNum).sum();
        int cellCount = sheets.stream().mapToInt(s -> s.cells().size()).sum();

        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                config.configHash(), now, null, "success", "{}");
        repo.insertAuditLog(parseRunId, "parse_run_started", now,
                Jsonb.toJson(Map.of("fileName", fileName, "fileHash", fileHash)), "info");

        long workbookId = repo.insertWorkbook(sourceFileId,
                metadata.applicationName(), metadata.applicationVersion(),
                metadata.sheetCount(), Jsonb.toJson(metadata.sheetNames()),
                Jsonb.toJson(new ArrayList<>(metadata.definedNames().keySet())),
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
        Map<CellStyle, Long> styleIds = new HashMap<>();
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
            sheetNameToId.put(sheetLookupKey(sheet.sheetName()), worksheetId);
            worksheetIdToSheetName.put(worksheetId, sheet.sheetName());
            Map<String, Long> coordMap = new HashMap<>();
            cellCoordMap.put(sheetLookupKey(sheet.sheetName()), coordMap);

            for (NormalizedCell cell : sheet.cells()) {
                NormalizedCell cellToInsert = cell;
                FormulaTokenizerResult tokRes = null;
                if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                    tokRes = FormulaTokenizer.tokenize(
                            cell.formulaText(), cell.rowNum(), cell.colNum(),
                            metadata.definedNames());
                    tokRes = new FormulaTokenizerResult(tokRes.formulaState(),
                            stripFunctionNamePseudoTokens(tokRes.tokens()),
                            tokRes.functionTokens());
                    cellToInsert = cell.withFormulaState(tokRes.formulaState());
                }

                Long styleId = resolveStyleId(repo, styleIds, cellToInsert.cellStyle());
                long cellId = repo.insertCell(worksheetId, cellToInsert, styleId,
                        cellToInsert.formulaNormalized());
                coordMap.put(cellToInsert.coord(), cellId);
                recordCellProvenance(repo, cellId, sourceFileId, parseRunId, sheet.sheetName(),
                        cellToInsert);

                if (tokRes != null && !tokRes.tokens().isEmpty()) {
                    pendingTokensList.add(new PendingCellTokens(cellId, worksheetId, tokRes.tokens()));
                }

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
            sheetIndex++;
        }

        ReferenceResolver resolver = new ReferenceResolver(repo);
        ReferenceStats refStats = new ReferenceStats();
        ReferenceResolutionContext refCtx = new ReferenceResolutionContext(sheetNameToId,
                worksheetIdToSheetName, linkIndexToId, cellCoordMap,
                metadata.definedNames().keySet(), parseRunId, now);
        for (PendingCellTokens pct : pendingTokensList) {
            resolver.resolveAndPersist(pct.cellId(), pct.worksheetId(), pct.tokens(), refCtx,
                    refStats);
        }

        repo.updateWorkbookCalcMetadata(workbookId, metadata.calculationMode(),
                metadata.fullCalcOnLoad(), metadata.calcChainPresent(), metadata.iterativeCalc(),
                metadata.iterativeCount(), cellsError);

        QaGateResult qa = QaGate.evaluate(cellCount, cellsWritten,
                refStats.total(), refStats.resolved(), refStats.unresolved(),
                formulaCellsTotal, formulaCellsTokenized, formulaCellsParseError,
                formulaCellsUnavailable);
        String metricsJson = IngestMetrics.toJson(fileName, fileHash, primarySheetName(sheets),
                rowCount, cellCount, cellsWritten, cellsCoerced, cellsError,
                refStats.total(), refStats.resolved(), refStats.unresolved(),
                formulaCellsTotal, formulaCellsTokenized, formulaCellsParseError,
                formulaCellsUnavailable, qa);
        repo.updateParseRunResult(parseRunId, Timestamps.now(), qa.status(), metricsJson);
        repo.insertAuditLog(parseRunId, "parse_run_completed", Timestamps.now(),
                Jsonb.toJson(Map.of("status", qa.status(), "cellsWritten", cellsWritten)),
                auditSeverity(qa.status()));
        repo.commit();
        return new IngestSummary(fileName, fileHash, primarySheetName(sheets), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath, qa.status(), metricsJson);
    }

    private static boolean isFunctionNamePseudoToken(FormulaToken token) {
        return "external".equals(token.refKind()) && token.targetSheetName() == null
                && token.rawToken() != null
                && BARRIER_FUNCTIONS.contains(token.rawToken().toUpperCase(Locale.ROOT));
    }

    private static List<FormulaToken> stripFunctionNamePseudoTokens(List<FormulaToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return tokens;
        }
        List<FormulaToken> cleaned = new ArrayList<>(tokens.size());
        for (FormulaToken token : tokens) {
            if (!isFunctionNamePseudoToken(token)) {
                cleaned.add(token);
            }
        }
        return cleaned;
    }

    private record PendingCellTokens(long cellId, long worksheetId, List<FormulaToken> tokens) {
    }

    /** Excel sheet names are case-insensitive; lookups use a Locale.ROOT key. */
    static String sheetLookupKey(String sheetName) {
        return sheetName == null ? null : sheetName.toLowerCase(Locale.ROOT);
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

    private static Long resolveStyleId(WorkspaceRepository repo, Map<CellStyle, Long> styleIds,
            CellStyle style) throws SQLException {
        if (style == null) {
            return null;
        }
        Long existing = styleIds.get(style);
        if (existing != null) {
            return existing;
        }
        long styleId = repo.insertCellStyle(style);
        styleIds.put(style, styleId);
        return styleId;
    }

    private static int coercedCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::coercedFromText).count();
    }

    private static int errorCount(List<NormalizedCell> cells) {
        return (int) cells.stream().filter(NormalizedCell::isError).count();
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
                value.isError(),
                value.errorType(),
                false, false, null, "cell", false, false, false, null);
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
        map.put("style_capture_reason", "csv_has_no_cell_styles");
        return Jsonb.toJson(map);
    }

    private static String primarySheetName(List<XlsxSheet> sheets) {
        return sheets.isEmpty() ? "" : sheets.get(0).sheetName();
    }

    private static int maxPopulatedRowNum(XlsxSheet sheet) {
        return sheet.cells().stream().mapToInt(NormalizedCell::rowNum).max().orElse(0);
    }

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
