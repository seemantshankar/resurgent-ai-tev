package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.WorkspaceRepository;
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
        List<SheetSnapshot> sheets = loadSheets(repo.findWorksheetRoleSheets(parseRunId));
        if (sheets.isEmpty()) {
            return List.of();
        }
        Map<Long, SheetSnapshot> byId = new LinkedHashMap<>();
        for (SheetSnapshot sheet : sheets) {
            byId.put(sheet.worksheetId, sheet);
        }
        loadCells(repo.findWorksheetRoleCells(parseRunId), byId);
        loadRegions(repo.findWorksheetRoleRegions(parseRunId), byId);
        loadContributions(repo.findWorksheetRoleContributions(parseRunId), byId);
        Map<Long, Long> worksheetIdByCellId = worksheetIdByCellId(sheets);
        Set<Long> primaryIds = new HashSet<>();
        for (SheetSnapshot sheet : sheets) {
            if (sheet.primaryMass() > 0 && !sheet.conflictingIdentity()) {
                primaryIds.add(sheet.worksheetId);
            }
        }
        Map<Long, Integer> feederMass = feederMassBySheet(repo, parseRunId, worksheetIdByCellId, primaryIds);
        boolean grew = true;
        Set<Long> primaryAndSupportIds = new HashSet<>(primaryIds);
        while (grew) {
            grew = false;
            Map<Long, Integer> next = feederMassBySheet(repo, parseRunId, worksheetIdByCellId, primaryAndSupportIds);
            for (Map.Entry<Long, Integer> entry : next.entrySet()) {
                feederMass.merge(entry.getKey(), entry.getValue(), Math::max);
                if (entry.getValue() > 0 && primaryAndSupportIds.add(entry.getKey())) {
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
            WorkspaceRepository repo, long parseRunId, Map<Long, Long> worksheetIdByCellId, Set<Long> sinks)
            throws SQLException {
        Map<Long, Integer> mass = new HashMap<>();
        Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
        for (Map.Entry<Long, List<Long>> entry : adjacency.entrySet()) {
            Long fromSheet = worksheetIdByCellId.get(entry.getKey());
            if (fromSheet == null || !sinks.contains(fromSheet)) {
                continue;
            }
            for (Long precedent : entry.getValue()) {
                Long toSheet = worksheetIdByCellId.get(precedent);
                if (toSheet != null && !toSheet.equals(fromSheet)) {
                    mass.merge(toSheet, 1, Integer::sum);
                }
            }
        }
        return mass;
    }

    private static Map<Long, Long> worksheetIdByCellId(List<SheetSnapshot> sheets) {
        Map<Long, Long> index = new HashMap<>();
        for (SheetSnapshot sheet : sheets) {
            for (long cellId : sheet.cells) {
                index.put(cellId, sheet.worksheetId);
            }
        }
        return index;
    }

    private static List<SheetSnapshot> loadSheets(List<WorkspaceRepository.WorksheetRoleSheetRow> rows) {
        List<SheetSnapshot> sheets = new ArrayList<>();
        for (WorkspaceRepository.WorksheetRoleSheetRow row : rows) {
            sheets.add(new SheetSnapshot(row.worksheetId(), row.sheetName()));
        }
        return sheets;
    }

    private static void loadCells(
            List<WorkspaceRepository.WorksheetRoleCellRow> rows, Map<Long, SheetSnapshot> byId) {
        for (WorkspaceRepository.WorksheetRoleCellRow row : rows) {
            SheetSnapshot sheet = byId.get(row.worksheetId());
            if (sheet == null) {
                continue;
            }
            sheet.cells.add(row.cellId());
            if (row.regionId() != null) {
                sheet.cellRegion.put(row.cellId(), row.regionId());
            }
            if (row.scratchOrOrphan()) {
                sheet.scratchCells.add(row.cellId());
            }
        }
    }

    private static void loadRegions(
            List<WorkspaceRepository.WorksheetRoleRegionRow> rows, Map<Long, SheetSnapshot> byId) {
        Map<Long, String> types = new HashMap<>();
        for (WorkspaceRepository.WorksheetRoleRegionRow row : rows) {
            types.put(row.regionId(), row.regionType());
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

    private static void loadContributions(
            List<WorkspaceRepository.WorksheetRoleContributionRow> rows, Map<Long, SheetSnapshot> byId) {
        Set<Long> seenContrib = new HashSet<>();
        for (WorkspaceRepository.WorksheetRoleContributionRow row : rows) {
            SheetSnapshot sheet = byId.get(row.worksheetId());
            if (sheet == null) {
                continue;
            }
            if (seenContrib.add(row.contributionId())) {
                sheet.contributionCount++;
            }
            if (row.cellId() != null) {
                sheet.contribCells.add(row.cellId());
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
            int primary = primaryMass();
            int scratch = scratchMass();
            return primary > 0 && scratch > 0 && primary <= scratch;
        }
    }
}
