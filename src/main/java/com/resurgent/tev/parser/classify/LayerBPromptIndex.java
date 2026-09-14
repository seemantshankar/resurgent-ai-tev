package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import java.util.List;
import java.util.Objects;

/**
 * Stable indices for a Layer B prompt: amount rows and ontology paths. The LLM
 * returns these indices; the parser resolves them back to coord/path/verbatim.
 */
record LayerBPromptIndex(
        List<AmountRow> amounts,
        List<NomenclatureNode> paths) {

    LayerBPromptIndex {
        amounts = List.copyOf(Objects.requireNonNull(amounts, "amounts"));
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }

    record AmountRow(
            int index,
            PacketCell cell,
            String label,
            String columnHeader,
            boolean formula,
            NumericKind kind) {
        AmountRow {
            Objects.requireNonNull(cell, "cell");
            label = label == null ? "" : label;
            columnHeader = columnHeader == null ? "" : columnHeader;
            kind = kind == null ? NumericKind.UNKNOWN : kind;
        }

        String coord() {
            return cell.coord();
        }
    }

    AmountRow amount(int index) {
        if (index < 0 || index >= amounts.size()) {
            throw new IllegalStateException("Layer B cellIndex out of range: " + index);
        }
        return amounts.get(index);
    }

    NomenclatureNode path(int index) {
        if (index < 0 || index >= paths.size()) {
            throw new IllegalStateException("Layer B pathIndex out of range: " + index);
        }
        return paths.get(index);
    }
}
