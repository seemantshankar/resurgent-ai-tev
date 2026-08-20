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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service behind {@code tev-parse ingest}: parses the input file and
 * lands the graph in the workspace DB in a single transaction — failure leaves
 * no partial graph behind.
 */
public final class IngestService {

    private static final String PARSER_VERSION = "0.1.0-SNAPSHOT";

    /** Fingerprint of the embedded default configuration (no --config support yet). */
    private static final String CONFIG_HASH = "embedded-defaults";

    private final CsvSniffer sniffer = new CsvSniffer();
    private final CsvAdapter csvAdapter = new CsvAdapter();
    private final XlsxAdapter xlsxAdapter = new XlsxAdapter();

    public IngestSummary ingest(Path input, long mandateId, Path dbPath)
            throws IOException, SQLException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileName = input.getFileName().toString();
        FileType fileType = FileType.fromPath(input);
        String fileHash = sha256(input);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                WorkspaceRepository repo = new WorkspaceRepository(connection);
                String now = Timestamps.now();
                String rawMetadata = rawMetadataJson(input, fileType);
                long sourceFileId = repo.insertSourceFile(mandateId,
                        fileName, fileHash, fileType.value(), now, PARSER_VERSION, rawMetadata);

                return switch (fileType) {
                    case FM_XLSX -> ingestXlsx(input, mandateId, dbPath,
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

        int rowNum = 1;
        for (List<String> row : sheet.rows()) {
            int colNum = 1;
            for (String field : row) {
                NormalizedCell cell = csvCell(rowNum, colNum, field);
                repo.insertCell(worksheetId, cell);
                colNum++;
            }
            rowNum++;
        }
        repo.commit();
        return new IngestSummary(fileName, fileHash, sheet.sheetName(), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath);
    }

    private IngestSummary ingestXlsx(Path input, long mandateId, Path dbPath,
            String fileName, String fileHash, long sourceFileId,
            WorkspaceRepository repo, String now) throws IOException, SQLException {
        List<XlsxSheet> sheets = xlsxAdapter.parse(input);
        int rowCount = sheets.stream().mapToInt(s -> maxPopulatedRowNum(s)).sum();
        int cellCount = sheets.stream().mapToInt(s -> s.cells().size()).sum();

        String metricsJson = IngestSummary.metricsJson(
                fileName, fileHash, primarySheetName(sheets), rowCount, cellCount);
        long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                CONFIG_HASH, now, Timestamps.now(), "success", metricsJson);

        int sheetIndex = 0;
        for (XlsxSheet sheet : sheets) {
            long worksheetId = repo.insertWorksheet(parseRunId, sheet.sheetName(),
                    sheetIndex, sheet.sheetState());
            for (NormalizedCell cell : sheet.cells()) {
                repo.insertCell(worksheetId, cell);
            }
            sheetIndex++;
        }
        repo.commit();
        return new IngestSummary(fileName, fileHash, primarySheetName(sheets), rowCount,
                cellCount, sourceFileId, parseRunId, dbPath);
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
                value.errorType());
    }

    private String rawMetadataJson(Path input, FileType fileType) throws IOException {
        if (fileType == FileType.FM_XLSX) {
            return Jsonb.toJson(Map.of("format", "xlsx"));
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
