package com.resurgent.tev.parser.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.classify.BindingPeer;
import com.resurgent.tev.parser.classify.CellInterpretation;
import com.resurgent.tev.parser.classify.FormulaAnnotation;
import com.resurgent.tev.parser.classify.FormulaAnnotationMember;
import com.resurgent.tev.parser.classify.InterpretationEvidence;
import com.resurgent.tev.parser.classify.NomenclatureBinding;
import com.resurgent.tev.parser.classify.PacketDisposition;
import com.resurgent.tev.parser.classify.ProjectFactBinding;
import com.resurgent.tev.parser.nomenclature.ProjectFactField;
import com.resurgent.tev.parser.ingest.NormalizedCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for FM Loader ingest, region discovery, the nomenclature
 * catalog, Layer A Packet dispositions, Layer B bindings, and Cell interpretations.
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

    public long selectParseRunMandateId(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mandate_id FROM parse_run WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("parse run not found: " + parseRunId);
                }
                return rs.getLong(1);
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
                "SELECT c.cell_id, c.coord, c.row_num, c.col_num, c.value_type, c.style_id,"
                        + " s.is_bold,"
                        + " c.is_merged_anchor, c.is_merged_participant, c.merged_range"
                        + " FROM cell c"
                        + " LEFT JOIN cell_style s ON s.style_id = c.style_id"
                        + " WHERE c.worksheet_id = ?"
                        + " ORDER BY c.row_num, c.col_num, c.cell_id")) {
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
                            getNullableBoolean(rs, "is_bold"),
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

    public long insertNomenclatureNode(NomenclatureNode node, String createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO nomenclature_node (path, name, parent_path, layer, frozen, leaf,"
                        + " industry_tag, mandate_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, node.path());
            ps.setString(2, node.name());
            ps.setString(3, node.parentPath());
            ps.setString(4, node.layer());
            ps.setInt(5, node.frozen() ? 1 : 0);
            ps.setInt(6, node.leaf() ? 1 : 0);
            ps.setString(7, node.industryTag());
            setLong(ps, 8, node.mandateId());
            ps.setString(9, createdAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long countNomenclatureSpine() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM nomenclature_node WHERE layer = 'spine'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public List<NomenclatureNode> selectNomenclatureSpine() throws SQLException {
        return selectNomenclatureByLayer("spine", null);
    }

    public long countNomenclatureIndustry(String industryTag) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM nomenclature_node WHERE layer = 'industry' AND industry_tag = ?")) {
            ps.setString(1, industryTag);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<NomenclatureNode> selectNomenclatureIndustry(String industryTag) throws SQLException {
        return selectNomenclatureByLayer("industry", industryTag);
    }

    public List<NomenclatureNode> selectNomenclatureOverlay(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT path, name, parent_path, layer, frozen, leaf, industry_tag, mandate_id"
                        + " FROM nomenclature_node WHERE layer = 'mandate_soft' AND mandate_id = ?"
                        + " ORDER BY node_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<NomenclatureNode> nodes = new ArrayList<>();
                while (rs.next()) {
                    nodes.add(readNomenclatureNode(rs));
                }
                return nodes;
            }
        }
    }

    public long insertNomenclatureAlias(NomenclatureAlias alias, String layer, String industryTag,
            Long mandateId, String createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO nomenclature_alias (alias_text, leaf_path, layer, industry_tag,"
                        + " mandate_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, alias.aliasText());
            ps.setString(2, alias.leafPath());
            ps.setString(3, layer);
            ps.setString(4, industryTag);
            setLong(ps, 5, mandateId);
            ps.setString(6, createdAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public List<NomenclatureAlias> selectNomenclatureAliases(String layer, String industryTag,
            Long mandateId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT alias_text, leaf_path FROM nomenclature_alias WHERE layer = ?");
        if (mandateId != null) {
            sql.append(" AND mandate_id = ?");
        } else {
            sql.append(" AND mandate_id IS NULL");
            if (industryTag != null) {
                sql.append(" AND industry_tag = ?");
            }
        }
        sql.append(" ORDER BY alias_id");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.setString(1, layer);
            if (mandateId != null) {
                ps.setLong(2, mandateId);
            } else if (industryTag != null) {
                ps.setString(2, industryTag);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<NomenclatureAlias> aliases = new ArrayList<>();
                while (rs.next()) {
                    aliases.add(new NomenclatureAlias(
                            rs.getString("alias_text"), rs.getString("leaf_path")));
                }
                return aliases;
            }
        }
    }

    public void upsertMandateIndustry(long mandateId, String industryTag, boolean confirmed,
            boolean inferred) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mandate_industry (mandate_id, industry_tag, confirmed, inferred, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (mandate_id) DO UPDATE SET industry_tag = excluded.industry_tag,"
                        + " confirmed = excluded.confirmed, inferred = excluded.inferred,"
                        + " updated_at = excluded.updated_at")) {
            ps.setLong(1, mandateId);
            ps.setString(2, industryTag);
            ps.setInt(3, confirmed ? 1 : 0);
            ps.setInt(4, inferred ? 1 : 0);
            ps.setString(5, Timestamps.now());
            ps.executeUpdate();
        }
    }

    public IndustryResolution selectMandateIndustry(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT industry_tag, confirmed, inferred FROM mandate_industry WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean confirmed = rs.getInt("confirmed") == 1;
                boolean inferred = rs.getInt("inferred") == 1;
                return new IndustryResolution(
                        rs.getString("industry_tag"), confirmed, inferred);
            }
        }
    }

    public void deletePacketDispositionsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM packet_disposition WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public long insertPacketDisposition(PacketDisposition row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO packet_disposition (parse_run_id, candidate_id, schedule_family,"
                        + " triage, relevance, row_labels, column_headers, packet_default_head,"
                        + " parent_candidate_id, cheap_pass, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.candidateId());
            ps.setString(3, row.scheduleFamily());
            ps.setString(4, row.triage());
            ps.setString(5, row.relevance());
            ps.setString(6, jsonList(row.rowLabels()));
            ps.setString(7, jsonList(row.columnHeaders()));
            ps.setString(8, row.packetDefaultHead());
            setLong(ps, 9, row.parentCandidateId());
            ps.setInt(10, row.cheapPass() ? 1 : 0);
            ps.setString(11, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public List<PacketDisposition> selectPacketDispositionsForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT candidate_id, parse_run_id, schedule_family, triage, relevance,"
                        + " row_labels, column_headers, packet_default_head, parent_candidate_id,"
                        + " cheap_pass FROM packet_disposition WHERE parse_run_id = ?"
                        + " ORDER BY disposition_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PacketDisposition> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new PacketDisposition(
                            rs.getLong("candidate_id"),
                            rs.getLong("parse_run_id"),
                            rs.getString("schedule_family"),
                            rs.getString("triage"),
                            rs.getString("relevance"),
                            readStringList(rs.getString("row_labels")),
                            readStringList(rs.getString("column_headers")),
                            rs.getString("packet_default_head"),
                            getNullableLong(rs, "parent_candidate_id"),
                            rs.getInt("cheap_pass") == 1));
                }
                return rows;
            }
        }
    }

    public void deleteNomenclatureBindingsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM nomenclature_binding WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public long insertNomenclatureBinding(NomenclatureBinding row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO nomenclature_binding (parse_run_id, candidate_id, cell_id, verbatim,"
                        + " path, amount_role, soft_leaf, via_alias, confidence, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.candidateId());
            ps.setLong(3, row.cellId());
            ps.setString(4, row.verbatim());
            ps.setString(5, row.path());
            ps.setString(6, row.amountRole());
            ps.setInt(7, row.softLeaf() ? 1 : 0);
            ps.setInt(8, row.viaAlias() ? 1 : 0);
            if (row.confidence() == null) {
                ps.setNull(9, Types.REAL);
            } else {
                ps.setDouble(9, row.confidence());
            }
            ps.setString(10, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public List<NomenclatureBinding> selectNomenclatureBindingsForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id, parse_run_id, candidate_id, verbatim, path, amount_role,"
                        + " soft_leaf, via_alias, confidence FROM nomenclature_binding"
                        + " WHERE parse_run_id = ? ORDER BY binding_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<NomenclatureBinding> rows = new ArrayList<>();
                while (rs.next()) {
                    double confidence = rs.getDouble("confidence");
                    Double confidenceValue = rs.wasNull() ? null : confidence;
                    rows.add(new NomenclatureBinding(
                            rs.getLong("cell_id"),
                            rs.getLong("parse_run_id"),
                            rs.getLong("candidate_id"),
                            rs.getString("verbatim"),
                            rs.getString("path"),
                            rs.getString("amount_role"),
                            rs.getInt("soft_leaf") == 1,
                            rs.getInt("via_alias") == 1,
                            confidenceValue));
                }
                return rows;
            }
        }
    }

    /**
     * Default leaf rollup: sum numeric amounts for bindings on {@code path} with
     * {@code amount_role = add} only, excluding Packets whose Layer A relevance is
     * {@code noise}.
     */
    public void deleteBindingPeersForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM nomenclature_binding_peer WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public void insertBindingPeer(BindingPeer row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO nomenclature_binding_peer (parse_run_id, cell_id, peer_cell_id,"
                        + " peer_reason, path_resolved, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.cellId());
            ps.setLong(3, row.peerCellId());
            ps.setString(4, row.peerReason());
            ps.setInt(5, row.pathResolved() ? 1 : 0);
            ps.setString(6, Timestamps.now());
            ps.executeUpdate();
        }
    }

    public List<BindingPeer> selectBindingPeersForCell(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, cell_id, peer_cell_id, peer_reason, path_resolved"
                        + " FROM nomenclature_binding_peer"
                        + " WHERE parse_run_id = ? AND cell_id = ?"
                        + " ORDER BY peer_id")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BindingPeer> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new BindingPeer(
                            rs.getLong("parse_run_id"),
                            rs.getLong("cell_id"),
                            rs.getLong("peer_cell_id"),
                            rs.getString("peer_reason"),
                            rs.getInt("path_resolved") == 1));
                }
                return rows;
            }
        }
    }

    public Optional<NomenclatureBinding> selectNomenclatureBindingForCell(
            long parseRunId, long cellId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id, parse_run_id, candidate_id, verbatim, path, amount_role,"
                        + " soft_leaf, via_alias, confidence FROM nomenclature_binding"
                        + " WHERE parse_run_id = ? AND cell_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                double confidence = rs.getDouble("confidence");
                Double confidenceValue = rs.wasNull() ? null : confidence;
                return Optional.of(new NomenclatureBinding(
                        rs.getLong("cell_id"),
                        rs.getLong("parse_run_id"),
                        rs.getLong("candidate_id"),
                        rs.getString("verbatim"),
                        rs.getString("path"),
                        rs.getString("amount_role"),
                        rs.getInt("soft_leaf") == 1,
                        rs.getInt("via_alias") == 1,
                        confidenceValue));
            }
        }
    }

    public List<CandidateRow> selectCandidatesForCell(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.candidate_id, c.parse_run_id, c.worksheet_id, c.candidate_kind,"
                        + " c.parent_candidate_id, c.bbox_min_row, c.bbox_min_col, c.bbox_max_row,"
                        + " c.bbox_max_col, c.internal_whitespace, c.anchors, c.structural_signatures,"
                        + " c.isolated_hidden_worksheet, c.structural_confidence,"
                        + " c.structural_confidence_rationale, c.explanation, c.created_at"
                        + " FROM candidate c"
                        + " JOIN candidate_member m ON m.candidate_id = c.candidate_id"
                        + " WHERE c.parse_run_id = ? AND m.cell_id = ?"
                        + " ORDER BY c.candidate_id")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CandidateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapCandidateRow(rs));
                }
                return rows;
            }
        }
    }

    public Optional<PacketDisposition> selectPacketDisposition(long parseRunId, long candidateId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT candidate_id, parse_run_id, schedule_family, triage, relevance,"
                        + " row_labels, column_headers, packet_default_head, parent_candidate_id,"
                        + " cheap_pass FROM packet_disposition"
                        + " WHERE parse_run_id = ? AND candidate_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PacketDisposition(
                        rs.getLong("candidate_id"),
                        rs.getLong("parse_run_id"),
                        rs.getString("schedule_family"),
                        rs.getString("triage"),
                        rs.getString("relevance"),
                        readStringList(rs.getString("row_labels")),
                        readStringList(rs.getString("column_headers")),
                        rs.getString("packet_default_head"),
                        getNullableLong(rs, "parent_candidate_id"),
                        rs.getInt("cheap_pass") == 1));
            }
        }
    }

    public void deleteProjectFactBindingsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM project_fact_binding WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public void insertProjectFactField(ProjectFactField field, String createdAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO project_fact_field (path, name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, field.path());
            ps.setString(2, field.name());
            ps.setString(3, createdAt);
            ps.executeUpdate();
        }
    }

    public List<ProjectFactField> selectProjectFactFields() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT path, name FROM project_fact_field ORDER BY field_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<ProjectFactField> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ProjectFactField(rs.getString("path"), rs.getString("name")));
                }
                return rows;
            }
        }
    }

    public void insertProjectFactBinding(ProjectFactBinding row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO project_fact_binding (parse_run_id, candidate_id, cell_id,"
                        + " verbatim, fact_path, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.candidateId());
            if (row.cellId() == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setLong(3, row.cellId());
            }
            ps.setString(4, row.verbatim());
            ps.setString(5, row.factPath());
            ps.setString(6, Timestamps.now());
            ps.executeUpdate();
        }
    }

    public List<ProjectFactBinding> selectProjectFactBindingsForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, candidate_id, cell_id, verbatim, fact_path"
                        + " FROM project_fact_binding WHERE parse_run_id = ? ORDER BY fact_binding_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProjectFactBinding> rows = new ArrayList<>();
                while (rs.next()) {
                    long cellId = rs.getLong("cell_id");
                    Long cellIdValue = rs.wasNull() ? null : cellId;
                    rows.add(new ProjectFactBinding(
                            rs.getLong("parse_run_id"),
                            rs.getLong("candidate_id"),
                            cellIdValue,
                            rs.getString("verbatim"),
                            rs.getString("fact_path")));
                }
                return rows;
            }
        }
    }

    public List<ProjectFactBinding> selectProjectFactBindingsForCell(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, candidate_id, cell_id, verbatim, fact_path"
                        + " FROM project_fact_binding"
                        + " WHERE parse_run_id = ? AND cell_id = ?"
                        + " ORDER BY fact_binding_id")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProjectFactBinding> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ProjectFactBinding(
                            rs.getLong("parse_run_id"),
                            rs.getLong("candidate_id"),
                            cellId,
                            rs.getString("verbatim"),
                            rs.getString("fact_path")));
                }
                return rows;
            }
        }
    }

    public long countCellsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM cell c"
                        + " JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<InterpretationCellView> selectInterpretationCellsForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id, c.worksheet_id, c.coord, c.row_num, c.col_num, c.value_type,"
                        + " c.text_value, c.display_value, c.numeric_value, c.bool_value,"
                        + " c.date_value, c.formula_text, c.formula_state, c.cached_value,"
                        + " c.cache_state, c.is_error, c.error_type, c.is_merged_anchor,"
                        + " c.is_merged_participant, c.merged_range, c.value_source"
                        + " FROM cell c"
                        + " JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ?"
                        + " ORDER BY c.worksheet_id, c.row_num, c.col_num")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InterpretationCellView> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapInterpretationCellView(rs));
                }
                return rows;
            }
        }
    }

    /** All candidate_member rows for a parse run: [candidate_id, cell_id]. */
    public List<long[]> selectCandidateMembersForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT m.candidate_id, m.cell_id FROM candidate_member m"
                        + " JOIN candidate c ON c.candidate_id = m.candidate_id"
                        + " WHERE c.parse_run_id = ?"
                        + " ORDER BY m.candidate_id, m.cell_id")) {
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

    public void insertInterpretationEvidence(InterpretationEvidence row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_interpretation_evidence (parse_run_id, cell_id, role,"
                        + " source_cell_id, source_text, ordinal, resolution, normalized_value,"
                        + " rule_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.cellId());
            ps.setString(3, row.role());
            if (row.sourceCellId() == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, row.sourceCellId());
            }
            ps.setString(5, row.sourceText());
            ps.setInt(6, row.ordinal());
            ps.setString(7, row.resolution());
            ps.setString(8, row.normalizedValue());
            ps.setString(9, row.ruleId());
            ps.executeUpdate();
        }
    }

    public List<InterpretationEvidence> selectInterpretationEvidence(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, cell_id, role, source_cell_id, source_text, ordinal,"
                        + " resolution, normalized_value, rule_id"
                        + " FROM cell_interpretation_evidence"
                        + " WHERE parse_run_id = ? AND cell_id = ?"
                        + " ORDER BY ordinal, evidence_id")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InterpretationEvidence> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapInterpretationEvidence(rs));
                }
                return rows;
            }
        }
    }

    public long insertFormulaAnnotation(FormulaAnnotation row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_interpretation_formula_annotation (parse_run_id, cell_id,"
                        + " ordinal, raw_token, ref_kind, target_sheet_name, target_range,"
                        + " completeness, enclosing_function, shared_dependency_path,"
                        + " shared_dependency_kind)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.cellId());
            ps.setInt(3, row.ordinal());
            ps.setString(4, row.rawToken());
            ps.setString(5, row.refKind());
            ps.setString(6, row.targetSheetName());
            ps.setString(7, row.targetRange());
            ps.setString(8, row.completeness());
            ps.setString(9, row.enclosingFunction());
            ps.setString(10, row.sharedDependencyPath());
            ps.setString(11, row.sharedDependencyKind());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public void insertFormulaAnnotationMember(long annotationId, FormulaAnnotationMember member)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_interpretation_formula_annotation_member (annotation_id,"
                        + " ordinal, target_cell_id, target_coord, nomenclature_path,"
                        + " nomenclature_status) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, annotationId);
            ps.setInt(2, member.ordinal());
            ps.setLong(3, member.targetCellId());
            ps.setString(4, member.coord());
            ps.setString(5, member.nomenclaturePath());
            ps.setString(6, member.nomenclatureStatus());
            ps.executeUpdate();
        }
    }

    public List<FormulaAnnotation> selectFormulaAnnotations(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT annotation_id, parse_run_id, cell_id, ordinal, raw_token, ref_kind,"
                        + " target_sheet_name, target_range, completeness, enclosing_function,"
                        + " shared_dependency_path, shared_dependency_kind"
                        + " FROM cell_interpretation_formula_annotation"
                        + " WHERE parse_run_id = ? AND cell_id = ?"
                        + " ORDER BY ordinal, annotation_id")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FormulaAnnotation> rows = new ArrayList<>();
                while (rs.next()) {
                    long annotationId = rs.getLong("annotation_id");
                    rows.add(new FormulaAnnotation(
                            rs.getLong("parse_run_id"),
                            rs.getLong("cell_id"),
                            rs.getInt("ordinal"),
                            rs.getString("raw_token"),
                            rs.getString("ref_kind"),
                            rs.getString("target_sheet_name"),
                            rs.getString("target_range"),
                            rs.getString("completeness"),
                            rs.getString("enclosing_function"),
                            rs.getString("shared_dependency_path"),
                            rs.getString("shared_dependency_kind"),
                            selectFormulaAnnotationMembers(annotationId)));
                }
                return rows;
            }
        }
    }

    private List<FormulaAnnotationMember> selectFormulaAnnotationMembers(long annotationId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ordinal, target_cell_id, target_coord, nomenclature_path,"
                        + " nomenclature_status"
                        + " FROM cell_interpretation_formula_annotation_member"
                        + " WHERE annotation_id = ?"
                        + " ORDER BY ordinal, member_id")) {
            ps.setLong(1, annotationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FormulaAnnotationMember> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new FormulaAnnotationMember(
                            rs.getInt("ordinal"),
                            rs.getLong("target_cell_id"),
                            rs.getString("target_coord"),
                            rs.getString("nomenclature_path"),
                            rs.getString("nomenclature_status")));
                }
                return rows;
            }
        }
    }

    public void deleteInterpretationsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM cell_interpretation WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    public void insertCellInterpretation(CellInterpretation row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_interpretation (parse_run_id, cell_id, value_origin,"
                        + " resulting_value, result_source, formula_text, formula_state,"
                        + " cache_state, is_error, error_type, nomenclature_path, amount_role,"
                        + " soft_leaf, via_alias, nomenclature_status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.parseRunId());
            ps.setLong(2, row.cellId());
            ps.setString(3, row.valueOrigin());
            ps.setString(4, row.resultingValue());
            ps.setString(5, row.resultSource());
            ps.setString(6, row.formulaText());
            ps.setString(7, row.formulaState());
            ps.setString(8, row.cacheState());
            ps.setInt(9, row.isError() ? 1 : 0);
            ps.setString(10, row.errorType());
            ps.setString(11, row.nomenclaturePath());
            ps.setString(12, row.amountRole());
            if (row.softLeaf() == null) {
                ps.setNull(13, Types.INTEGER);
            } else {
                ps.setInt(13, row.softLeaf() ? 1 : 0);
            }
            if (row.viaAlias() == null) {
                ps.setNull(14, Types.INTEGER);
            } else {
                ps.setInt(14, row.viaAlias() ? 1 : 0);
            }
            ps.setString(15, row.nomenclatureStatus());
            ps.setString(16, Timestamps.now());
            ps.executeUpdate();
        }
    }

    public Optional<CellInterpretation> selectCellInterpretation(long parseRunId, long cellId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, cell_id, value_origin, resulting_value, result_source,"
                        + " formula_text, formula_state, cache_state, is_error, error_type,"
                        + " nomenclature_path, amount_role, soft_leaf, via_alias,"
                        + " nomenclature_status, formula_gloss"
                        + " FROM cell_interpretation"
                        + " WHERE parse_run_id = ? AND cell_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapCellInterpretation(rs));
            }
        }
    }

    public long countInterpretationsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM cell_interpretation WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<CellInterpretation> selectCellInterpretationsForParseRun(long parseRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, cell_id, value_origin, resulting_value, result_source,"
                        + " formula_text, formula_state, cache_state, is_error, error_type,"
                        + " nomenclature_path, amount_role, soft_leaf, via_alias,"
                        + " nomenclature_status, formula_gloss"
                        + " FROM cell_interpretation WHERE parse_run_id = ?"
                        + " ORDER BY interpretation_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CellInterpretation> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapCellInterpretation(rs));
                }
                return rows;
            }
        }
    }

    public void updateFormulaGloss(long parseRunId, long cellId, String gloss) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell_interpretation SET formula_gloss = ?"
                        + " WHERE parse_run_id = ? AND cell_id = ?")) {
            ps.setString(1, gloss);
            ps.setLong(2, parseRunId);
            ps.setLong(3, cellId);
            ps.executeUpdate();
        }
    }

    public double sumAddAmountsForPath(long parseRunId, String path) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(c.numeric_value), 0) AS total"
                        + " FROM nomenclature_binding b"
                        + " JOIN cell c ON c.cell_id = b.cell_id"
                        + " JOIN packet_disposition d"
                        + "   ON d.parse_run_id = b.parse_run_id"
                        + "  AND d.candidate_id = b.candidate_id"
                        + " WHERE b.parse_run_id = ? AND b.path = ? AND b.amount_role = 'add'"
                        + "   AND d.relevance != 'noise'")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, path);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble("total");
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

    private static InterpretationCellView mapInterpretationCellView(ResultSet rs)
            throws SQLException {
        int boolRaw = rs.getInt("bool_value");
        Boolean boolValue = rs.wasNull() ? null : boolRaw == 1;
        return new InterpretationCellView(
                rs.getLong("cell_id"),
                rs.getLong("worksheet_id"),
                rs.getString("coord"),
                rs.getInt("row_num"),
                rs.getInt("col_num"),
                rs.getString("value_type"),
                rs.getString("text_value"),
                rs.getString("display_value"),
                rs.getString("numeric_value"),
                boolValue,
                rs.getString("date_value"),
                rs.getString("formula_text"),
                rs.getString("formula_state"),
                rs.getString("cached_value"),
                rs.getString("cache_state"),
                rs.getInt("is_error") == 1,
                rs.getString("error_type"),
                rs.getInt("is_merged_anchor") == 1,
                rs.getInt("is_merged_participant") == 1,
                rs.getString("merged_range"),
                rs.getString("value_source"));
    }

    private static InterpretationEvidence mapInterpretationEvidence(ResultSet rs)
            throws SQLException {
        long sourceRaw = rs.getLong("source_cell_id");
        Long sourceCellId = rs.wasNull() ? null : sourceRaw;
        return new InterpretationEvidence(
                rs.getLong("parse_run_id"),
                rs.getLong("cell_id"),
                rs.getString("role"),
                sourceCellId,
                rs.getString("source_text"),
                rs.getInt("ordinal"),
                rs.getString("resolution"),
                rs.getString("normalized_value"),
                rs.getString("rule_id"));
    }

    private static CellInterpretation mapCellInterpretation(ResultSet rs) throws SQLException {
        int softRaw = rs.getInt("soft_leaf");
        Boolean softLeaf = rs.wasNull() ? null : softRaw == 1;
        int aliasRaw = rs.getInt("via_alias");
        Boolean viaAlias = rs.wasNull() ? null : aliasRaw == 1;
        return new CellInterpretation(
                rs.getLong("parse_run_id"),
                rs.getLong("cell_id"),
                rs.getString("value_origin"),
                rs.getString("resulting_value"),
                rs.getString("result_source"),
                rs.getString("formula_text"),
                rs.getString("formula_state"),
                rs.getString("cache_state"),
                rs.getInt("is_error") == 1,
                rs.getString("error_type"),
                rs.getString("nomenclature_path"),
                rs.getString("amount_role"),
                softLeaf,
                viaAlias,
                rs.getString("nomenclature_status"),
                rs.getString("formula_gloss"));
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

    private List<NomenclatureNode> selectNomenclatureByLayer(String layer, String industryTag)
            throws SQLException {
        String sql = industryTag == null
                ? "SELECT path, name, parent_path, layer, frozen, leaf, industry_tag, mandate_id"
                        + " FROM nomenclature_node WHERE layer = ? ORDER BY node_id"
                : "SELECT path, name, parent_path, layer, frozen, leaf, industry_tag, mandate_id"
                        + " FROM nomenclature_node WHERE layer = ? AND industry_tag = ? ORDER BY node_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, layer);
            if (industryTag != null) {
                ps.setString(2, industryTag);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<NomenclatureNode> nodes = new ArrayList<>();
                while (rs.next()) {
                    nodes.add(readNomenclatureNode(rs));
                }
                return nodes;
            }
        }
    }

    private static NomenclatureNode readNomenclatureNode(ResultSet rs) throws SQLException {
        return new NomenclatureNode(
                rs.getString("path"),
                rs.getString("name"),
                rs.getString("parent_path"),
                rs.getString("layer"),
                rs.getInt("frozen") == 1,
                rs.getInt("leaf") == 1,
                rs.getString("industry_tag"),
                getNullableLong(rs, "mandate_id"));
    }

    private static String jsonList(List<String> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Jsonb.toJson(values);
        } catch (JsonProcessingException e) {
            throw new SQLException("failed to serialize string list", e);
        }
    }

    private static List<String> readStringList(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Jsonb.fromJson(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new SQLException("failed to parse string list", e);
        }
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
