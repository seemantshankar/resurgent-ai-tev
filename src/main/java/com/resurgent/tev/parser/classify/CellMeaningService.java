package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellPacketView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Read path: one cell address → graph facts, Candidates, Layer A/B, peers, ProjectFacts,
 * optional Cell interpretation. */
public final class CellMeaningService {

    public CellMeaning lookup(Path dbPath, long parseRunId, String qualifiedCoord)
            throws ClassifyException {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(qualifiedCoord, "qualifiedCoord");
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new ClassifyException("database not found: " + absolute);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(absolute)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (!repo.parseRunExists(parseRunId)) {
                throw new ClassifyException("parse run not found: " + parseRunId);
            }
            List<WorksheetRef> worksheets = repo.selectWorksheetsForParseRun(parseRunId);
            ParsedAddress address = parseAddress(qualifiedCoord, worksheets);
            Optional<Long> cellId = PeerCoordResolver.resolveCellId(
                    worksheets,
                    PeerCoordResolver.indexCells(
                            worksheets,
                            worksheetId -> repo.selectCellsForWorksheet(worksheetId).stream()
                                    .map(ref -> new PeerCoordResolver.PeerCellRef(
                                            ref.cellId(), ref.coord()))
                                    .toList()),
                    address.worksheetId(),
                    qualifiedCoord);
            if (cellId.isEmpty()) {
                throw new ClassifyException("cell not found: " + qualifiedCoord);
            }
            List<CellPacketView> cells = repo.selectCellPacketViews(List.of(cellId.get()));
            if (cells.isEmpty()) {
                throw new ClassifyException("cell not found: " + qualifiedCoord);
            }
            CellPacketView cell = cells.get(0);
            List<CandidateRow> candidates = repo.selectCandidatesForCell(parseRunId, cellId.get());
            List<PacketDisposition> dispositions = new ArrayList<>();
            for (CandidateRow candidate : candidates) {
                repo.selectPacketDisposition(parseRunId, candidate.candidateId())
                        .ifPresent(dispositions::add);
            }
            NomenclatureBinding binding = repo.selectNomenclatureBindingForCell(parseRunId, cellId.get())
                    .orElse(null);
            List<BindingPeer> peers = repo.selectBindingPeersForCell(parseRunId, cellId.get());
            List<ProjectFactBinding> facts = repo.selectProjectFactBindingsForCell(parseRunId, cellId.get());
            CellInterpretation interpretation = repo.selectCellInterpretation(parseRunId, cellId.get())
                    .orElse(null);
            List<InterpretationEvidence> evidence = interpretation == null
                    ? List.of()
                    : repo.selectInterpretationEvidence(parseRunId, cellId.get());
            return new CellMeaning(
                    address.displayQualifiedCoord(cell.coord()),
                    cell,
                    candidates,
                    dispositions,
                    binding,
                    peers,
                    facts,
                    interpretation,
                    evidence);
        } catch (ClassifyException e) {
            throw e;
        } catch (SQLException e) {
            throw new ClassifyException("cell meaning lookup failed: " + e.getMessage(), e);
        } catch (PeerCoordResolver.PeerCoordException e) {
            throw new ClassifyException(e.getMessage(), e);
        }
    }

    private record ParsedAddress(long worksheetId, String sheetName) {
        String displayQualifiedCoord(String coord) {
            return sheetName + "!" + coord.toUpperCase(Locale.ROOT);
        }
    }

    private static ParsedAddress parseAddress(String qualifiedCoord, List<WorksheetRef> worksheets)
            throws ClassifyException {
        String trimmed = qualifiedCoord.trim();
        int bang = trimmed.indexOf('!');
        if (bang >= 0) {
            String sheetName = trimmed.substring(0, bang).trim();
            for (WorksheetRef worksheet : worksheets) {
                if (worksheet.sheetName().equalsIgnoreCase(sheetName)) {
                    return new ParsedAddress(worksheet.worksheetId(), worksheet.sheetName());
                }
            }
            throw new ClassifyException("worksheet not found in parse run: " + sheetName);
        }
        if (worksheets.size() == 1) {
            WorksheetRef only = worksheets.get(0);
            return new ParsedAddress(only.worksheetId(), only.sheetName());
        }
        throw new ClassifyException(
                "qualified coord required when parse run has multiple worksheets: " + trimmed);
    }
}
