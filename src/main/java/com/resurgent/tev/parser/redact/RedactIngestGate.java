package com.resurgent.tev.parser.redact;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.IngestService;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Ensures a workbook is ingested before redaction proceeds. */
final class RedactIngestGate {

    private RedactIngestGate() {
    }

    /**
     * @return {@code true} when a new ingest was run; {@code false} when already present
     */
    static boolean ensureIngested(Path input, long mandateId, Path dbPath, String fileHash,
            ParserConfig config, WorkspaceDatabase.OpenOptions openOptions)
            throws IOException, SQLException {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath, openOptions)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (repo.findSourceFileId(mandateId, fileHash) != null) {
                return false;
            }
        }
        ParserConfig ingestConfig = ingestConfigFor(input, config);
        new IngestService().ingest(input, mandateId, dbPath, ingestConfig, openOptions);
        return true;
    }

    private static ParserConfig ingestConfigFor(Path input, ParserConfig config) {
        String lower = input.getFileName().toString().toLowerCase();
        if (lower.endsWith(".xls") && !lower.endsWith(".xlsx") && !config.xlsEnabled()) {
            return config.withXlsEnabled(true);
        }
        return config;
    }
}
