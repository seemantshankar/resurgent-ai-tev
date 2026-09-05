package com.resurgent.tev.parser.db;

import com.resurgent.tev.parser.ingest.NormalizedCell;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for FM Loader ingest and region discovery: source files, parse runs,
 * worksheets, cells, shared cell styles, reference edges, Candidates, workbook metadata,
 * provenance, and audit trail.
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

    public Long findSourceFileId(long mandateId, String fileHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT source_file_id FROM source_file WHERE mandate_id = ? AND file_hash = ?")) {
            ps.setLong(1, mandateId);
            ps.setString(2, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
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

    public Long findParseRunId(long sourceFileId, String parserVersion, String configHash)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id FROM parse_run WHERE source_file_id = ? AND parser_version = ?"
                        + " AND config_hash = ?")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, parserVersion);
            ps.setString(3, configHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
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

    public void updateParseRunResult(long parseRunId, String finishedAt, String status,
            String metrics) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE parse_run SET finished_at = ?, status = ?, metrics = ? WHERE parse_run_id = ?")) {
            ps.setString(1, finishedAt);
            ps.setString(2, status);
            ps.setString(3, metrics);
            ps.setLong(4, parseRunId);
            ps.executeUpdate();
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

    public long insertCell(long worksheetId, NormalizedCell cell) throws SQLException {
        return insertCell(worksheetId, cell, null, null);
    }

    public long insertCell(long worksheetId, NormalizedCell cell, Long styleId,
            String formulaNormalized) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell (worksheet_id, coord, row_num, col_num,"
                        + " raw_value, raw_type, value_type, text_value, display_value,"
                        + " numeric_value, bool_value, date_value,"
                        + " formula_text, formula_state, cached_value, cache_state, coerced_from_text,"
                        + " is_error, error_type, is_merged_anchor, is_merged_participant, merged_range,"
                        + " value_source, row_hidden, col_hidden, sheet_hidden,"
                        + " style_id, formula_normalized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
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
            ps.setString(14, cell.formulaState());
            ps.setString(15, cell.cachedValue());
            ps.setString(16, cell.cacheState());
            ps.setInt(17, cell.coercedFromText() ? 1 : 0);
            ps.setInt(18, cell.isError() ? 1 : 0);
            ps.setString(19, cell.errorType());
            ps.setInt(20, cell.isMergedAnchor() ? 1 : 0);
            ps.setInt(21, cell.isMergedParticipant() ? 1 : 0);
            ps.setString(22, cell.mergedRange());
            ps.setString(23, cell.valueSource());
            ps.setInt(24, cell.rowHidden() ? 1 : 0);
            ps.setInt(25, cell.colHidden() ? 1 : 0);
            ps.setInt(26, cell.sheetHidden() ? 1 : 0);
            setLong(ps, 27, styleId);
            ps.setString(28, formulaNormalized);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public Long selectCellStyleId(long cellId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT style_id FROM cell WHERE cell_id = ?")) {
            ps.setLong(1, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return getNullableLong(rs, "style_id");
            }
        }
    }

    public String selectFormulaNormalized(long cellId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT formula_normalized FROM cell WHERE cell_id = ?")) {
            ps.setLong(1, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("formula_normalized");
            }
        }
    }

    /**
     * Inserts a style row, or returns the existing flyweight id when identical
     * paint is already stored (ADR 0013 deduplication).
     */
    public long insertCellStyle(CellStyle style) throws SQLException {
        Long existing = findCellStyleId(style);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_style (is_bold, number_format, fill_fg_color, fill_pattern,"
                        + " border_top_style, border_top_color,"
                        + " border_right_style, border_right_color,"
                        + " border_bottom_style, border_bottom_color,"
                        + " border_left_style, border_left_color)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bindCellStyle(ps, style);
            ps.executeUpdate();
            return generatedId(ps);
        } catch (SQLException e) {
            // Concurrent identical insert races the unique index; reuse the winner.
            Long raced = findCellStyleId(style);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    public Long findCellStyleId(CellStyle style) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT style_id FROM cell_style WHERE"
                        + " ((is_bold IS NULL AND ? IS NULL) OR is_bold = ?)"
                        + " AND ((number_format IS NULL AND ? IS NULL) OR number_format = ?)"
                        + " AND ((fill_fg_color IS NULL AND ? IS NULL) OR fill_fg_color = ?)"
                        + " AND ((fill_pattern IS NULL AND ? IS NULL) OR fill_pattern = ?)"
                        + " AND ((border_top_style IS NULL AND ? IS NULL) OR border_top_style = ?)"
                        + " AND ((border_top_color IS NULL AND ? IS NULL) OR border_top_color = ?)"
                        + " AND ((border_right_style IS NULL AND ? IS NULL) OR border_right_style = ?)"
                        + " AND ((border_right_color IS NULL AND ? IS NULL) OR border_right_color = ?)"
                        + " AND ((border_bottom_style IS NULL AND ? IS NULL) OR border_bottom_style = ?)"
                        + " AND ((border_bottom_color IS NULL AND ? IS NULL) OR border_bottom_color = ?)"
                        + " AND ((border_left_style IS NULL AND ? IS NULL) OR border_left_style = ?)"
                        + " AND ((border_left_color IS NULL AND ? IS NULL) OR border_left_color = ?)")) {
            int i = 1;
            i = bindNullableBooleanPair(ps, i, style.isBold());
            i = bindNullableStringPair(ps, i, style.numberFormat());
            i = bindNullableStringPair(ps, i, style.fillFgColor());
            i = bindNullableStringPair(ps, i, style.fillPattern());
            i = bindNullableStringPair(ps, i, style.borderTopStyle());
            i = bindNullableStringPair(ps, i, style.borderTopColor());
            i = bindNullableStringPair(ps, i, style.borderRightStyle());
            i = bindNullableStringPair(ps, i, style.borderRightColor());
            i = bindNullableStringPair(ps, i, style.borderBottomStyle());
            i = bindNullableStringPair(ps, i, style.borderBottomColor());
            i = bindNullableStringPair(ps, i, style.borderLeftStyle());
            bindNullableStringPair(ps, i, style.borderLeftColor());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public CellStyle selectCellStyle(long styleId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT is_bold, number_format, fill_fg_color, fill_pattern,"
                        + " border_top_style, border_top_color,"
                        + " border_right_style, border_right_color,"
                        + " border_bottom_style, border_bottom_color,"
                        + " border_left_style, border_left_color"
                        + " FROM cell_style WHERE style_id = ?")) {
            ps.setLong(1, styleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CellStyle(
                        getNullableBoolean(rs, "is_bold"),
                        rs.getString("number_format"),
                        rs.getString("fill_fg_color"),
                        rs.getString("fill_pattern"),
                        rs.getString("border_top_style"),
                        rs.getString("border_top_color"),
                        rs.getString("border_right_style"),
                        rs.getString("border_right_color"),
                        rs.getString("border_bottom_style"),
                        rs.getString("border_bottom_color"),
                        rs.getString("border_left_style"),
                        rs.getString("border_left_color"));
            }
        }
    }

    public long insertCellReference(CellReferenceEdge edge) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_reference (from_cell_id, token_index, raw_token, ref_kind,"
                        + " target_sheet_name, target_worksheet_id, target_range, resolved_cell_id,"
                        + " external_link_id, abs_row, abs_col, row_offset, col_offset,"
                        + " is_whole_column, is_whole_row, unresolved_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, edge.fromCellId());
            ps.setInt(2, edge.tokenIndex());
            ps.setString(3, edge.rawToken());
            ps.setString(4, edge.refKind());
            ps.setString(5, edge.targetSheetName());
            setLong(ps, 6, edge.targetWorksheetId());
            ps.setString(7, edge.targetRange());
            setLong(ps, 8, edge.resolvedCellId());
            setLong(ps, 9, edge.externalLinkId());
            setBoolean(ps, 10, edge.absRow());
            setBoolean(ps, 11, edge.absCol());
            setInteger(ps, 12, edge.rowOffset());
            setInteger(ps, 13, edge.colOffset());
            ps.setInt(14, edge.isWholeColumn() ? 1 : 0);
            ps.setInt(15, edge.isWholeRow() ? 1 : 0);
            ps.setString(16, edge.unresolvedReason());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public CellReferenceEdge selectCellReference(long cellReferenceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT from_cell_id, token_index, raw_token, ref_kind,"
                        + " target_sheet_name, target_worksheet_id, target_range, resolved_cell_id,"
                        + " external_link_id, abs_row, abs_col, row_offset, col_offset,"
                        + " is_whole_column, is_whole_row, unresolved_reason"
                        + " FROM cell_reference WHERE cell_reference_id = ?")) {
            ps.setLong(1, cellReferenceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CellReferenceEdge(
                        rs.getLong("from_cell_id"),
                        rs.getInt("token_index"),
                        rs.getString("raw_token"),
                        rs.getString("ref_kind"),
                        rs.getString("target_sheet_name"),
                        getNullableLong(rs, "target_worksheet_id"),
                        rs.getString("target_range"),
                        getNullableLong(rs, "resolved_cell_id"),
                        getNullableLong(rs, "external_link_id"),
                        getNullableBoolean(rs, "abs_row"),
                        getNullableBoolean(rs, "abs_col"),
                        getNullableInteger(rs, "row_offset"),
                        getNullableInteger(rs, "col_offset"),
                        rs.getInt("is_whole_column") == 1,
                        rs.getInt("is_whole_row") == 1,
                        rs.getString("unresolved_reason"));
            }
        }
    }

    public void updateWorkbookCalcMetadata(long workbookId, String calcMode, Boolean fullCalcOnLoad,
            Boolean calcChainPresent, Boolean iterativeCalc, Integer iterativeCount,
            Integer errorCellCount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE workbook SET calculation_mode = ?, full_calc_on_load = ?, calc_chain_present = ?,"
                        + " iterative_calc = ?, iterative_count = ?, error_cell_count = ?"
                        + " WHERE workbook_id = ?")) {
            ps.setString(1, calcMode);
            setBoolean(ps, 2, fullCalcOnLoad);
            setBoolean(ps, 3, calcChainPresent);
            setBoolean(ps, 4, iterativeCalc);
            setInteger(ps, 5, iterativeCount);
            setInteger(ps, 6, errorCellCount);
            ps.setLong(7, workbookId);
            ps.executeUpdate();
        }
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

    public long insertAuditLog(Long parseRunId, String eventType, String eventAt,
            String payload, String severity) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (parse_run_id, event_type, event_at, payload, severity)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (parseRunId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setLong(1, parseRunId);
            }
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
                        + " is_escalated, created_at, resolved_at, subject_kind, subject_key, confidence)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setString(2, category);
            ps.setString(3, summary);
            ps.setString(4, detail);
            ps.setString(5, status);
            ps.setInt(6, isEscalated ? 1 : 0);
            ps.setString(7, createdAt);
            ps.setString(8, resolvedAt);
            ps.setNull(9, java.sql.Types.VARCHAR);
            ps.setNull(10, java.sql.Types.VARCHAR);
            ps.setNull(11, java.sql.Types.REAL);
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

    public long countCellStyles() throws SQLException {
        return count("SELECT COUNT(*) FROM cell_style");
    }

    public long countCellReferences() throws SQLException {
        return count("SELECT COUNT(*) FROM cell_reference");
    }

    public long insertCandidate(CandidateWrite write, List<Long> memberCellIds) throws SQLException {
        long candidateId;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO candidate (parse_run_id, worksheet_id, candidate_kind,"
                        + " parent_candidate_id, bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,"
                        + " internal_whitespace, anchors, structural_signatures,"
                        + " isolated_hidden_worksheet, structural_confidence,"
                        + " structural_confidence_rationale, explanation, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, write.parseRunId());
            ps.setLong(2, write.worksheetId());
            ps.setString(3, write.candidateKind());
            setLong(ps, 4, write.parentCandidateId());
            setInteger(ps, 5, write.bboxMinRow());
            setInteger(ps, 6, write.bboxMinCol());
            setInteger(ps, 7, write.bboxMaxRow());
            setInteger(ps, 8, write.bboxMaxCol());
            ps.setString(9, write.internalWhitespaceJson());
            ps.setString(10, write.anchorsJson());
            ps.setString(11, write.structuralSignaturesJson());
            ps.setInt(12, write.isolatedHiddenWorksheet() ? 1 : 0);
            if (write.structuralConfidence() == null) {
                ps.setNull(13, java.sql.Types.REAL);
            } else {
                ps.setDouble(13, write.structuralConfidence());
            }
            ps.setString(14, write.structuralConfidenceRationale());
            ps.setString(15, write.explanation());
            ps.setString(16, Timestamps.now());
            ps.executeUpdate();
            candidateId = generatedId(ps);
        }
        insertCandidateMembers(candidateId, memberCellIds);
        return candidateId;
    }

    public void deleteCandidatesForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM candidate WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public void insertCandidateRelated(
            long candidateId, long relatedCandidateId, String relationshipKind)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO candidate_related (candidate_id, related_candidate_id, relationship_kind)"
                        + " VALUES (?, ?, ?)")) {
            ps.setLong(1, candidateId);
            ps.setLong(2, relatedCandidateId);
            ps.setString(3, relationshipKind);
            ps.executeUpdate();
        }
    }

    /** Directed related edges for Candidates belonging to the parse run. */
    public List<long[]> selectCandidateRelatedForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cr.candidate_id, cr.related_candidate_id"
                        + " FROM candidate_related cr"
                        + " JOIN candidate c ON c.candidate_id = cr.candidate_id"
                        + " WHERE c.parse_run_id = ?"
                        + " ORDER BY cr.candidate_id, cr.related_candidate_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<long[]> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new long[] {rs.getLong(1), rs.getLong(2)});
                }
                return rows;
            }
        }
    }

    public List<CellReferenceEdge> selectCellReferencesForParseRun(long parseRunId)
            throws SQLException {
        return selectPersistedCellReferencesForParseRun(parseRunId).stream()
                .map(PersistedCellReference::edge)
                .toList();
    }

    public List<PersistedCellReference> selectPersistedCellReferencesForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cr.cell_reference_id, cr.from_cell_id, cr.token_index, cr.raw_token, cr.ref_kind,"
                        + " cr.target_sheet_name, cr.target_worksheet_id, cr.target_range,"
                        + " cr.resolved_cell_id, cr.external_link_id, cr.abs_row, cr.abs_col,"
                        + " cr.row_offset, cr.col_offset, cr.is_whole_column, cr.is_whole_row,"
                        + " cr.unresolved_reason"
                        + " FROM cell_reference cr"
                        + " JOIN cell from_cell ON from_cell.cell_id = cr.from_cell_id"
                        + " JOIN worksheet ws ON ws.worksheet_id = from_cell.worksheet_id"
                        + " WHERE ws.parse_run_id = ?"
                        + " ORDER BY cr.from_cell_id, cr.token_index")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PersistedCellReference> rows = new ArrayList<>();
                while (rs.next()) {
                    CellReferenceEdge edge = new CellReferenceEdge(
                            rs.getLong("from_cell_id"),
                            rs.getInt("token_index"),
                            rs.getString("raw_token"),
                            rs.getString("ref_kind"),
                            rs.getString("target_sheet_name"),
                            getNullableLong(rs, "target_worksheet_id"),
                            rs.getString("target_range"),
                            getNullableLong(rs, "resolved_cell_id"),
                            getNullableLong(rs, "external_link_id"),
                            getNullableBoolean(rs, "abs_row"),
                            getNullableBoolean(rs, "abs_col"),
                            getNullableInteger(rs, "row_offset"),
                            getNullableInteger(rs, "col_offset"),
                            rs.getInt("is_whole_column") == 1,
                            rs.getInt("is_whole_row") == 1,
                            rs.getString("unresolved_reason"));
                    rows.add(new PersistedCellReference(rs.getLong("cell_reference_id"), edge));
                }
                return rows;
            }
        }
    }

    public List<CellPacketView> selectCellPacketViews(List<Long> cellIds) throws SQLException {
        if (cellIds == null || cellIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < cellIds.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        String sql = "SELECT cell_id, worksheet_id, coord, row_num, col_num, value_type,"
                + " text_value, display_value, numeric_value, formula_text,"
                + " row_hidden, col_hidden"
                + " FROM cell WHERE cell_id IN (" + placeholders + ")"
                + " ORDER BY row_num, col_num, cell_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < cellIds.size(); i++) {
                ps.setLong(i + 1, cellIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<CellPacketView> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CellPacketView(
                            rs.getLong("cell_id"),
                            rs.getLong("worksheet_id"),
                            rs.getString("coord"),
                            rs.getInt("row_num"),
                            rs.getInt("col_num"),
                            rs.getString("value_type"),
                            rs.getString("text_value"),
                            rs.getString("display_value"),
                            rs.getString("numeric_value"),
                            rs.getString("formula_text"),
                            rs.getInt("row_hidden") == 1,
                            rs.getInt("col_hidden") == 1));
                }
                return rows;
            }
        }
    }

    /**
     * Persisted cells on a worksheet whose coordinates fall inside an A1-style range
     * (e.g. {@code A1}, {@code B2:D10}). Whole-column/row refs return all cells on that axis.
     */
    public List<CellPacketView> selectCellsInTargetRange(long worksheetId, String targetRange)
            throws SQLException {
        if (targetRange == null || targetRange.isBlank()) {
            return List.of();
        }
        List<CellPacketView> all = selectCellPacketViewsForWorksheet(worksheetId);
        A1Range bounds = A1Range.parse(targetRange.trim());
        if (bounds == null) {
            return List.of();
        }
        List<CellPacketView> out = new ArrayList<>();
        for (CellPacketView cell : all) {
            if (bounds.contains(cell.rowNum(), cell.colNum())) {
                out.add(cell);
            }
        }
        return out;
    }

    public List<CellPacketView> selectCellPacketViewsForWorksheet(long worksheetId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id, worksheet_id, coord, row_num, col_num, value_type,"
                        + " text_value, display_value, numeric_value, formula_text,"
                        + " row_hidden, col_hidden"
                        + " FROM cell WHERE worksheet_id = ?"
                        + " ORDER BY row_num, col_num, cell_id")) {
            ps.setLong(1, worksheetId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CellPacketView> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CellPacketView(
                            rs.getLong("cell_id"),
                            rs.getLong("worksheet_id"),
                            rs.getString("coord"),
                            rs.getInt("row_num"),
                            rs.getInt("col_num"),
                            rs.getString("value_type"),
                            rs.getString("text_value"),
                            rs.getString("display_value"),
                            rs.getString("numeric_value"),
                            rs.getString("formula_text"),
                            rs.getInt("row_hidden") == 1,
                            rs.getInt("col_hidden") == 1));
                }
                return rows;
            }
        }
    }

    public void replaceCandidatesForParseRun(long parseRunId, List<CandidateWithMembers> batch)
            throws SQLException {
        deleteCandidatesForParseRun(parseRunId);
        for (CandidateWithMembers item : batch) {
            insertCandidate(item.write(), item.memberCellIds());
        }
    }

    public CandidateRow selectCandidate(long candidateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT candidate_id, parse_run_id, worksheet_id, candidate_kind,"
                        + " parent_candidate_id, bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,"
                        + " internal_whitespace, anchors, structural_signatures,"
                        + " isolated_hidden_worksheet, structural_confidence,"
                        + " structural_confidence_rationale, explanation, created_at"
                        + " FROM candidate WHERE candidate_id = ?")) {
            ps.setLong(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("candidate not found: " + candidateId);
                }
                return mapCandidateRow(rs);
            }
        }
    }

    public List<CandidateRow> selectCandidatesForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT candidate_id, parse_run_id, worksheet_id, candidate_kind,"
                        + " parent_candidate_id, bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,"
                        + " internal_whitespace, anchors, structural_signatures,"
                        + " isolated_hidden_worksheet, structural_confidence,"
                        + " structural_confidence_rationale, explanation, created_at"
                        + " FROM candidate WHERE parse_run_id = ?"
                        + " ORDER BY worksheet_id, candidate_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CandidateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapCandidateRow(rs));
                }
                return rows;
            }
        }
    }

    public List<Long> selectCandidateMemberCellIds(long candidateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id FROM candidate_member WHERE candidate_id = ?"
                        + " ORDER BY cell_id")) {
            ps.setLong(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    public long countCandidatesForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM candidate WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public boolean parseRunExists(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM parse_run WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<WorksheetRef> selectWorksheetsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT worksheet_id, sheet_name, sheet_index, sheet_state"
                        + " FROM worksheet WHERE parse_run_id = ?"
                        + " ORDER BY sheet_index, worksheet_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<WorksheetRef> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new WorksheetRef(
                            rs.getLong("worksheet_id"),
                            rs.getString("sheet_name"),
                            rs.getInt("sheet_index"),
                            rs.getString("sheet_state")));
                }
                return rows;
            }
        }
    }

    public List<CellCoordRef> selectCellsForWorksheet(long worksheetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id, coord, row_num, col_num FROM cell"
                        + " WHERE worksheet_id = ?"
                        + " ORDER BY row_num, col_num, cell_id")) {
            ps.setLong(1, worksheetId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CellCoordRef> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CellCoordRef(
                            rs.getLong("cell_id"),
                            rs.getString("coord"),
                            rs.getInt("row_num"),
                            rs.getInt("col_num")));
                }
                return rows;
            }
        }
    }

    /** Bulk cell evidence for one worksheet’s discover pass (signatures / membership). */
    public List<CellEvidence> selectCellEvidenceForWorksheet(long worksheetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id, coord, row_num, col_num, value_type, style_id,"
                        + " is_merged_anchor, is_merged_participant, merged_range"
                        + " FROM cell WHERE worksheet_id = ?"
                        + " ORDER BY row_num, col_num, cell_id")) {
            ps.setLong(1, worksheetId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CellEvidence> rows = new ArrayList<>();
                while (rs.next()) {
                    long styleId = rs.getLong("style_id");
                    Long style = rs.wasNull() ? null : styleId;
                    rows.add(new CellEvidence(
                            rs.getLong("cell_id"),
                            rs.getString("coord"),
                            rs.getInt("row_num"),
                            rs.getInt("col_num"),
                            rs.getString("value_type"),
                            style,
                            rs.getInt("is_merged_anchor") == 1,
                            rs.getInt("is_merged_participant") == 1,
                            rs.getString("merged_range")));
                }
                return rows;
            }
        }
    }

    /**
     * True when any cell_reference edge links this worksheet to a different visible worksheet
     * (or vice versa) in the same parse run.
     */
    public boolean worksheetHasEdgeToOrFromVisibleSheet(long parseRunId, long worksheetId)
            throws SQLException {
        String sql = "SELECT 1"
                + " FROM cell_reference cr"
                + " JOIN cell from_cell ON from_cell.cell_id = cr.from_cell_id"
                + " JOIN worksheet from_ws ON from_ws.worksheet_id = from_cell.worksheet_id"
                + " LEFT JOIN cell resolved ON resolved.cell_id = cr.resolved_cell_id"
                + " LEFT JOIN worksheet target_ws ON target_ws.worksheet_id = COALESCE("
                + " cr.target_worksheet_id, resolved.worksheet_id)"
                + " WHERE from_ws.parse_run_id = ?"
                + " AND ("
                + " (from_ws.worksheet_id = ?"
                + " AND target_ws.worksheet_id IS NOT NULL"
                + " AND target_ws.worksheet_id != ?"
                + " AND COALESCE(target_ws.sheet_state, 'visible') = 'visible')"
                + " OR"
                + " (target_ws.worksheet_id = ?"
                + " AND from_ws.worksheet_id != ?"
                + " AND COALESCE(from_ws.sheet_state, 'visible') = 'visible')"
                + " )"
                + " LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, worksheetId);
            ps.setLong(3, worksheetId);
            ps.setLong(4, worksheetId);
            ps.setLong(5, worksheetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertCandidateMembers(long candidateId, List<Long> memberCellIds)
            throws SQLException {
        if (memberCellIds == null || memberCellIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO candidate_member (candidate_id, cell_id) VALUES (?, ?)")) {
            for (Long cellId : memberCellIds) {
                ps.setLong(1, candidateId);
                ps.setLong(2, cellId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static CandidateRow mapCandidateRow(ResultSet rs) throws SQLException {
        double confidence = rs.getDouble("structural_confidence");
        Double structuralConfidence = rs.wasNull() ? null : confidence;
        return new CandidateRow(
                rs.getLong("candidate_id"),
                rs.getLong("parse_run_id"),
                rs.getLong("worksheet_id"),
                rs.getString("candidate_kind"),
                getNullableLong(rs, "parent_candidate_id"),
                getNullableInteger(rs, "bbox_min_row"),
                getNullableInteger(rs, "bbox_min_col"),
                getNullableInteger(rs, "bbox_max_row"),
                getNullableInteger(rs, "bbox_max_col"),
                rs.getString("internal_whitespace"),
                rs.getString("anchors"),
                rs.getString("structural_signatures"),
                rs.getInt("isolated_hidden_worksheet") == 1,
                structuralConfidence,
                rs.getString("structural_confidence_rationale"),
                rs.getString("explanation"),
                rs.getString("created_at"));
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setBoolean(PreparedStatement ps, int index, Boolean value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value ? 1 : 0);
        }
    }

    private static void bindCellStyle(PreparedStatement ps, CellStyle style) throws SQLException {
        setBoolean(ps, 1, style.isBold());
        ps.setString(2, style.numberFormat());
        ps.setString(3, style.fillFgColor());
        ps.setString(4, style.fillPattern());
        ps.setString(5, style.borderTopStyle());
        ps.setString(6, style.borderTopColor());
        ps.setString(7, style.borderRightStyle());
        ps.setString(8, style.borderRightColor());
        ps.setString(9, style.borderBottomStyle());
        ps.setString(10, style.borderBottomColor());
        ps.setString(11, style.borderLeftStyle());
        ps.setString(12, style.borderLeftColor());
    }

    private static int bindNullableBooleanPair(PreparedStatement ps, int index, Boolean value)
            throws SQLException {
        setBoolean(ps, index, value);
        setBoolean(ps, index + 1, value);
        return index + 2;
    }

    private static int bindNullableStringPair(PreparedStatement ps, int index, String value)
            throws SQLException {
        ps.setString(index, value);
        ps.setString(index + 1, value);
        return index + 2;
    }

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
