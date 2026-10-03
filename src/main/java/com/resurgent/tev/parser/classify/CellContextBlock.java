package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.List;

/**
 * The facts about a cell that every cell-level LLM call is given, written one way. A prompt that
 * decides about a cell (typing it, binding it) takes its labels, its row's notes and the group
 * it sits in from here, so what the model is told does not drift between calls.
 */
final class CellContextBlock {

    /** A cell's own labels, the text beside its row's amounts, and the group it belongs to. */
    record Parts(String rowLabel, String columnLabel, List<String> rowNotes, String partOf) {
        Parts {
            rowLabel = rowLabel == null ? "" : rowLabel;
            columnLabel = columnLabel == null ? "" : columnLabel;
            rowNotes = rowNotes == null ? List.of() : List.copyOf(rowNotes);
            partOf = partOf == null ? "" : partOf;
        }
    }

    private CellContextBlock() {}

    /** One fact per line, each starting with {@code indent}; nothing for a fact the cell lacks. */
    static String lines(Parts parts, String indent) {
        StringBuilder sb = new StringBuilder();
        append(sb, indent, "Row Label", parts.rowLabel());
        append(sb, indent, "Column Label", parts.columnLabel());
        append(sb, indent, "Part of", parts.partOf());
        append(sb, indent, "Row note", String.join("; ", parts.rowNotes()));
        return sb.toString();
    }

    /** The same facts on one line, for a prompt that lists many cells. */
    static String inline(Parts parts) {
        List<String> facts = new ArrayList<>();
        add(facts, "row", parts.rowLabel());
        add(facts, "column", parts.columnLabel());
        add(facts, "part of", parts.partOf());
        add(facts, "note", String.join("; ", parts.rowNotes()));
        return String.join(" | ", facts);
    }

    private static void append(StringBuilder sb, String indent, String name, String value) {
        if (!value.isBlank()) {
            sb.append(indent).append(name).append(": ").append(value).append('\n');
        }
    }

    private static void add(List<String> facts, String name, String value) {
        if (!value.isBlank()) {
            facts.add(name + ": " + value);
        }
    }
}
