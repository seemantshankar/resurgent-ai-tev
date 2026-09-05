package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CandidateWithMembers;
import com.resurgent.tev.parser.db.CandidateWrite;
import com.resurgent.tev.parser.db.CellCoordRef;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Region discovery application service: reads one parse run from SQLite and writes
 * Candidates. Ticket #90: one coverage parent per worksheet; Packets stay on demand later.
 */
public final class DiscoverService {

    public DiscoverSummary discover(Path dbPath, long parseRunId) throws DiscoverException {
        Objects.requireNonNull(dbPath, "dbPath");
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new DiscoverException("database not found: " + absolute);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(absolute)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (!repo.parseRunExists(parseRunId)) {
                throw new DiscoverException("parse run not found: " + parseRunId);
            }

            List<WorksheetRef> worksheets = repo.selectWorksheetsForParseRun(parseRunId);
            List<CandidateWithMembers> batch = new ArrayList<>();
            int isolatedHidden = 0;

            for (WorksheetRef worksheet : worksheets) {
                List<CellCoordRef> cells = repo.selectCellsForWorksheet(worksheet.worksheetId());
                boolean isolated = isIsolatedHidden(repo, parseRunId, worksheet);
                if (isolated) {
                    isolatedHidden++;
                }
                batch.add(coverageParent(parseRunId, worksheet, cells, isolated));
            }

            db.connection().setAutoCommit(false);
            try {
                repo.replaceCandidatesForParseRun(parseRunId, batch);
                boolean coverageOk = verifyCoverage(repo, parseRunId, worksheets);
                if (!coverageOk) {
                    throw new DiscoverException(
                            "coverage check failed: a worksheet's coverage parent omitted a cell");
                }
                repo.commit();
                return new DiscoverSummary(
                        parseRunId,
                        worksheets.size(),
                        batch.size(),
                        isolatedHidden,
                        true);
            } catch (SQLException | DiscoverException e) {
                repo.rollback();
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }
        } catch (DiscoverException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new DiscoverException("discover failed: " + msg, e);
        }
    }

    private static boolean isIsolatedHidden(
            WorkspaceRepository repo, long parseRunId, WorksheetRef worksheet)
            throws SQLException {
        if (!isHiddenSheetState(worksheet.sheetState())) {
            return false;
        }
        return !repo.worksheetHasEdgeToOrFromVisibleSheet(parseRunId, worksheet.worksheetId());
    }

    private static boolean isHiddenSheetState(String sheetState) {
        return "hidden".equals(sheetState) || "veryHidden".equals(sheetState);
    }

    private static CandidateWithMembers coverageParent(
            long parseRunId,
            WorksheetRef worksheet,
            List<CellCoordRef> cells,
            boolean isolatedHidden) {
        Integer minRow = null;
        Integer minCol = null;
        Integer maxRow = null;
        Integer maxCol = null;
        List<Long> memberIds = new ArrayList<>(cells.size());
        for (CellCoordRef cell : cells) {
            memberIds.add(cell.cellId());
            minRow = minRow == null ? cell.rowNum() : Math.min(minRow, cell.rowNum());
            minCol = minCol == null ? cell.colNum() : Math.min(minCol, cell.colNum());
            maxRow = maxRow == null ? cell.rowNum() : Math.max(maxRow, cell.rowNum());
            maxCol = maxCol == null ? cell.colNum() : Math.max(maxCol, cell.colNum());
        }
        String explanation = isolatedHidden
                ? "Coverage parent for isolated hidden worksheet '" + worksheet.sheetName() + "'"
                : "Coverage parent for worksheet '" + worksheet.sheetName() + "'";
        CandidateWrite write = new CandidateWrite(
                parseRunId,
                worksheet.worksheetId(),
                "coverage_parent",
                null,
                minRow,
                minCol,
                maxRow,
                maxCol,
                null,
                null,
                null,
                isolatedHidden,
                1.0,
                "mandatory coverage parent for every persisted cell on the worksheet",
                explanation);
        return new CandidateWithMembers(write, memberIds);
    }

    private static boolean verifyCoverage(
            WorkspaceRepository repo, long parseRunId, List<WorksheetRef> worksheets)
            throws SQLException {
        for (WorksheetRef worksheet : worksheets) {
            List<CellCoordRef> cells = repo.selectCellsForWorksheet(worksheet.worksheetId());
            var candidates = repo.selectCandidatesForParseRun(parseRunId).stream()
                    .filter(c -> c.worksheetId() == worksheet.worksheetId()
                            && "coverage_parent".equals(c.candidateKind()))
                    .toList();
            if (candidates.size() != 1) {
                return false;
            }
            List<Long> members = repo.selectCandidateMemberCellIds(candidates.get(0).candidateId());
            if (members.size() != cells.size()) {
                return false;
            }
            for (CellCoordRef cell : cells) {
                if (!members.contains(cell.cellId())) {
                    return false;
                }
            }
        }
        return true;
    }
}
