package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Production {@link CellContext}: labels come from the same candidate-scoped resolver the
 * main typing pass uses, so the LLM fallback sees the headers the rest of the pipeline sees,
 * and the region is the Layer A disposition of the narrowest owning candidate that has one.
 */
final class ResolverCellContext implements CellContext {

    private final long parseRunId;
    private final InterpretationEvidenceResolver.ResolveCache cache;
    private final Map<Long, List<CandidateRow>> owners;
    private final Map<Long, Set<Long>> members;
    private final Map<Long, CandidateRow> candidatesById;
    private final Map<Long, PacketDisposition> dispositions;
    private final Map<Long, String> sheetNames;
    private final String workbookKey;
    private final Map<Long, String[]> labelMemo = new HashMap<>();
    private final StatedScales statedScales;

    ResolverCellContext(
            long parseRunId,
            InterpretationEvidenceResolver.ResolveCache cache,
            Map<Long, List<CandidateRow>> owners,
            Map<Long, Set<Long>> members,
            Map<Long, CandidateRow> candidatesById,
            Map<Long, PacketDisposition> dispositionsByCandidate,
            Map<Long, String> sheetNamesByWorksheet,
            String workbookKey,
            StatedScales statedScales) {
        this.statedScales = statedScales;
        this.parseRunId = parseRunId;
        this.cache = cache;
        this.owners = owners;
        this.members = members;
        this.candidatesById = candidatesById;
        this.dispositions = dispositionsByCandidate;
        this.sheetNames = sheetNamesByWorksheet;
        this.workbookKey = workbookKey == null ? "" : workbookKey;
    }

    @Override
    public String rowLabel(InterpretationCellView cell) {
        return labels(cell)[0];
    }

    @Override
    public String columnLabel(InterpretationCellView cell) {
        return labels(cell)[1];
    }

    private String[] labels(InterpretationCellView cell) {
        return labelMemo.computeIfAbsent(cell.cellId(), id -> {
            String[] texts = InterpretationEvidenceResolver.resolvedHeaderTexts(
                    parseRunId, cell, cache, owners, members, candidatesById);
            return new String[] {
                texts[0] == null ? "" : texts[0], texts[1] == null ? "" : texts[1]
            };
        });
    }

    @Override
    public List<String> rowNotes(InterpretationCellView cell) {
        HeaderGeometry geometry = cache.geometryFor(owners.getOrDefault(cell.cellId(), List.of()));
        return geometry == null
                ? List.of()
                : RowContext.annotations(geometry, cell, (row, col) -> cache.cellAt(cell.worksheetId(), row, col));
    }

    @Override
    public String partOf(InterpretationCellView cell) {
        HeaderGeometry geometry = cache.geometryFor(owners.getOrDefault(cell.cellId(), List.of()));
        CandidateRow region = geometry == null ? null : candidatesById.get(geometry.candidateId());
        if (region == null || region.bboxMinRow() == null || region.bboxMaxRow() == null) {
            return null;
        }
        return RowContext.parent(
                geometry, cell, (row, col) -> cache.cellAt(cell.worksheetId(), row, col),
                region.bboxMinRow(), region.bboxMaxRow());
    }

    @Override
    public RegionContext region(InterpretationCellView cell) {
        List<CandidateRow> owned = owners.getOrDefault(cell.cellId(), List.of());
        List<CandidateRow> narrow = new ArrayList<>();
        List<CandidateRow> parents = new ArrayList<>();
        for (CandidateRow c : owned) {
            ("coverage_parent".equals(c.candidateKind()) ? parents : narrow).add(c);
        }
        narrow.sort(Comparator.comparingInt(ResolverCellContext::area).thenComparingLong(CandidateRow::candidateId));
        for (CandidateRow c : narrow.isEmpty() ? parents : narrow) {
            PacketDisposition d = dispositions.get(c.candidateId());
            if (d != null) {
                String sentence = statedScales == null ? "" : statedScales.sentence(cell);
                return new RegionContext(
                        c.candidateId(),
                        d.scheduleFamily(),
                        sentence.isEmpty() ? d.about() : d.about() + " " + sentence + ".",
                        d.packetDefaultHead(),
                        sheetNames.getOrDefault(cell.worksheetId(), ""),
                        Triage.MAIN.equals(d.triage()) && !Relevance.NOISE.equals(d.relevance()));
            }
        }
        return RegionContext.NONE;
    }

    @Override
    public CellScale statedScale(InterpretationCellView cell) {
        return statedScales == null ? null : statedScales.of(cell);
    }

    @Override
    public String sheetName(InterpretationCellView cell) {
        return sheetNames.getOrDefault(cell.worksheetId(), "");
    }

    @Override
    public String workbookKey() {
        return workbookKey;
    }

    private static int area(CandidateRow c) {
        if (c.bboxMinRow() == null || c.bboxMaxRow() == null || c.bboxMinCol() == null || c.bboxMaxCol() == null) {
            return Integer.MAX_VALUE;
        }
        return (c.bboxMaxRow() - c.bboxMinRow() + 1) * (c.bboxMaxCol() - c.bboxMinCol() + 1);
    }
}
