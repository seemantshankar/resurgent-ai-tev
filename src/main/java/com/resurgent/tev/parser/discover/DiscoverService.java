package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CandidateWithMembers;
import com.resurgent.tev.parser.db.CandidateWrite;
import com.resurgent.tev.parser.db.CellCoordRef;
import com.resurgent.tev.parser.db.CellEvidence;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Region discovery application service: reads one parse run from SQLite and writes
 * Candidates. Coverage parent plus local/related structure; Packets on demand (#93).
 */
public final class DiscoverService {

    private final LocalStructureDiscoverer localStructure = new LocalStructureDiscoverer();
    private final RelatedCandidateLinker relatedLinker = new RelatedCandidateLinker();
    private final PacketBuilder packetBuilder = new PacketBuilder();

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
            int isolatedHidden = 0;
            int candidateCount = 0;

            db.connection().setAutoCommit(false);
            try {
                repo.deleteCandidatesForParseRun(parseRunId);

                for (WorksheetRef worksheet : worksheets) {
                    List<CellEvidence> evidence = repo.selectCellEvidenceForWorksheet(
                            worksheet.worksheetId());
                    boolean isolated = isIsolatedHidden(repo, parseRunId, worksheet);
                    if (isolated) {
                        isolatedHidden++;
                    }
                    CandidateWithMembers coverage = coverageParent(
                            parseRunId, worksheet, evidence, isolated);
                    long coverageId = repo.insertCandidate(coverage.write(), coverage.memberCellIds());
                    candidateCount++;

                    Set<Long> coverageMembers = new HashSet<>(coverage.memberCellIds());
                    for (LocalStructureDiscoverer.NarrowCandidate narrow :
                            localStructure.discover(evidence)) {
                        boolean sameAsCoverage = narrow.memberCellIds().size() == coverageMembers.size()
                                && coverageMembers.containsAll(narrow.memberCellIds());
                        // Overlap may share coverage membership when the sheet is only the
                        // parallel bands — still keep it so wide + narrow both remain.
                        if (sameAsCoverage && !"overlap".equals(narrow.kind())) {
                            continue;
                        }
                        CandidateWrite write = new CandidateWrite(
                                parseRunId,
                                worksheet.worksheetId(),
                                narrow.kind(),
                                coverageId,
                                narrow.bboxMinRow(),
                                narrow.bboxMinCol(),
                                narrow.bboxMaxRow(),
                                narrow.bboxMaxCol(),
                                null,
                                null,
                                null,
                                false,
                                narrow.structuralConfidence(),
                                narrow.structuralConfidenceRationale(),
                                narrow.explanation());
                        repo.insertCandidate(write, narrow.memberCellIds());
                        candidateCount++;
                    }
                }

                relatedLinker.link(repo, parseRunId);

                boolean coverageOk = verifyCoverage(repo, parseRunId, worksheets);
                if (!coverageOk) {
                    throw new DiscoverException(
                            "coverage check failed: a worksheet's coverage parent omitted a cell");
                }
                repo.commit();
                return new DiscoverSummary(
                        parseRunId,
                        worksheets.size(),
                        candidateCount,
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

    /** Build a Packet for any Candidate on demand (amounts read from the cell graph). */
    public Packet buildPacket(Path dbPath, long candidateId) throws DiscoverException {
        Objects.requireNonNull(dbPath, "dbPath");
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new DiscoverException("database not found: " + absolute);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(absolute)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            return packetBuilder.build(repo, candidateId);
        } catch (DiscoverException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new DiscoverException("packet build failed: " + msg, e);
        }
    }

    /**
     * Default Packet selection for a parse run after discover: every non-coverage Candidate;
     * coverage-parent Packet only when sole Candidate on that sheet or a child fails closure.
     */
    public List<Packet> selectDefaultPackets(Path dbPath, long parseRunId) throws DiscoverException {
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
            return packetBuilder.selectDefault(repo, parseRunId);
        } catch (DiscoverException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new DiscoverException("packet selection failed: " + msg, e);
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
            List<CellEvidence> cells,
            boolean isolatedHidden) {
        Integer minRow = null;
        Integer minCol = null;
        Integer maxRow = null;
        Integer maxCol = null;
        List<Long> memberIds = new ArrayList<>(cells.size());
        for (CellEvidence cell : cells) {
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
