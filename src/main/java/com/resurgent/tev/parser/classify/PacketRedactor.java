package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.ingest.CellNormalizer;
import com.resurgent.tev.parser.redact.DummyValueMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Number-redacts a Packet for LLM send. Formulas and labels stay; dummy amounts
 * replace numeric literals. Real values remain on the cell graph.
 */
final class PacketRedactor {

    private PacketRedactor() {}

    static Packet redact(Packet packet, boolean cheapPass) {
        List<PacketCell> cells = new ArrayList<>();
        for (PacketCell cell : packet.cells()) {
            if (cheapPass && isAmountLine(cell)) {
                continue;
            }
            cells.add(redactCell(cell));
        }
        return new Packet(
                packet.candidateId(),
                packet.parseRunId(),
                packet.worksheetId(),
                packet.candidateKind(),
                List.copyOf(cells),
                packet.largeRangeRefs(),
                packet.contextClosureSucceeded());
    }

    /** Coverage-parent cheap pass keeps labels/formulas, not rupee line dumps. */
    private static boolean isAmountLine(PacketCell cell) {
        if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
            return false;
        }
        return "number".equals(cell.valueType());
    }

    private static PacketCell redactCell(PacketCell cell) {
        String numeric = cell.numericValue();
        String display = cell.displayValue();
        String text = cell.textValue();
        if (numeric != null) {
            try {
                double dummy = DummyValueMapper.dummyNumeric(Double.parseDouble(numeric), cell.coord());
                numeric = BigDecimal.valueOf(dummy).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                numeric = null;
            }
        }
        if (display != null && CellNormalizer.coerceNumericText(display) != null) {
            display = DummyValueMapper.dummyAmountText(display, cell.coord());
        }
        if (text != null && CellNormalizer.coerceNumericText(text) != null) {
            text = DummyValueMapper.dummyAmountText(text, cell.coord());
        }
        return new PacketCell(
                cell.cellId(),
                cell.worksheetId(),
                cell.coord(),
                cell.rowNum(),
                cell.colNum(),
                cell.role(),
                cell.valueType(),
                text,
                display,
                numeric,
                cell.formulaText(),
                cell.rowHidden(),
                cell.colHidden());
    }
}
