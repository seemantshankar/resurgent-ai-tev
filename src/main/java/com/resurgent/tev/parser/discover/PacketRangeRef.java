package com.resurgent.tev.parser.discover;

/**
 * Formula target too large to inline (more than {@link PacketBuilder#INLINE_FORMULA_CELL_CAP}
 * persisted cells). Recorded as range address + edge identity only.
 */
public record PacketRangeRef(
        long fromCellId,
        long cellReferenceEdgeId,
        String targetRange,
        Long targetWorksheetId,
        int persistedCellCount) {
}
