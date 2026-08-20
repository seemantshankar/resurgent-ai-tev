package com.resurgent.tev.parser.ingest;

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
import java.util.List;

/**
 * Application service behind {@code tev-parse ingest}: parses the input file and
 * lands the graph in the workspace DB in a single transaction — failure leaves
 * no partial graph behind.
 */
public final class IngestService {

    private static final String PARSER_VERSION = "0.1.0-SNAPSHOT";

    /** Fingerprint of the embedded default configuration (no --config support yet). */
    private static final String CONFIG_HASH = "embedded-defaults";

    private final CsvAdapter csvAdapter = new CsvAdapter();

    public IngestSummary ingest(Path input, long mandateId, Path dbPath)
            throws IOException, SQLException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileHash = sha256(input);
        CsvSheet sheet = csvAdapter.parse(input);
        int cellCount = sheet.rows().stream().mapToInt(List::size).sum();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                WorkspaceRepository repo = new WorkspaceRepository(connection);
                String now = Timestamps.now();
                String fileName = input.getFileName().toString();
                long sourceFileId = repo.insertSourceFile(mandateId,
                        fileName, fileHash, "fm_csv", now, PARSER_VERSION);

                int rowCount = sheet.rows().size();
                String metricsJson = IngestSummary.metricsJson(
                        fileName, fileHash, sheet.sheetName(), rowCount, cellCount);
                long parseRunId = repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                        CONFIG_HASH, now, Timestamps.now(), "success", metricsJson);
                long worksheetId = repo.insertWorksheet(parseRunId, sheet.sheetName(), 0);

                int rowNum = 1;
                for (List<String> row : sheet.rows()) {
                    int colNum = 1;
                    for (String field : row) {
                        boolean empty = field.isEmpty();
                        repo.insertCell(worksheetId, coord(rowNum, colNum), rowNum, colNum,
                                field, empty ? "empty" : "text", empty ? null : field);
                        colNum++;
                    }
                    rowNum++;
                }
                connection.commit();
                return new IngestSummary(fileName, fileHash, sheet.sheetName(), rowCount,
                        cellCount, sourceFileId, parseRunId, dbPath);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
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
