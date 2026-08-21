package com.resurgent.tev.parser.db;

import com.resurgent.tev.parser.ingest.NormalizedCell;
import com.resurgent.tev.parser.ingest.ParsedQuantity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin hand-written repository for the workspace schema (ADR 0002): plain JDBC,
 * prepared statements exclusively, no business logic.
 */
public final class WorkspaceRepository {

    private final Connection connection;

    public WorkspaceRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public void commit() throws SQLException {
        connection.commit();
    }

    public void rollback() throws SQLException {
        connection.rollback();
    }

    public long insertSourceFile(long mandateId, String fileName, String fileHash,
            String fileType, String ingestedAt, String parserVersion, String rawMetadata)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_file (mandate_id, file_name, file_hash, file_type,"
                        + " ingested_at, parser_version, raw_metadata) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, fileName);
            ps.setString(3, fileHash);
            ps.setString(4, fileType);
            ps.setString(5, ingestedAt);
            ps.setString(6, parserVersion);
            ps.setString(7, rawMetadata);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertParseRun(long sourceFileId, long mandateId, String parserVersion,
            String configHash, String startedAt, String finishedAt, String status, String metrics)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO parse_run (source_file_id, mandate_id, parser_version, config_hash,"
                        + " started_at, finished_at, status, metrics)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setLong(2, mandateId);
            ps.setString(3, parserVersion);
            ps.setString(4, configHash);
            ps.setString(5, startedAt);
            ps.setString(6, finishedAt);
            ps.setString(7, status);
            ps.setString(8, metrics);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertWorksheet(long parseRunId, String sheetName, int sheetIndex) throws SQLException {
        return insertWorksheet(parseRunId, sheetName, sheetIndex, null);
    }

    public long insertWorksheet(long parseRunId, String sheetName, int sheetIndex,
            String sheetState) throws SQLException {
        return insertWorksheet(parseRunId, sheetName, sheetIndex, sheetState,
                null, null, null, null, null, null, null);
    }

    public long insertWorksheet(long parseRunId, String sheetName, int sheetIndex,
            String sheetState, Integer bboxMinRow, Integer bboxMinCol,
            Integer bboxMaxRow, Integer bboxMaxCol, String dimensionsDeclared,
            Integer realContentRows, Integer declaredMerged) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO worksheet (parse_run_id, sheet_name, sheet_index, sheet_state,"
                        + " bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,"
                        + " dimensions_declared, real_content_rows, declared_merged)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setString(2, sheetName);
            ps.setInt(3, sheetIndex);
            ps.setString(4, sheetState);
            setInteger(ps, 5, bboxMinRow);
            setInteger(ps, 6, bboxMinCol);
            setInteger(ps, 7, bboxMaxRow);
            setInteger(ps, 8, bboxMaxCol);
            ps.setString(9, dimensionsDeclared);
            setInteger(ps, 10, realContentRows);
            setInteger(ps, 11, declaredMerged);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    public void insertCell(long worksheetId, NormalizedCell cell) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell (worksheet_id, coord, row_num, col_num,"
                        + " raw_value, raw_type, value_type, text_value, display_value,"
                        + " numeric_value, bool_value, date_value,"
                        + " formula_text, formula_normalized, formula_state,"
                        + " cached_value, cache_state, coerced_from_text, parsed_quantity,"
                        + " is_error, error_type, row_label, col_label,"
                        + " is_merged_anchor, is_merged_participant, merged_range, value_source,"
                        + " row_hidden, col_hidden, sheet_hidden,"
                        + " external_ref, external_link_id, sheet_refs, defined_name_refs)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, worksheetId);
            ps.setString(2, cell.coord());
            ps.setInt(3, cell.rowNum());
            ps.setInt(4, cell.colNum());
            ps.setString(5, cell.rawValue());
            ps.setString(6, cell.rawType());
            ps.setString(7, cell.valueType());
            ps.setString(8, cell.textValue());
            ps.setString(9, cell.displayValue());
            ps.setString(10, cell.numericValue() == null ? null : cell.numericValue().toPlainString());
            if (cell.boolValue() == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setBoolean(11, cell.boolValue());
            }
            ps.setString(12, cell.dateValue() == null ? null : cell.dateValue().toString());
            ps.setString(13, cell.formulaText());
            ps.setString(14, cell.formulaNormalized());
            ps.setString(15, cell.formulaState());
            ps.setString(16, cell.cachedValue());
            ps.setString(17, cell.cacheState());
            ps.setInt(18, cell.coercedFromText() ? 1 : 0);
            ps.setString(19, quantityJson(cell.parsedQuantity()));
            ps.setInt(20, cell.isError() ? 1 : 0);
            ps.setString(21, cell.errorType());
            ps.setString(22, cell.rowLabel());
            ps.setString(23, cell.colLabel());
            ps.setInt(24, cell.isMergedAnchor() ? 1 : 0);
            ps.setInt(25, cell.isMergedParticipant() ? 1 : 0);
            ps.setString(26, cell.mergedRange());
            ps.setString(27, cell.valueSource());
            ps.setInt(28, cell.rowHidden() ? 1 : 0);
            ps.setInt(29, cell.colHidden() ? 1 : 0);
            ps.setInt(30, cell.sheetHidden() ? 1 : 0);
            ps.setString(31, cell.externalRef());
            if (cell.externalLinkId() == null) {
                ps.setNull(32, java.sql.Types.INTEGER);
            } else {
                ps.setLong(32, cell.externalLinkId());
            }
            ps.setString(33, cell.sheetRefs());
            ps.setString(34, cell.definedNameRefs());
            ps.executeUpdate();
        }
    }

    private static String quantityJson(ParsedQuantity quantity) {
        return quantity == null ? null : quantity.toJson();
    }

    public long insertWorkbook(long sourceFileId, String applicationName, String applicationVersion,
            int sheetCount, String sheetNames, String definedNames, String properties,
            boolean isProtected, String createdAt, String modifiedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO workbook (source_file_id, application_name, application_version,"
                        + " sheet_count, sheet_names, defined_names, properties, is_protected,"
                        + " created_at, modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, applicationName);
            ps.setString(3, applicationVersion);
            ps.setInt(4, sheetCount);
            ps.setString(5, sheetNames);
            ps.setString(6, definedNames);
            ps.setString(7, properties);
            ps.setInt(8, isProtected ? 1 : 0);
            ps.setString(9, createdAt);
            ps.setString(10, modifiedAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertExternalLink(long workbookId, String linkType, Integer linkIndex,
            String targetPath, String status, String checkedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO external_link (workbook_id, link_type, link_index, target_path, status, checked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, workbookId);
            ps.setString(2, linkType);
            setInteger(ps, 3, linkIndex);
            ps.setString(4, targetPath);
            ps.setString(5, status);
            ps.setString(6, checkedAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertProvenance(String entityType, long entityId, long sourceFileId,
            Long parseRunId, String location, String rawValue, double confidence,
            boolean isDerived, String notes) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO provenance (entity_type, entity_id, source_file_id, parse_run_id,"
                        + " location, raw_value, confidence, is_derived, notes)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entityType);
            ps.setLong(2, entityId);
            ps.setLong(3, sourceFileId);
            if (parseRunId == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, parseRunId);
            }
            ps.setString(5, location);
            ps.setString(6, rawValue);
            ps.setDouble(7, confidence);
            ps.setInt(8, isDerived ? 1 : 0);
            ps.setString(9, notes);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertAuditLog(long parseRunId, String eventType, String eventAt,
            String payload, String severity) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (parse_run_id, event_type, event_at, payload, severity)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setString(2, eventType);
            ps.setString(3, eventAt);
            ps.setString(4, payload);
            ps.setString(5, severity);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertReviewQueue(long parseRunId, String category, String summary,
            String detail, String status, boolean isEscalated, String createdAt,
            String resolvedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO review_queue (parse_run_id, category, summary, detail, status,"
                        + " is_escalated, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setString(2, category);
            ps.setString(3, summary);
            ps.setString(4, detail);
            ps.setString(5, status);
            ps.setInt(6, isEscalated ? 1 : 0);
            ps.setString(7, createdAt);
            ps.setString(8, resolvedAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertIngestRejection(Long sourceFileId, long mandateId, String fileName,
            String fileHash, String reason, String detail, String rejectedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ingest_rejection (source_file_id, mandate_id, file_name, file_hash,"
                        + " reason, detail, rejected_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (sourceFileId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setLong(1, sourceFileId);
            }
            ps.setLong(2, mandateId);
            ps.setString(3, fileName);
            ps.setString(4, fileHash);
            ps.setString(5, reason);
            ps.setString(6, detail);
            ps.setString(7, rejectedAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public String selectWorkbookSheetNames(long workbookId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sheet_names FROM workbook WHERE workbook_id = ?")) {
            ps.setLong(1, workbookId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public String selectWorkbookProperties(long workbookId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT properties FROM workbook WHERE workbook_id = ?")) {
            ps.setLong(1, workbookId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public boolean selectWorkbookIsProtected(long workbookId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT is_protected FROM workbook WHERE workbook_id = ?")) {
            ps.setLong(1, workbookId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    public String selectAuditLogPayload(long auditLogId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT payload FROM audit_log WHERE audit_log_id = ?")) {
            ps.setLong(1, auditLogId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public String selectReviewQueueDetail(long reviewQueueId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT detail FROM review_queue WHERE review_queue_id = ?")) {
            ps.setLong(1, reviewQueueId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public String selectIngestRejectionDetail(long ingestRejectionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT detail FROM ingest_rejection WHERE ingest_rejection_id = ?")) {
            ps.setLong(1, ingestRejectionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public String selectParseRunMetrics(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT metrics FROM parse_run WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public long countSourceFiles() throws SQLException {
        return count("SELECT COUNT(*) FROM source_file");
    }

    public long countParseRuns() throws SQLException {
        return count("SELECT COUNT(*) FROM parse_run");
    }

    public long countWorksheets() throws SQLException {
        return count("SELECT COUNT(*) FROM worksheet");
    }

    public long countCells() throws SQLException {
        return count("SELECT COUNT(*) FROM cell");
    }

    public long countWorkbooks() throws SQLException {
        return count("SELECT COUNT(*) FROM workbook");
    }

    public long countExternalLinks() throws SQLException {
        return count("SELECT COUNT(*) FROM external_link");
    }

    public long countProvenance() throws SQLException {
        return count("SELECT COUNT(*) FROM provenance");
    }

    public long countAuditLogs() throws SQLException {
        return count("SELECT COUNT(*) FROM audit_log");
    }

    public long countReviewQueue() throws SQLException {
        return count("SELECT COUNT(*) FROM review_queue");
    }

    public long countIngestRejections() throws SQLException {
        return count("SELECT COUNT(*) FROM ingest_rejection");
    }

    private long count(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }
}
