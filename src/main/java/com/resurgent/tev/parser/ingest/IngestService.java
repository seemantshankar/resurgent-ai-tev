package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
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

    /** Fingerprint of the embedded default configuration (no --config support yet). */
    private static final String CONFIG_HASH = "embedded-defaults";

    private static final Pattern EXTERNAL_LINK_INDEX_PATTERN = Pattern.compile("^\\[(\\d+)\\]");

    private final CsvSniffer sniffer = new CsvSniffer();
    private final CsvAdapter csvAdapter = new CsvAdapter();
    private final XlsxAdapter xlsxAdapter = new XlsxAdapter();
    private final CellContextEnricher enricher = new CellContextEnricher();

    public IngestSummary ingest(Path input, long mandateId, Path dbPath)
            throws IOException, SQLException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileName = input.getFileName().toString();
        FileType fileType = FileType.fromPath(input);
        String fileHash = sha256(input);

        XlsxWorkbook xlsxWorkbook = null;
        if (fileType == FileType.FM_XLSX) {
            xlsxWorkbook = xlsxAdapter.parseWorkbook(input);
        }
        String rawMetadata = rawMetadataJson(input, fileType, xlsxWorkbook);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                WorkspaceRepository repo = new WorkspaceRepository(connection);
                String now = Timestamps.now();
                long sourceFileId = repo.insertSourceFile(mandateId,
                        fileName, fileHash, fileType.value(), now, PARSER_VERSION, rawMetadata);

                return switch (fileType) {
                    case FM_XLSX -> ingestXlsx(xlsxWorkbook, mandateId, dbPath,
                            fileName, fileHash, sourceFileId, repo, now);
                    case FM_CSV -> ingestCsv(input, mandateId, dbPath,
                            fileName, fileHash, sourceFileId, repo, now);
                };
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private IngestSummary ingestCsv(Path input, long mandateId, Path dbPath,
            String fileName, String fileHash, long sourceFileId,
            WorkspaceRepository repo, String now) throws IOException, SQLException {
        CsvDialect dialect = sniffer.sniff(input);
        CsvSheet sheet = csvAdapter.parse(input, dialect);
        int rowCount = sheet.rows().size();
        int cellCount = sheet.rows().stream().mapToInt(List::size).sum();

        String metricsJson = IngestSummary.metricsJson(
                fileName, fileHash, sheet.sheetName(), rowCount, cellCount);
        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                CONFIG_HASH, now, Timestamps.now(), "success", metricsJson);
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
        for (NormalizedCell cell : enriched) {
            repo.insertCell(worksheetId, cell);
        }
        repo.commit();
        return new IngestSummary(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath);
    }

    private IngestSummary ingestXlsx(XlsxWorkbook workbook, long mandateId, Path dbPath,
            String fileName, String fileHash, long sourceFileId,
            WorkspaceRepository repo, String now) throws IOException, SQLException {
        List<XlsxSheet> sheets = workbook.sheets();
        WorkbookMetadata metadata = workbook.metadata();
        int rowCount = sheets.stream().mapToInt(s -> maxPopulatedRowNum(s)).sum();
        int cellCount = sheets.stream().mapToInt(s -> s.cells().size()).sum();

        String metricsJson = IngestSummary.metricsJson(
                fileName, fileHash, primarySheetName(sheets), rowCount, cellCount);
        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                CONFIG_HASH, now, Timestamps.now(), "success", metricsJson);

        Set<String> referencedNames = collectReferencedDefinedNames(sheets);
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
        for (XlsxSheet sheet : sheets) {
            long worksheetId = repo.insertWorksheet(parseRunId, sheet.sheetName(),
                    sheetIndex, sheet.sheetState(),
                    sheet.bboxMinRow(), sheet.bboxMinCol(),
                    sheet.bboxMaxRow(), sheet.bboxMaxCol(),
                    sheet.dimensionsDeclared(), sheet.realContentRows(),
                    sheet.declaredMerged());
            List<NormalizedCell> enriched = enricher.enrich(sheet.cells());
            for (NormalizedCell cell : enriched) {
                NormalizedCell resolved = resolveExternalRefs(cell, sheet.sheetName(),
                        parseRunId, linkIndexToId, repo, now);
                repo.insertCell(worksheetId, resolved);
            }
            sheetIndex++;
        }
        repo.commit();
        return new IngestSummary(fileName, fileHash, primarySheetName(sheets), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath);
    }

    private Set<String> collectReferencedDefinedNames(List<XlsxSheet> sheets) throws IOException {
        Set<String> referenced = new HashSet<>();
        for (XlsxSheet sheet : sheets) {
            for (NormalizedCell cell : sheet.cells()) {
                String refsJson = cell.definedNameRefs();
                if (refsJson == null || refsJson.isBlank()) {
                    continue;
                }
                List<String> refs = Jsonb.fromJson(refsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                referenced.addAll(refs);
            }
        }
        return referenced;
    }

    private NormalizedCell resolveExternalRefs(NormalizedCell cell, String sheetName,
            long parseRunId, Map<Integer, Long> linkIndexToId,
            WorkspaceRepository repo, String now) throws IOException, SQLException {
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
            Long linkId = linkIndexToId.get(index);
            if (linkId == null) {
                queueUnresolvableExternalRef(parseRunId, ref, sheetName, cell.coord(), repo, now);
            } else if (firstLinkId == null) {
                firstLinkId = linkId;
            }
        }
        return firstLinkId == null ? cell : cell.withExternalLinkId(firstLinkId);
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
                false, false, null, "cell", false, false, false,
                null, null, null, null);
    }

    private String rawMetadataJson(Path input, FileType fileType, XlsxWorkbook workbook)
            throws IOException {
        if (fileType == FileType.FM_XLSX) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", "xlsx");
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
