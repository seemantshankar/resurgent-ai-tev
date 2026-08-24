package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Scores each worksheet as primary, support, scratch, or unknown from
 * content- and dependency-weighted evidence. Region counts are never used as
 * scores: one-cell regions cannot outvote a cost-head table.
 */
final class WorksheetRoleScorer {

    private static final Set<String> STATEMENT_TYPES = Set.of(
            "pnl", "bs", "cash_flow", "debt_schedule", "mof");

    record Score(
            long worksheetId,
            String sheetName,
            String role,
            double confidence,
            List<RoleReason> reasons) {
    }

    record RoleReason(Code code, int weight, Map<String, Long> params) {
        RoleReason {
            params = Collections.unmodifiableMap(
                    new LinkedHashMap<>(new TreeMap<>(params)));
        }

        enum Code {
            COST_HEAD_CONTRIBUTION,
            STATEMENT_CONTENT,
            DEPENDENCY_INTO_PRIMARY,
            SCRATCH_ORPHAN_CONTENT,
            ROLE_CONFLICT
        }
    }

    List<Score> score(WorkspaceRepository repo, long parseRunId) throws SQLException {
        List<SheetSnapshot> sheets = loadSheets(repo, parseRunId);
        if (sheets.isEmpty()) {
            return List.of();
        }
        Map<Long, SheetSnapshot> byId = new LinkedHashMap<>();
        for (SheetSnapshot sheet : sheets) {
            byId.put(sheet.worksheetId, sheet);
        }
        loadCells(repo, parseRunId, byId);
        loadRegions(repo, parseRunId, byId);
        loadContributions(repo, parseRunId, byId);
        Map<Long, Long> cellSheet = cellSheetIndex(sheets);
        Set<Long> primaryIds = new HashSet<>();
        for (SheetSnapshot sheet : sheets) {
            if (sheet.primaryMass() > 0 && !sheet.conflictingIdentity()) {
                primaryIds.add(sheet.worksheetId);
            }
        }
        Map<Long, Integer> feederMass = feederMassBySheet(repo, parseRunId, cellSheet, primaryIds);
        boolean grew = true;
        Set<Long> live = new HashSet<>(primaryIds);
        while (grew) {
            grew = false;
            Map<Long, Integer> next = feederMassBySheet(repo, parseRunId, cellSheet, live);
            for (Map.Entry<Long, Integer> entry : next.entrySet()) {
                feederMass.merge(entry.getKey(), entry.getValue(), Math::max);
                if (entry.getValue() > 0 && live.add(entry.getKey())) {
                    grew = true;
                }
            }
        }
        List<Score> scores = new ArrayList<>(sheets.size());
        for (SheetSnapshot sheet : sheets) {
            int feed = feederMass.getOrDefault(sheet.worksheetId, 0);
            scores.add(decide(sheet, feed, primaryIds.contains(sheet.worksheetId)));
        }
        return scores;
    }

    private static Score decide(SheetSnapshot sheet, int feedMass, boolean seedPrimary) {
        int primary = sheet.primaryMass();
        int scratch = sheet.scratchMass();
        int occupied = sheet.cells.size();
        boolean onlyScratchOrphan = occupied > 0 && scratch == occupied && primary == 0;
        List<RoleReason> reasons = new ArrayList<>();
        if (sheet.contribCells.size() > 0) {
            reasons.add(new RoleReason(RoleReason.Code.COST_HEAD_CONTRIBUTION, sheet.contribCells.size() * 8,
                    Map.of("cell_count", (long) sheet.contribCells.size(),
                            "contribution_count", (long) sheet.contributionCount)));
        }
        if (sheet.statementCells.size() > 0) {
            reasons.add(new RoleReason(RoleReason.Code.STATEMENT_CONTENT, sheet.statementCells.size() * 4,
                    Map.of("cell_count", (long) sheet.statementCells.size())));
        }
        if (feedMass > 0) {
            reasons.add(new RoleReason(RoleReason.Code.DEPENDENCY_INTO_PRIMARY, feedMass,
                    Map.of("cell_count", (long) feedMass)));
        }
        if (scratch > 0) {
            reasons.add(new RoleReason(RoleReason.Code.SCRATCH_ORPHAN_CONTENT, scratch,
                    Map.of("cell_count", (long) scratch)));
        }

        String role;
        double confidence;
        if (sheet.conflictingIdentity()) {
            reasons.add(new RoleReason(RoleReason.Code.ROLE_CONFLICT, 1,
                    Map.of("primary_mass", (long) primary, "scratch_mass", (long) scratch)));
            role = "unknown";
            confidence = margin(primary, scratch);
        } else if (primary > 0 || seedPrimary) {
            role = "primary";
            confidence = margin(primary, Math.max(feedMass, scratch));
        } else if (feedMass > 0) {
            role = "support";
            confidence = margin(feedMass, scratch);
        } else if (onlyScratchOrphan) {
            role = "scratch";
            confidence = occupied == 0 ? 0.0 : 1.0;
        } else {
            role = "unknown";
            confidence = 0.0;
        }
        return new Score(sheet.worksheetId, sheet.sheetName, role, confidence, List.copyOf(reasons));
    }

    private static double margin(int best, int runnerUp) {
        if (best <= 0) {
            return 0.0;
        }
        return Math.clamp((best - Math.max(0, runnerUp)) / (double) best, 0.0, 1.0);
    }

    private static Map<Long, Integer> feederMassBySheet(
            WorkspaceRepository repo, long parseRunId, Map<Long, Long> cellSheet, Set<Long> live)
            throws SQLException {
        Map<Long, Integer> mass = new HashMap<>();
        Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
        for (Map.Entry<Long, List<Long>> entry : adjacency.entrySet()) {
            Long fromSheet = cellSheet.get(entry.getKey());
            if (fromSheet == null || !live.contains(fromSheet)) {
                continue;
            }
            for (Long precedent : entry.getValue()) {
                Long toSheet = cellSheet.get(precedent);
                if (toSheet != null && !toSheet.equals(fromSheet)) {
                    mass.merge(toSheet, 1, Integer::sum);
                }
            }
        }
        return mass;
    }

    private static Map<Long, Long> cellSheetIndex(List<SheetSnapshot> sheets) {
        Map<Long, Long> index = new HashMap<>();
        for (SheetSnapshot sheet : sheets) {
            for (long cellId : sheet.cells) {
                index.put(cellId, sheet.worksheetId);
            }
        }
        return index;
    }

    private static List<SheetSnapshot> loadSheets(WorkspaceRepository repo, long parseRunId)
            throws SQLException {
        List<SheetSnapshot> sheets = new ArrayList<>();
        try (PreparedStatement ps = repo.connection().prepareStatement(
                "SELECT worksheet_id, sheet_name FROM worksheet WHERE parse_run_id = ?"
                        + " ORDER BY sheet_index")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sheets.add(new SheetSnapshot(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return sheets;
    }

    private static void loadCells(WorkspaceRepository repo, long parseRunId, Map<Long, SheetSnapshot> byId)
            throws SQLException {
        try (PreparedStatement ps = repo.connection().prepareStatement(
                "SELECT c.cell_id, c.worksheet_id, c.region_id, c.is_scratch, c.is_orphan"
                        + " FROM cell c JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SheetSnapshot sheet = byId.get(rs.getLong("worksheet_id"));
                    if (sheet == null) {
                        continue;
                    }
                    long cellId = rs.getLong("cell_id");
                    sheet.cells.add(cellId);
                    long regionId = rs.getLong("region_id");
                    if (!rs.wasNull()) {
                        sheet.cellRegion.put(cellId, regionId);
                    }
                    if (rs.getInt("is_scratch") == 1 || rs.getInt("is_orphan") == 1) {
                        sheet.scratchCells.add(cellId);
                    }
                }
            }
        }
    }

    private static void loadRegions(WorkspaceRepository repo, long parseRunId, Map<Long, SheetSnapshot> byId)
            throws SQLException {
        Map<Long, String> types = new HashMap<>();
        try (PreparedStatement ps = repo.connection().prepareStatement(
                "SELECT region_id, worksheet_id, region_type FROM region WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getLong("region_id"), rs.getString("region_type"));
                }
            }
        }
        for (SheetSnapshot sheet : byId.values()) {
            for (Map.Entry<Long, Long> entry : sheet.cellRegion.entrySet()) {
                String type = types.get(entry.getValue());
                if (type == null) {
                    continue;
                }
                if ("cost_head".equals(type)) {
                    sheet.costHeadCells.add(entry.getKey());
                }
                if (STATEMENT_TYPES.contains(type)) {
                    sheet.statementCells.add(entry.getKey());
                }
            }
        }
    }

    private static void loadContributions(WorkspaceRepository repo, long parseRunId,
            Map<Long, SheetSnapshot> byId) throws SQLException {
        try (PreparedStatement ps = repo.connection().prepareStatement(
                "SELECT contrib.cost_head_contribution_id, r.worksheet_id, cc.cell_id"
                        + " FROM cost_head_contribution contrib"
                        + " JOIN region r ON r.region_id = contrib.region_id"
                        + " LEFT JOIN cost_head_contribution_cell cc"
                        + " ON cc.cost_head_contribution_id = contrib.cost_head_contribution_id"
                        + " WHERE r.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<Long> seenContrib = new HashSet<>();
                while (rs.next()) {
                    SheetSnapshot sheet = byId.get(rs.getLong("worksheet_id"));
                    if (sheet == null) {
                        continue;
                    }
                    long contribId = rs.getLong("cost_head_contribution_id");
                    if (seenContrib.add(contribId)) {
                        sheet.contributionCount++;
                    }
                    long cellId = rs.getLong("cell_id");
                    if (!rs.wasNull()) {
                        sheet.contribCells.add(cellId);
                    }
                }
            }
        }
    }

    private static final class SheetSnapshot {
        final long worksheetId;
        final String sheetName;
        final Set<Long> cells = new HashSet<>();
        final Set<Long> scratchCells = new HashSet<>();
        final Set<Long> contribCells = new HashSet<>();
        final Set<Long> statementCells = new HashSet<>();
        final Set<Long> costHeadCells = new HashSet<>();
        final Map<Long, Long> cellRegion = new HashMap<>();
        int contributionCount;

        SheetSnapshot(long worksheetId, String sheetName) {
            this.worksheetId = worksheetId;
            this.sheetName = sheetName;
        }

        int primaryMass() {
            Set<Long> union = new HashSet<>(contribCells);
            union.addAll(statementCells);
            union.addAll(costHeadCells);
            return union.size();
        }

        int scratchMass() {
            return scratchCells.size();
        }

        boolean conflictingIdentity() {
            return !statementCells.isEmpty() && scratchMass() >= 2;
        }
    }
}
