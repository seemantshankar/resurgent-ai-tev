package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;

/**
 * Resolves the identity anchor for an idempotent ingestion.
 *
 * <p>A {@code source_file} is unique by {@code (mandate_id, file_hash)}. A
 * {@code parse_run} is unique by
 * {@code (source_file_id, parser_version, config_hash)}. This class provides
 * lookup helpers so the caller can avoid re-parsing when an identical run
 * already exists, and a safe source-file creation helper that handles unique
 * constraint races.
 */
public final class ParseRunIdentity {

    private ParseRunIdentity() {
    }

    /**
     * Looks up an existing parse_run for the given intake identity.
     *
     * @return the existing {@code parse_run_id}, or {@code null} if no matching
     *         source_file or parse_run exists
     */
    public static Long findExistingParseRunId(long mandateId, String fileHash,
            String parserVersion, String configHash, WorkspaceRepository repo)
            throws SQLException {
        Long sourceFileId = repo.findSourceFileId(mandateId, fileHash);
        if (sourceFileId == null) {
            return null;
        }
        return repo.findParseRunId(sourceFileId, parserVersion, configHash);
    }

    /**
     * Finds or creates the source_file row keyed by {@code (mandate_id, file_hash)}.
     *
     * <p>If the row already exists it is reused. If a unique-constraint race is
     * lost, the method queries again instead of throwing.
     *
     * @return the source_file id, created or reused
     */
    public static long ensureSourceFile(long mandateId, String fileName, String fileHash,
            String fileType, String parserVersion, String rawMetadata, String ingestedAt,
            WorkspaceRepository repo) throws SQLException {
        Long sourceFileId = repo.findSourceFileId(mandateId, fileHash);
        if (sourceFileId != null) {
            return sourceFileId;
        }
        try {
            return repo.insertSourceFile(mandateId, fileName, fileHash,
                    fileType, ingestedAt, parserVersion, rawMetadata);
        } catch (SQLException e) {
            if (isUniqueConstraintViolation(e)) {
                sourceFileId = repo.findSourceFileId(mandateId, fileHash);
            }
            if (sourceFileId == null) {
                throw e;
            }
            return sourceFileId;
        }
    }

    private static boolean isUniqueConstraintViolation(SQLException e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message != null && message.contains("UNIQUE constraint failed")) {
            return true;
        }
        int code = e.getErrorCode();
        // SQLite SQLITE_CONSTRAINT = 19, SQLITE_CONSTRAINT_UNIQUE = 2067
        return code == 19 || code == 2067;
    }
}
