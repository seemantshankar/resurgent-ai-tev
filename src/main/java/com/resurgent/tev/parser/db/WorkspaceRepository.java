package com.resurgent.tev.parser.db;

import com.resurgent.tev.parser.ingest.NormalizedCell;
import com.resurgent.tev.parser.ingest.ParsedQuantity;
import com.resurgent.tev.parser.ingest.RegionQaStats;
import com.resurgent.tev.parser.ingest.SemanticFacts;
import com.resurgent.tev.parser.ingest.SemanticQaStats;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public void updateWorksheetRole(long worksheetId, String role, double confidence, String reasonsJson)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE worksheet SET role = ?, role_conf = ?, role_reasons = ? WHERE worksheet_id = ?")) {
            ps.setString(1, role);
            ps.setDouble(2, confidence);
            ps.setString(3, reasonsJson);
            ps.setLong(4, worksheetId);
            ps.executeUpdate();
        }
    }

    public record WorksheetRoleSheetRow(long worksheetId, String sheetName) {}

    public record WorksheetRoleCellRow(
            long cellId, long worksheetId, Long regionId, boolean scratchOrOrphan) {}

    public record WorksheetRoleRegionRow(long regionId, String regionType) {}

    public record WorksheetRoleContributionRow(long contributionId, long worksheetId, Long cellId) {}

    public List<WorksheetRoleSheetRow> findWorksheetRoleSheets(long parseRunId) throws SQLException {
        List<WorksheetRoleSheetRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT worksheet_id, sheet_name FROM worksheet WHERE parse_run_id = ?"
                        + " ORDER BY sheet_index")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new WorksheetRoleSheetRow(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return rows;
    }

    public List<WorksheetRoleCellRow> findWorksheetRoleCells(long parseRunId) throws SQLException {
        List<WorksheetRoleCellRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id, c.worksheet_id, c.region_id, c.is_scratch, c.is_orphan"
                        + " FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long regionId = rs.getLong("region_id");
                    rows.add(new WorksheetRoleCellRow(
                            rs.getLong("cell_id"),
                            rs.getLong("worksheet_id"),
                            rs.wasNull() ? null : regionId,
                            rs.getInt("is_scratch") == 1 || rs.getInt("is_orphan") == 1));
                }
            }
        }
        return rows;
    }

    public List<WorksheetRoleRegionRow> findWorksheetRoleRegions(long parseRunId) throws SQLException {
        List<WorksheetRoleRegionRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT region_id, region_type FROM region WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new WorksheetRoleRegionRow(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return rows;
    }

    public List<WorksheetRoleContributionRow> findWorksheetRoleContributions(long parseRunId)
            throws SQLException {
        List<WorksheetRoleContributionRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT contrib.cost_head_contribution_id, r.worksheet_id, cc.cell_id"
                        + " FROM cost_head_contribution contrib"
                        + " JOIN region r ON r.region_id = contrib.region_id"
                        + " LEFT JOIN cost_head_contribution_cell cc"
                        + " ON cc.cost_head_contribution_id = contrib.cost_head_contribution_id"
                        + " WHERE r.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cellId = rs.getLong("cell_id");
                    rows.add(new WorksheetRoleContributionRow(
                            rs.getLong("cost_head_contribution_id"),
                            rs.getLong("worksheet_id"),
                            rs.wasNull() ? null : cellId));
                }
            }
        }
        return rows;
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

    public long insertCell(long worksheetId, NormalizedCell cell) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell (worksheet_id, coord, row_num, col_num,"
                        + " raw_value, raw_type, value_type, text_value, display_value,"
                        + " numeric_value, bool_value, date_value,"
                        + " formula_text, formula_normalized, formula_state,"
                        + " cached_value, cache_state, coerced_from_text, parsed_quantity,"
                        + " is_error, error_type, row_label, col_label,"
                        + " is_merged_anchor, is_merged_participant, merged_range, value_source,"
                        + " row_hidden, col_hidden, sheet_hidden,"
                        + " is_bold, has_fill, has_border, number_format)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            setBoolean(ps, 31, cell.isBold());
            setBoolean(ps, 32, cell.hasFill());
            setBoolean(ps, 33, cell.hasBorder());
            ps.setString(34, cell.numberFormat());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** Persists a geometry-only region; classification fields remain at their schema defaults. */
    public long insertRegion(long worksheetId, long parseRunId, String regionKey,
            int startRow, int endRow, int startCol, int endCol) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO region (worksheet_id, parse_run_id, region_key, start_row, end_row,"
                        + " start_col, end_col, region_type, region_conf) VALUES (?, ?, ?, ?, ?, ?, ?, 'unknown', 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, worksheetId);
            ps.setLong(2, parseRunId);
            ps.setString(3, regionKey);
            ps.setInt(4, startRow);
            ps.setInt(5, endRow);
            ps.setInt(6, startCol);
            ps.setInt(7, endCol);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** Persists the complete Sprint 3a classification and header facts for a detected region. */
    public long insertRegion(long worksheetId, long parseRunId, String regionKey,
            int startRow, int endRow, int startCol, int endCol, String headerRows,
            String regionType, double regionConf, String costHeadCode, String periodAxis,
            String detectionReasons) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO region (worksheet_id, parse_run_id, region_key, start_row, end_row,"
                        + " start_col, end_col, header_rows, region_type, region_conf, cost_head_code,"
                        + " period_axis, detection_reasons) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, worksheetId);
            ps.setLong(2, parseRunId);
            ps.setString(3, regionKey);
            ps.setInt(4, startRow);
            ps.setInt(5, endRow);
            ps.setInt(6, startCol);
            ps.setInt(7, endCol);
            ps.setString(8, headerRows);
            ps.setString(9, regionType);
            ps.setDouble(10, regionConf);
            ps.setString(11, costHeadCode);
            ps.setString(12, periodAxis);
            ps.setString(13, detectionReasons);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** Assigns one occupied cell to its detector-produced region. */
    public void updateCellRegion(long cellId, long regionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET region_id = ? WHERE cell_id = ?")) {
            ps.setLong(1, regionId);
            ps.setLong(2, cellId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the region coverage and classification-accounting facts for one
     * parse run. Classification review rows carry the durable region id in
     * their JSON detail, so this query can prove that each low-confidence or
     * unknown region has a corresponding review item rather than merely
     * counting unrelated queue entries.
     */
    public RegionQaStats selectRegionQaStats(long parseRunId, double confidenceFloor)
            throws SQLException {
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id "
                + " WHERE w.parse_run_id = ? AND c.region_id IS NULL"
                + " AND (c.is_merged_participant = 1 OR c.is_error = 1 OR c.formula_text IS NOT NULL"
                + " OR (c.raw_value IS NOT NULL AND trim(c.raw_value) <> ''))), "
                + "COUNT(r.region_id), "
                + "COALESCE(SUM(CASE WHEN r.region_type <> 'unknown' AND r.region_conf >= ? THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN q.region_id IS NOT NULL THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN (r.region_type = 'unknown' OR r.region_conf < ?) "
                + " AND q.region_id IS NULL THEN 1 ELSE 0 END), 0) "
                + "FROM region r LEFT JOIN ("
                + " SELECT DISTINCT CAST(json_extract(detail, '$.regionId') AS INTEGER) AS region_id "
                + " FROM review_queue WHERE parse_run_id = ? AND category = 'region_classification'"
                + ") q ON q.region_id = r.region_id WHERE r.parse_run_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, parseRunId);
            ps.setDouble(2, confidenceFloor);
            ps.setDouble(3, confidenceFloor);
            ps.setLong(4, parseRunId);
            ps.setLong(5, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new RegionQaStats(rs.getInt(2), rs.getInt(1), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5));
            }
        }
    }

    public SemanticFacts selectSemanticFacts(long parseRunId) throws SQLException {
        int mappingsUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM cost_head_mapping m
                WHERE m.parse_run_id = ?
                AND NOT (
                  (m.match_method IN ('exact_alias', 'carried')
                   AND instr(COALESCE(m.reasons, ''), 'AMBIGUOUS') = 0)
                  OR EXISTS (
                    SELECT 1 FROM review_queue q
                    WHERE q.parse_run_id = m.parse_run_id
                    AND q.category = 'cost_head_mapping'
                    AND CAST(json_extract(q.detail, '$.mappingId') AS INTEGER) = m.cost_head_mapping_id
                  )
                )
                """);
        int contributionsUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM cost_head_contribution c
                JOIN cost_head_candidate cand ON cand.cost_head_candidate_id = c.cost_head_candidate_id
                WHERE cand.parse_run_id = ?
                AND (
                  c.basis IS NULL OR trim(c.basis) = ''
                  OR c.reasons IS NULL OR trim(c.reasons) = '' OR c.reasons = '[]'
                  OR (c.basis <> 'manual' AND NOT EXISTS (
                    SELECT 1 FROM cost_head_contribution_cell cc
                    WHERE cc.cost_head_contribution_id = c.cost_head_contribution_id
                  ))
                )
                """);
        int contributionArithmeticMismatches = countContributionArithmeticMismatches(parseRunId);
        int candidatesUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM (
                  SELECT DISTINCT m.cost_head_id
                  FROM cost_head_mapping m
                  JOIN cost_head h ON h.cost_head_id = m.cost_head_id
                  WHERE m.parse_run_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM cost_head_candidate c
                    WHERE c.parse_run_id = m.parse_run_id AND c.cost_head_id = m.cost_head_id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM review_queue q
                    WHERE q.parse_run_id = m.parse_run_id
                    AND q.category = 'cost_head_candidate'
                    AND json_extract(q.detail, '$.costHeadCode') = h.code
                  )
                )
                """);
        int duplicatesUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM duplicate_proposal p
                WHERE p.parse_run_id = ?
                AND (
                  p.reasons IS NULL OR trim(p.reasons) = '' OR p.reasons = '[]'
                  OR (
                    NOT EXISTS (
                      SELECT 1 FROM review_queue q
                      WHERE q.parse_run_id = p.parse_run_id
                      AND q.category = 'duplicate'
                      AND CAST(json_extract(q.detail, '$.proposalId') AS INTEGER)
                          = p.duplicate_proposal_id
                    )
                    AND NOT EXISTS (
                      SELECT 1 FROM duplicate_decision d
                      JOIN region lr ON lr.region_id = p.left_region_id
                      JOIN region rr ON rr.region_id = p.right_region_id
                      WHERE d.source_file_id = (
                        SELECT source_file_id FROM parse_run WHERE parse_run_id = p.parse_run_id
                      )
                      AND ((d.left_region_key = lr.region_key AND d.right_region_key = rr.region_key)
                        OR (d.left_region_key = rr.region_key AND d.right_region_key = lr.region_key))
                    )
                  )
                )
                """);
        int scratchUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM cell c
                JOIN worksheet w ON w.worksheet_id = c.worksheet_id
                WHERE w.parse_run_id = ?
                AND (
                  (c.is_scratch = 1 AND (c.scratch_reason IS NULL OR trim(c.scratch_reason) = ''))
                  OR (c.is_support = 1 AND (c.support_reason IS NULL OR trim(c.support_reason) = ''))
                )
                """);
        int worksheetRolesUnaccounted = countForParse(parseRunId, """
                SELECT COUNT(*) FROM worksheet w
                WHERE w.parse_run_id = ?
                AND (w.role IS NULL OR trim(w.role) = '')
                """);
        List<String> observed = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT h.code FROM cost_head_mapping m"
                        + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                        + " WHERE m.parse_run_id = ? ORDER BY h.code")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    observed.add(rs.getString(1));
                }
            }
        }
        int mappingsExact = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cost_head_mapping WHERE parse_run_id = ?"
                        + " AND match_method = 'exact_alias'"
                        + " AND instr(COALESCE(reasons, ''), 'AMBIGUOUS') = 0");
        int mappingsPending = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cost_head_mapping WHERE parse_run_id = ?"
                        + " AND (match_method = 'fuzzy_proposal'"
                        + " OR instr(COALESCE(reasons, ''), 'AMBIGUOUS') > 0)");
        int mappingsCarried = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cost_head_mapping WHERE parse_run_id = ?"
                        + " AND match_method = 'carried'");
        Map<String, Integer> bases = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.basis, COUNT(*) FROM cost_head_contribution c"
                        + " JOIN cost_head_candidate cand ON cand.cost_head_candidate_id"
                        + " = c.cost_head_candidate_id"
                        + " WHERE cand.parse_run_id = ? GROUP BY c.basis ORDER BY c.basis")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bases.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        int unitCurrencyUnknowns = countForParse(parseRunId,
                "SELECT COUNT(*) FROM region r WHERE r.parse_run_id = ?"
                        + " AND (r.inferred_unit = 'unknown' OR r.inferred_currency = 'unknown'"
                        + " OR r.inferred_unit IS NULL OR r.inferred_currency = 'unknown')");
        int scratch = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND c.is_scratch = 1");
        int support = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND c.is_support = 1");
        int orphan = countForParse(parseRunId,
                "SELECT COUNT(*) FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND c.is_orphan = 1");
        int promotions = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(CAST(json_extract(detail, '$.promotions') AS INTEGER)), 0)"
                        + " FROM review_queue WHERE parse_run_id = ? AND category = 'semantic_accounting'")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    promotions = rs.getInt(1);
                }
            }
        }
        int duplicatesProposed = countForParse(parseRunId,
                "SELECT COUNT(*) FROM duplicate_proposal WHERE parse_run_id = ?");
        int duplicatesDuplicate = countForParse(parseRunId, """
                SELECT COUNT(*) FROM duplicate_decision d
                WHERE d.decision = 'Duplicate'
                AND d.source_file_id = (SELECT source_file_id FROM parse_run WHERE parse_run_id = ?)
                """);
        int duplicatesDistinct = countForParse(parseRunId, """
                SELECT COUNT(*) FROM duplicate_decision d
                WHERE d.decision = 'Distinct'
                AND d.source_file_id = (SELECT source_file_id FROM parse_run WHERE parse_run_id = ?)
                """);
        return new SemanticFacts(
                new SemanticQaStats(
                        mappingsUnaccounted,
                        contributionsUnaccounted,
                        contributionArithmeticMismatches,
                        candidatesUnaccounted,
                        duplicatesUnaccounted,
                        scratchUnaccounted,
                        worksheetRolesUnaccounted),
                List.copyOf(observed),
                mappingsExact,
                mappingsPending,
                mappingsCarried,
                Map.copyOf(bases),
                unitCurrencyUnknowns,
                scratch,
                support,
                orphan,
                promotions,
                duplicatesProposed,
                duplicatesDuplicate,
                duplicatesDistinct);
    }

    public Set<String> findAcceptedTotalFingerprints(long sourceFileId) throws SQLException {
        Set<String> fingerprints = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT candidate_fingerprint FROM cost_head_total_decision"
                        + " WHERE source_file_id = ? AND decision = 'Accepted'")) {
            ps.setLong(1, sourceFileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fingerprints.add(rs.getString(1));
                }
            }
        }
        return Set.copyOf(fingerprints);
    }

    private int countContributionArithmeticMismatches(long parseRunId) throws SQLException {
        int mismatches = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cost_head_contribution_id, c.basis, c.source_amount, c.reasons"
                        + " FROM cost_head_contribution c"
                        + " JOIN cost_head_candidate cand ON cand.cost_head_candidate_id"
                        + " = c.cost_head_candidate_id"
                        + " WHERE cand.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String basis = rs.getString("basis");
                    if ("manual".equals(basis)) {
                        continue;
                    }
                    String reasons = rs.getString("reasons");
                    if (reasons != null && reasons.contains("AMOUNT_MISMATCH")) {
                        // Recorded mismatch is pending accounted work, not an unaccounted break.
                        continue;
                    }
                    long contributionId = rs.getLong("cost_head_contribution_id");
                    BigDecimal expected = rs.getBigDecimal("source_amount");
                    BigDecimal included = includedAmount(contributionId);
                    if (expected != null && included != null
                            && expected.compareTo(included) != 0) {
                        mismatches++;
                    }
                }
            }
        }
        return mismatches;
    }

    private BigDecimal includedAmount(long contributionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT SUM(cell.numeric_value) FROM cost_head_contribution_cell cc"
                        + " JOIN cell ON cell.cell_id = cc.cell_id"
                        + " WHERE cc.cost_head_contribution_id = ?"
                        + " AND cc.participation = 'included'")) {
            ps.setLong(1, contributionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getBigDecimal(1);
            }
        }
    }

    private int countForParse(long parseRunId, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Replaces the provisional generic labels with labels inferred from the cell's region headers. */
    public void updateCellLabels(long cellId, String rowLabel, String colLabel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET row_label = ?, col_label = ? WHERE cell_id = ?")) {
            ps.setString(1, rowLabel);
            ps.setString(2, colLabel);
            ps.setLong(3, cellId);
            ps.executeUpdate();
        }
    }

    public long insertCellReference(CellReferenceRow row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_reference (from_cell_id, token_index, raw_token, ref_kind,"
                        + " target_sheet_name, target_worksheet_id, target_range, resolved_cell_id,"
                        + " external_link_id, abs_row, abs_col, row_offset, col_offset,"
                        + " is_whole_column, is_whole_row, unresolved_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, row.fromCellId());
            ps.setInt(2, row.tokenIndex());
            ps.setString(3, row.rawToken());
            ps.setString(4, row.refKind());
            ps.setString(5, row.targetSheetName());
            setLong(ps, 6, row.targetWorksheetId());
            ps.setString(7, row.targetRange());
            setLong(ps, 8, row.resolvedCellId());
            setLong(ps, 9, row.externalLinkId());
            setBoolean(ps, 10, row.absRow());
            setBoolean(ps, 11, row.absCol());
            setInteger(ps, 12, row.rowOffset());
            setInteger(ps, 13, row.colOffset());
            ps.setInt(14, row.isWholeColumn() ? 1 : 0);
            ps.setInt(15, row.isWholeRow() ? 1 : 0);
            ps.setString(16, row.unresolvedReason());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public void insertCellErrorRoot(long cellId, long errorRootCellId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell_error_root (cell_id, error_root_cell_id) VALUES (?, ?)")) {
            ps.setLong(1, cellId);
            ps.setLong(2, errorRootCellId);
            ps.executeUpdate();
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

    public void updateWorkbookCycleMetadata(long workbookId, boolean calcIsCircular,
            int calcCircularGroupCount, int calcMaxCycleLength) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE workbook SET calc_is_circular = ?, calc_circular_group_count = ?,"
                        + " calc_max_cycle_length = ? WHERE workbook_id = ?")) {
            ps.setInt(1, calcIsCircular ? 1 : 0);
            ps.setInt(2, calcCircularGroupCount);
            ps.setInt(3, calcMaxCycleLength);
            ps.setLong(4, workbookId);
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

    public long insertReviewQueue(long parseRunId, String category, String summary,
            String detail, String status, boolean isEscalated, String createdAt,
            String resolvedAt, String subjectKind, String subjectKey, Double confidence)
            throws SQLException {
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
            ps.setString(9, subjectKind);
            ps.setString(10, subjectKey);
            if (confidence == null) {
                ps.setNull(11, java.sql.Types.REAL);
            } else {
                ps.setDouble(11, confidence);
            }
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

    /**
     * All {@code cell_reference} rows for a parse run, enriched with the referencing
     * cell's own worksheet id (needed to resolve local, unqualified ranges) — feeds
     * {@code ReferenceGraphLoader}, which expands range references into direct edges.
     */
    public ResultSet findCellReferenceRowsByParseRun(long parseRunId) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT cr.from_cell_id, cr.resolved_cell_id, cr.target_worksheet_id,"
                        + " cr.target_range, cr.is_whole_column, cr.is_whole_row, c.worksheet_id AS from_worksheet_id"
                        + " FROM cell_reference cr"
                        + " JOIN cell c ON cr.from_cell_id = c.cell_id"
                        + " JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                        + " WHERE w.parse_run_id = ?");
        ps.setLong(1, parseRunId);
        return ps.executeQuery();
    }

    /** Cell ids that exist within a row/col bounding box on one worksheet (range expansion, C3). */
    public java.util.List<Long> findCellIdsInRange(long worksheetId, int minRow, int maxRow,
            int minCol, int maxCol) throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cell_id FROM cell WHERE worksheet_id = ? AND row_num BETWEEN ? AND ?"
                        + " AND col_num BETWEEN ? AND ?")) {
            ps.setLong(1, worksheetId);
            ps.setInt(2, minRow);
            ps.setInt(3, maxRow);
            ps.setInt(4, minCol);
            ps.setInt(5, maxCol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Worksheet bbox (min/max row/col), used to clamp whole-column/whole-row range expansion. */
    public int[] findWorksheetBbox(long worksheetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col FROM worksheet"
                        + " WHERE worksheet_id = ?")) {
            ps.setLong(1, worksheetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) };
            }
        }
    }

    public void updateCellCircularStatus(long cellId, boolean isCircular, long circularGroupId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET is_circular = ?, circular_group_id = ? WHERE cell_id = ?")) {
            ps.setInt(1, isCircular ? 1 : 0);
            ps.setLong(2, circularGroupId);
            ps.setLong(3, cellId);
            ps.executeUpdate();
        }
    }

    public Set<Long> findErrorCellIdsByParseRun(long parseRunId) throws SQLException {
        Set<Long> ids = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id FROM cell c JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND (c.is_error = 1 OR c.value_type = 'error')")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    public void updateCellErrorRoot(long cellId, long errorRootCellId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET error_root_cell_id = ? WHERE cell_id = ?")) {
            ps.setLong(1, errorRootCellId);
            ps.setLong(2, cellId);
            ps.executeUpdate();
        }
    }

    public void updateCellErrorDescendant(long cellId, boolean errorDescendant) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET error_descendant = ? WHERE cell_id = ?")) {
            ps.setInt(1, errorDescendant ? 1 : 0);
            ps.setLong(2, cellId);
            ps.executeUpdate();
        }
    }

    public void updateCellErrorBarrier(long cellId, boolean isErrorBarrier) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET is_error_barrier = ? WHERE cell_id = ?")) {
            ps.setInt(1, isErrorBarrier ? 1 : 0);
            ps.setLong(2, cellId);
            ps.executeUpdate();
        }
    }

    /** Cell ids flagged as error barriers (function-wise) for a parse run, with their is_error flag. */
    public java.util.Map<Long, Boolean> findErrorBarrierCellsByParseRun(long parseRunId) throws SQLException {
        java.util.Map<Long, Boolean> result = new java.util.HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id, c.is_error FROM cell c JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND c.is_error_barrier = 1")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), rs.getInt(2) == 1);
                }
            }
        }
        return result;
    }

    public void updateCellSkeleton(long cellId, String formulaSkeleton) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET formula_skeleton = ? WHERE cell_id = ?")) {
            ps.setString(1, formulaSkeleton);
            ps.setLong(2, cellId);
            ps.executeUpdate();
        }
    }

    /** Stores the deterministic local formula-family evidence used by region scoring. */
    public void updateCellCoherence(long cellId, Double coherenceScore, String coherenceDirs)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET coherence_score = ?, coherence_dirs = ? WHERE cell_id = ?")) {
            if (coherenceScore == null) {
                ps.setNull(1, java.sql.Types.REAL);
            } else {
                ps.setDouble(1, coherenceScore);
            }
            ps.setString(2, coherenceDirs);
            ps.setLong(3, cellId);
            ps.executeUpdate();
        }
    }

    public List<CellSemanticRow> findSemanticCells(long parseRunId) throws SQLException {
        List<CellSemanticRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id, c.region_id, c.formula_text, c.row_label, c.col_label,"
                        + " c.text_value, c.numeric_value"
                        + " FROM cell c JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                        + " WHERE w.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long regionId = rs.getLong("region_id");
                    Long region = rs.wasNull() ? null : regionId;
                    String numeric = rs.getString("numeric_value");
                    rows.add(new CellSemanticRow(
                            rs.getLong("cell_id"),
                            region,
                            rs.getString("formula_text"),
                            rs.getString("row_label"),
                            rs.getString("col_label"),
                            rs.getString("text_value"),
                            numeric == null ? null : new java.math.BigDecimal(numeric)));
                }
            }
        }
        return rows;
    }

    public record CellSemanticUpdate(boolean scratch, String scratchReason, boolean support,
            String supportReason, boolean orphan) {}

    public void updateCellSemantics(long cellId, CellSemanticUpdate update) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cell SET is_scratch = ?, scratch_reason = ?, is_support = ?,"
                        + " support_reason = ?, is_orphan = ? WHERE cell_id = ?")) {
            ps.setInt(1, update.scratch() ? 1 : 0);
            ps.setString(2, update.scratchReason());
            ps.setInt(3, update.support() ? 1 : 0);
            ps.setString(4, update.supportReason());
            ps.setInt(5, update.orphan() ? 1 : 0);
            ps.setLong(6, cellId);
            ps.executeUpdate();
        }
    }

    public void updateSemanticRegionType(long regionId, String semanticRegionType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE region SET semantic_region_type = ? WHERE region_id = ?")) {
            ps.setString(1, semanticRegionType);
            ps.setLong(2, regionId);
            ps.executeUpdate();
        }
    }

    public void updateRegionSchema(long regionId, String schemaJson, String inferredUnit,
            double inferredUnitConf, String inferredCurrency, double inferredCurrencyConf)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE region SET schema_json = ?, inferred_unit = ?, inferred_unit_conf = ?,"
                        + " inferred_currency = ?, inferred_currency_conf = ? WHERE region_id = ?")) {
            ps.setString(1, schemaJson);
            ps.setString(2, inferredUnit);
            ps.setDouble(3, inferredUnitConf);
            ps.setString(4, inferredCurrency);
            ps.setDouble(5, inferredCurrencyConf);
            ps.setLong(6, regionId);
            ps.executeUpdate();
        }
    }

    public List<RegionMappingInput> findRegionMappingInputs(long parseRunId) throws SQLException {
        List<RegionMappingInput> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.region_id, r.region_key, r.cost_head_code,"
                        + " (SELECT c.text_value FROM cell c WHERE c.region_id = r.region_id"
                        + " AND c.text_value IS NOT NULL AND TRIM(c.text_value) != ''"
                        + " ORDER BY c.row_num, c.col_num LIMIT 1) AS label"
                        + " FROM region r WHERE r.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String label = rs.getString("label");
                    if (label == null || label.isBlank()) {
                        String key = rs.getString("region_key");
                        int bang = key.indexOf('!');
                        label = bang >= 0 ? key.substring(bang + 1) : key;
                    }
                    String existing = rs.getString("cost_head_code");
                    rows.add(new RegionMappingInput(
                            rs.getLong("region_id"),
                            rs.getString("region_key"),
                            existing != null && !existing.isBlank() ? existing : label,
                            existing));
                }
            }
        }
        return rows;
    }

    public long ensureCostHead(long mandateId, String code) throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT cost_head_id FROM cost_head WHERE mandate_id = ? AND code = ?")) {
            find.setLong(1, mandateId);
            find.setString(2, code);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO cost_head (mandate_id, code, label) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, mandateId);
            insert.setString(2, code);
            insert.setString(3, code);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    public long insertCostHeadMapping(long parseRunId, long sourceFileId, long costHeadId,
            long regionId, String regionKey, String method, double score, double runnerUpMargin,
            double confidence, String reasonsJson, String sourceLabel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_mapping (parse_run_id, source_file_id, cost_head_id, region_id,"
                        + " region_key, match_method, match_score, runner_up_margin, confidence, reasons, source_label)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, sourceFileId);
            ps.setLong(3, costHeadId);
            ps.setLong(4, regionId);
            ps.setString(5, regionKey);
            ps.setString(6, method);
            ps.setDouble(7, score);
            ps.setDouble(8, runnerUpMargin);
            ps.setDouble(9, confidence);
            ps.setString(10, reasonsJson);
            ps.setString(11, sourceLabel);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public List<RegionAnchorRow> findRegionAnchorRows(long parseRunId) throws SQLException {
        List<RegionAnchorRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.region_id, r.region_key, r.schema_json, r.header_rows, r.inferred_unit,"
                        + " r.inferred_currency, m.cost_head_mapping_id, m.cost_head_id, h.code,"
                        + " m.match_method, m.reasons AS mapping_reasons"
                        + " FROM region r"
                        + " JOIN cost_head_mapping m ON m.region_id = r.region_id"
                        + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                        + " WHERE r.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RegionAnchorRow(
                            rs.getLong("region_id"),
                            rs.getString("region_key"),
                            rs.getString("schema_json"),
                            rs.getString("header_rows"),
                            rs.getString("inferred_unit"),
                            rs.getString("inferred_currency"),
                            rs.getLong("cost_head_mapping_id"),
                            rs.getLong("cost_head_id"),
                            rs.getString("code"),
                            rs.getString("match_method"),
                            rs.getString("mapping_reasons")));
                }
            }
        }
        return rows;
    }

    public List<CellAnchorRow> findRegionAnchorCells(long parseRunId) throws SQLException {
        List<CellAnchorRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.cell_id, c.region_id, c.coord, c.row_num, c.col_num, c.text_value,"
                        + " c.numeric_value, c.formula_text, c.is_error, c.error_descendant,"
                        + " c.is_scratch, c.is_merged_participant, c.cache_state, c.number_format,"
                        + " w.sheet_name"
                        + " FROM cell c JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                        + " WHERE w.parse_run_id = ? AND c.region_id IS NOT NULL")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String numeric = rs.getString("numeric_value");
                    rows.add(new CellAnchorRow(
                            rs.getLong("cell_id"),
                            rs.getLong("region_id"),
                            rs.getString("coord"),
                            rs.getInt("row_num"),
                            rs.getInt("col_num"),
                            rs.getString("text_value"),
                            numeric == null ? null : new BigDecimal(numeric),
                            rs.getString("formula_text"),
                            rs.getInt("is_error") == 1,
                            rs.getInt("error_descendant") == 1,
                            rs.getInt("is_scratch") == 1,
                            rs.getInt("is_merged_participant") == 1,
                            rs.getString("cache_state"),
                            rs.getString("number_format"),
                            rs.getString("sheet_name")));
                }
            }
        }
        return rows;
    }

    public long insertCostHeadCandidate(long parseRunId, long sourceFileId, long costHeadId,
            String fingerprint, BigDecimal amount, String currency, String unit, int automaticTrust,
            double confidence, String reasonsJson) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_candidate (parse_run_id, source_file_id, cost_head_id,"
                        + " candidate_fingerprint, amount, currency, unit, automatic_trust_eligible,"
                        + " confidence, reasons) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, sourceFileId);
            ps.setLong(3, costHeadId);
            ps.setString(4, fingerprint);
            if (amount == null) {
                ps.setNull(5, java.sql.Types.NUMERIC);
            } else {
                ps.setBigDecimal(5, amount);
            }
            ps.setString(6, currency);
            ps.setString(7, unit);
            ps.setInt(8, automaticTrust);
            ps.setDouble(9, confidence);
            ps.setString(10, reasonsJson);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertCostHeadContribution(long candidateId, Long mappingId, long regionId,
            Long anchorCellId, String basis, BigDecimal sourceAmount, String sourceCurrency,
            String sourceUnit, BigDecimal normalizedAmount, String normalizedCurrency,
            String normalizedUnit, double confidence, String reasonsJson) throws SQLException {
        return insertCostHeadContribution(candidateId, mappingId, regionId, anchorCellId, basis,
                sourceAmount, sourceCurrency, sourceUnit, normalizedAmount, normalizedCurrency,
                normalizedUnit, confidence, reasonsJson, false);
    }

    public long insertCostHeadContribution(long candidateId, Long mappingId, long regionId,
            Long anchorCellId, String basis, BigDecimal sourceAmount, String sourceCurrency,
            String sourceUnit, BigDecimal normalizedAmount, String normalizedCurrency,
            String normalizedUnit, double confidence, String reasonsJson, boolean locationOptional)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_contribution (cost_head_candidate_id, cost_head_mapping_id, region_id,"
                        + " anchor_cell_id, basis, source_amount, source_currency, source_unit,"
                        + " normalized_amount, normalized_currency, normalized_unit, confidence, reasons)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, candidateId);
            if (mappingId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, mappingId);
            }
            if (locationOptional) {
                ps.setNull(3, java.sql.Types.INTEGER);
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, regionId);
                setLong(ps, 4, anchorCellId);
            }
            ps.setString(5, basis);
            ps.setBigDecimal(6, sourceAmount);
            ps.setString(7, sourceCurrency);
            ps.setString(8, sourceUnit);
            if (normalizedAmount == null) {
                ps.setNull(9, java.sql.Types.NUMERIC);
            } else {
                ps.setBigDecimal(9, normalizedAmount);
            }
            ps.setString(10, normalizedCurrency);
            ps.setString(11, normalizedUnit);
            ps.setDouble(12, confidence);
            ps.setString(13, reasonsJson);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public void insertCostHeadContributionCell(long contributionId, long cellId, String participation,
            String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_contribution_cell (cost_head_contribution_id, cell_id,"
                        + " participation, reason) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, contributionId);
            ps.setLong(2, cellId);
            ps.setString(3, participation);
            ps.setString(4, reason);
            ps.executeUpdate();
        }
    }

    public String findLatestAcceptedMappingCode(long sourceFileId, String regionKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT d.cost_head_code FROM cost_head_mapping_decision d"
                        + " WHERE d.source_file_id = ? AND d.region_key = ? AND d.decision = 'Accepted'"
                        + " ORDER BY d.decided_at DESC, d.mapping_decision_id DESC LIMIT 1")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, regionKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public List<ReviewQueueRow> findPendingMappingReviews(long parseRunId) throws SQLException {
        List<ReviewQueueRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT review_queue_id, summary, detail FROM review_queue"
                        + " WHERE parse_run_id = ? AND category = 'cost_head_mapping'"
                        + " AND status = 'Pending' ORDER BY review_queue_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ReviewQueueRow(
                            rs.getLong("review_queue_id"),
                            rs.getString("summary"),
                            rs.getString("detail")));
                }
            }
        }
        return rows;
    }

    public void insertMappingDecision(long sourceFileId, String regionKey, String code,
            String sourceLabel, String decision, String actor, String reason, String decidedAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_mapping_decision (source_file_id, region_key, cost_head_code, source_label, decision, actor, reason, decided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, regionKey);
            ps.setString(3, code);
            ps.setString(4, sourceLabel);
            ps.setString(5, decision);
            ps.setString(6, actor);
            ps.setString(7, reason);
            ps.setString(8, decidedAt);
            ps.executeUpdate();
        }
    }

    public void resolveReviewQueue(long reviewQueueId, String status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE review_queue SET status = ? WHERE review_queue_id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, reviewQueueId);
            ps.executeUpdate();
        }
    }

    public long findLatestParseRunId() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id FROM parse_run ORDER BY parse_run_id DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("no parse_run in workspace");
            }
            return rs.getLong(1);
        }
    }

    public MappingIdentity findMappingIdentity(long mappingId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT source_file_id, region_key, source_label FROM cost_head_mapping WHERE cost_head_mapping_id = ?")) {
            ps.setLong(1, mappingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("cost_head_mapping not found: " + mappingId);
                }
                return new MappingIdentity(rs.getLong("source_file_id"), rs.getString("region_key"),
                        rs.getString("source_label"));
            }
        }
    }

    public List<ReviewQueueRow> findPendingTotalReviews(long parseRunId) throws SQLException {
        List<ReviewQueueRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT review_queue_id, summary, detail FROM review_queue"
                        + " WHERE parse_run_id = ? AND category = 'cost_head_candidate'"
                        + " AND status = 'Pending' ORDER BY review_queue_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ReviewQueueRow(
                            rs.getLong("review_queue_id"),
                            rs.getString("summary"),
                            rs.getString("detail")));
                }
            }
        }
        return rows;
    }

    public CandidateIdentity findCandidateIdentity(long candidateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.source_file_id, h.code, c.candidate_fingerprint"
                        + " FROM cost_head_candidate c JOIN cost_head h ON h.cost_head_id = c.cost_head_id"
                        + " WHERE c.cost_head_candidate_id = ?")) {
            ps.setLong(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("cost_head_candidate not found: " + candidateId);
                }
                return new CandidateIdentity(
                        rs.getLong("source_file_id"),
                        rs.getString("code"),
                        rs.getString("candidate_fingerprint"));
            }
        }
    }

    public Long findLatestTotalDecisionId(long sourceFileId, String costHeadCode, String fingerprint)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT total_decision_id FROM cost_head_total_decision"
                        + " WHERE source_file_id = ? AND cost_head_code = ? AND candidate_fingerprint = ?"
                        + " ORDER BY total_decision_id DESC LIMIT 1")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, costHeadCode);
            ps.setString(3, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public String findLatestAcceptedTotalFingerprint(long sourceFileId, String costHeadCode)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT candidate_fingerprint FROM cost_head_total_decision"
                        + " WHERE source_file_id = ? AND cost_head_code = ? AND decision = 'Accepted'"
                        + " ORDER BY total_decision_id DESC LIMIT 1")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, costHeadCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public Set<String> findPendingManualCostHeads(long sourceFileId) throws SQLException {
        Set<String> codes = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT h.code FROM manual_contribution m"
                        + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                        + " WHERE m.source_file_id = ? AND m.status = 'Pending'")) {
            ps.setLong(1, sourceFileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
        }
        return codes;
    }

    public Long findLatestAcceptedTotalDecisionId(long sourceFileId, String costHeadCode,
            String fingerprint) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT total_decision_id, decision FROM cost_head_total_decision"
                        + " WHERE source_file_id = ? AND cost_head_code = ? AND candidate_fingerprint = ?"
                        + " ORDER BY total_decision_id DESC LIMIT 1")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, costHeadCode);
            ps.setString(3, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "Accepted".equals(rs.getString("decision"))) {
                    return rs.getLong("total_decision_id");
                }
                return null;
            }
        }
    }

    public void carryReviewQueue(long reviewQueueId, long decisionId, String status)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE review_queue SET status = ?, carried_from_decision_id = ? WHERE review_queue_id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, decisionId);
            ps.setLong(3, reviewQueueId);
            ps.executeUpdate();
        }
    }

    public ParseContext findLatestParseContext() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parse_run_id, source_file_id, mandate_id FROM parse_run"
                        + " ORDER BY parse_run_id DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("no parse_run in workspace");
            }
            return new ParseContext(rs.getLong("parse_run_id"), rs.getLong("source_file_id"),
                    rs.getLong("mandate_id"));
        }
    }

    public long findCostHeadId(long mandateId, String code) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cost_head_id FROM cost_head WHERE mandate_id = ? AND code = ?")) {
            ps.setLong(1, mandateId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("cost head not found: " + code);
                }
                return rs.getLong(1);
            }
        }
    }

    public String findContributionRegionKey(long contributionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.region_key FROM cost_head_contribution c"
                        + " JOIN region r ON r.region_id = c.region_id"
                        + " WHERE c.cost_head_contribution_id = ?")) {
            ps.setLong(1, contributionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("contribution not found: " + contributionId);
                }
                return rs.getString(1);
            }
        }
    }

    public long insertManualContribution(long sourceFileId, long costHeadId, Long adjustsContributionId,
            BigDecimal amount, String currency, String unit, String reason, String actor,
            String status, String createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO manual_contribution (source_file_id, cost_head_id, adjusts_contribution_id,"
                        + " amount, currency, unit, reason, actor, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setLong(2, costHeadId);
            if (adjustsContributionId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, adjustsContributionId);
            }
            ps.setBigDecimal(4, amount);
            ps.setString(5, currency);
            ps.setString(6, unit);
            ps.setString(7, reason);
            ps.setString(8, actor);
            ps.setString(9, status);
            ps.setString(10, createdAt);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public ManualContributionRow findManualContribution(long manualId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT manual_contribution_id, source_file_id, cost_head_id, status"
                        + " FROM manual_contribution WHERE manual_contribution_id = ?")) {
            ps.setLong(1, manualId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("manual contribution not found: " + manualId);
                }
                return new ManualContributionRow(
                        rs.getLong("manual_contribution_id"),
                        rs.getLong("source_file_id"),
                        rs.getLong("cost_head_id"),
                        rs.getString("status"));
            }
        }
    }

    public void updateManualStatus(long manualId, String status, String decidedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE manual_contribution SET status = ?, decided_at = ? WHERE manual_contribution_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, decidedAt);
            ps.setLong(3, manualId);
            ps.executeUpdate();
        }
    }

    public void updateManualValues(long manualId, BigDecimal amount, String unit, String currency)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE manual_contribution SET amount = ?, unit = ?, currency = ?"
                        + " WHERE manual_contribution_id = ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, unit);
            ps.setString(3, currency);
            ps.setLong(4, manualId);
            ps.executeUpdate();
        }
    }

    public String findCostHeadCode(long costHeadId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code FROM cost_head WHERE cost_head_id = ?")) {
            ps.setLong(1, costHeadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("cost head not found: " + costHeadId);
                }
                return rs.getString(1);
            }
        }
    }

    public long insertDuplicateProposal(long parseRunId, long leftRegionId, long rightRegionId,
            String method, double score, String reasonsJson) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO duplicate_proposal (parse_run_id, left_region_id, right_region_id,"
                        + " method, score, reasons) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, leftRegionId);
            ps.setLong(3, rightRegionId);
            ps.setString(4, method);
            ps.setDouble(5, score);
            ps.setString(6, reasonsJson);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertDuplicateDecision(long sourceFileId, String leftRegionKey, String rightRegionKey,
            String decision, String supersededRegionKey, String actor, String reason, String decidedAt,
            Long supersedesId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO duplicate_decision (source_file_id, left_region_key, right_region_key,"
                        + " decision, superseded_region_key, actor, reason, decided_at, supersedes_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, leftRegionKey);
            ps.setString(3, rightRegionKey);
            ps.setString(4, decision);
            ps.setString(5, supersededRegionKey);
            ps.setString(6, actor);
            ps.setString(7, reason);
            ps.setString(8, decidedAt);
            if (supersedesId == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setLong(9, supersedesId);
            }
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public List<DuplicateDecisionRow> findLatestDuplicateDecisions(long sourceFileId) throws SQLException {
        Map<String, DuplicateDecisionRow> latest = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT left_region_key, right_region_key, decision, superseded_region_key"
                        + " FROM duplicate_decision WHERE source_file_id = ?"
                        + " ORDER BY duplicate_decision_id")) {
            ps.setLong(1, sourceFileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DuplicateDecisionRow row = new DuplicateDecisionRow(
                            rs.getString("left_region_key"),
                            rs.getString("right_region_key"),
                            rs.getString("decision"),
                            rs.getString("superseded_region_key"));
                    latest.put(canonicalPairKey(row.leftRegionKey(), row.rightRegionKey()), row);
                }
            }
        }
        return List.copyOf(latest.values());
    }

    private static String canonicalPairKey(String left, String right) {
        return left.compareTo(right) <= 0 ? left + '\0' + right : right + '\0' + left;
    }

    public Long findLatestDuplicateDecisionId(long sourceFileId, String leftRegionKey, String rightRegionKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT duplicate_decision_id FROM duplicate_decision"
                        + " WHERE source_file_id = ? AND ("
                        + " (left_region_key = ? AND right_region_key = ?)"
                        + " OR (left_region_key = ? AND right_region_key = ?))"
                        + " ORDER BY duplicate_decision_id DESC LIMIT 1")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, leftRegionKey);
            ps.setString(3, rightRegionKey);
            ps.setString(4, rightRegionKey);
            ps.setString(5, leftRegionKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public List<ReviewQueueRow> findPendingDuplicateReviews(long parseRunId) throws SQLException {
        List<ReviewQueueRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT review_queue_id, summary, detail FROM review_queue"
                        + " WHERE parse_run_id = ? AND category = 'duplicate'"
                        + " AND status = 'Pending' ORDER BY review_queue_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ReviewQueueRow(
                            rs.getLong("review_queue_id"),
                            rs.getString("summary"),
                            rs.getString("detail")));
                }
            }
        }
        return rows;
    }

    public List<String> findCostHeadCodesForRegionKeys(long sourceFileId, String leftKey, String rightKey)
            throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT h.code FROM cost_head_mapping m"
                        + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                        + " WHERE m.source_file_id = ? AND m.region_key IN (?, ?)")) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, leftKey);
            ps.setString(3, rightKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
        }
        return codes;
    }

    public void reopenCostHeadCandidateReviews(long parseRunId, String costHeadCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE review_queue SET status = 'Pending', carried_from_decision_id = NULL"
                        + " WHERE parse_run_id = ? AND category = 'cost_head_candidate'"
                        + " AND subject_key = ?")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, costHeadCode);
            ps.executeUpdate();
        }
    }

    public List<AcceptedManualRow> findAcceptedManuals(long sourceFileId) throws SQLException {
        List<AcceptedManualRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT m.manual_contribution_id, h.code, m.amount, m.unit, m.currency,"
                        + " COALESCE(p.location, '') AS adjusts_key"
                        + " FROM manual_contribution m"
                        + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                        + " LEFT JOIN provenance p ON p.entity_type = 'manual_contribution'"
                        + " AND p.entity_id = m.manual_contribution_id"
                        + " WHERE m.source_file_id = ? AND m.status = 'Accepted'"
                        + " ORDER BY m.manual_contribution_id")) {
            ps.setLong(1, sourceFileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AcceptedManualRow(
                            rs.getLong("manual_contribution_id"),
                            rs.getString("code"),
                            rs.getString("amount"),
                            rs.getString("unit"),
                            rs.getString("currency"),
                            rs.getString("adjusts_key")));
                }
            }
        }
        return rows;
    }

    public long insertTotalDecision(long sourceFileId, String costHeadCode, String fingerprint,
            String decision, String actor, String reason, String decidedAt, Long supersedesId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cost_head_total_decision (source_file_id, cost_head_code, candidate_fingerprint,"
                        + " decision, actor, reason, decided_at, supersedes_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setString(2, costHeadCode);
            ps.setString(3, fingerprint);
            ps.setString(4, decision);
            ps.setString(5, actor);
            ps.setString(6, reason);
            ps.setString(7, decidedAt);
            if (supersedesId == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setLong(8, supersedesId);
            }
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public record MappingIdentity(long sourceFileId, String regionKey, String sourceLabel) {}

    public record CandidateIdentity(long sourceFileId, String costHeadCode, String fingerprint) {}

    public record ParseContext(long parseRunId, long sourceFileId, long mandateId) {}

    public record ManualContributionRow(long id, long sourceFileId, long costHeadId, String status) {}

    public record AcceptedManualRow(
            long id, String costHeadCode, String amount, String unit, String currency, String adjustsKey) {}

    public record RegionAnchorRow(
            long regionId,
            String regionKey,
            String schemaJson,
            String headerRowsJson,
            String unit,
            String currency,
            long mappingId,
            long costHeadId,
            String costHeadCode,
            String matchMethod,
            String mappingReasons) {}

    public record CellAnchorRow(
            long cellId,
            long regionId,
            String coord,
            int row,
            int col,
            String text,
            BigDecimal numeric,
            String formula,
            boolean error,
            boolean errorDescendant,
            boolean scratch,
            boolean mergedParticipant,
            String cacheState,
            String numberFormat,
            String sheetName) {}

    public record RegionMappingInput(long regionId, String regionKey, String label, String existingCode) {}

    public record ReviewQueueRow(long reviewQueueId, String summary, String detail) {}

    public record DuplicateDecisionRow(
            String leftRegionKey, String rightRegionKey, String decision, String supersededRegionKey) {}

    public record CellSemanticRow(
            long cellId,
            Long regionId,
            String formula,
            String rowLabel,
            String colLabel,
            String text,
            java.math.BigDecimal numeric) {}

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
