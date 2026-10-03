package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved context of any cell in a parse run (row and column labels, the group its row is
 * in, the status text beside it), built once from the stored cells, regions and header
 * geometry. Binding uses it so the model is told what a cell sits under even when its own
 * region's grid does not contain those labels.
 */
final class RunCellContext {

    private final ResolverCellContext context;
    private final Map<Long, InterpretationCellView> byId;

    private RunCellContext(ResolverCellContext context, Map<Long, InterpretationCellView> byId) {
        this.context = context;
        this.byId = byId;
    }

    static RunCellContext create(WorkspaceRepository repo, long parseRunId) throws SQLException {
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        Map<Long, InterpretationCellView> byId = InterpretationEvidenceResolver.indexCells(cells);
        Map<Long, Set<Long>> members = InterpretationEvidenceResolver.indexMembers(
                repo.selectCandidateMembersForParseRun(parseRunId));
        List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
        Map<Long, CandidateRow> candidatesById = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            candidatesById.put(candidate.candidateId(), candidate);
        }
        Map<Long, List<CandidateRow>> owners = InterpretationEvidenceResolver.indexOwners(candidates, members);
        InterpretationEvidenceResolver.ResolveCache cache = new InterpretationEvidenceResolver.ResolveCache(byId);
        cache.useGeometry(repo.selectHeaderGeometry(parseRunId));
        Map<Long, PacketDisposition> dispositions = new HashMap<>();
        for (PacketDisposition d : repo.selectPacketDispositionsForParseRun(parseRunId)) {
            dispositions.put(d.candidateId(), d);
        }
        Map<Long, String> sheetNames = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheetNames.put(sheet.worksheetId(), sheet.sheetName());
        }
        return new RunCellContext(
                new ResolverCellContext(
                        parseRunId, cache, owners, members, candidatesById, dispositions, sheetNames, "", null),
                byId);
    }

    /**
     * One line of context for a cell, or blank when it has none or is unknown. Regions are bound
     * from several threads at once and the resolver's caches are plain maps, so lookups take
     * turns; each is a quick in-memory read next to the model call it feeds.
     */
    synchronized String inline(long cellId) {
        InterpretationCellView cell = byId.get(cellId);
        if (cell == null) {
            return "";
        }
        return CellContextBlock.inline(new CellContextBlock.Parts(
                context.rowLabel(cell), context.columnLabel(cell), context.rowNotes(cell), context.partOf(cell)));
    }
}
