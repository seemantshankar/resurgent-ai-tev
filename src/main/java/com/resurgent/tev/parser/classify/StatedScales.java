package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scale each cell's region (Layer A, verified) or sheet title states for money.
 */
final class StatedScales {
    private final Map<Long, CellScale> sheet;
    private final Map<Long, List<CandidateRow>> owners;
    private final Map<Long, PacketDisposition> dispositions;
    private final Map<String, InterpretationCellView> byCoord = new HashMap<>();
    private final Map<Long, CellScale> verified = new HashMap<>();

    StatedScales(
            List<InterpretationCellView> cells,
            Map<Long, List<CandidateRow>> owners,
            Map<Long, PacketDisposition> dispositions) {
        this.sheet = SheetScaleStatement.byWorksheet(cells);
        this.owners = owners;
        this.dispositions = dispositions;
        for (InterpretationCellView cell : cells) {
            byCoord.put(cell.worksheetId() + "!" + cell.coord(), cell);
        }
    }

    CellScale of(InterpretationCellView cell) {
        CellScale region = region(cell);
        return region != null ? region : sheet.get(cell.worksheetId());
    }

    /** One sentence for the LLM prompt, or empty when no scale is stated for the cell. */
    String sentence(InterpretationCellView cell) {
        CellScale scale = of(cell);
        return scale == null
                ? ""
                : "Amounts here are stated in " + (scale == CellScale.UNIT ? "rupees" : scale.wireName() + "s")
                        + " (as the sheet itself says)";
    }

    private CellScale region(InterpretationCellView cell) {
        List<CandidateRow> owned = owners.getOrDefault(cell.cellId(), List.of());
        CandidateRow best = null;
        for (CandidateRow c : owned) {
            PacketDisposition d = dispositions.get(c.candidateId());
            if (d == null || d.statedScale() == null || verifiedScale(c, d) == null) {
                continue;
            }
            if (best == null || area(c) < area(best)) {
                best = c;
            }
        }
        return best == null ? null : verifiedScale(best, dispositions.get(best.candidateId()));
    }

    /** The scale Layer A reported, only if the cell it cites really says so. */
    private CellScale verifiedScale(CandidateRow candidate, PacketDisposition d) {
        return verified.computeIfAbsent(candidate.candidateId(), id -> {
            InterpretationCellView evidence = d.scaleEvidenceCell() == null
                    ? null
                    : byCoord.get(candidate.worksheetId() + "!" + d.scaleEvidenceCell().strip().toUpperCase(Locale.ROOT));
            CellScale found = evidence == null ? null : SheetScaleStatement.statedBy(evidence.textValue());
            CellScale claimed;
            try {
                claimed = CellScale.fromWire(d.statedScale());
            } catch (IllegalArgumentException e) {
                return null;
            }
            return found != null && found == claimed ? found : null;
        });
    }

    private static int area(CandidateRow c) {
        if (c.bboxMinRow() == null || c.bboxMaxRow() == null || c.bboxMinCol() == null || c.bboxMaxCol() == null) {
            return Integer.MAX_VALUE;
        }
        return (c.bboxMaxRow() - c.bboxMinRow() + 1) * (c.bboxMaxCol() - c.bboxMinCol() + 1);
    }
}

