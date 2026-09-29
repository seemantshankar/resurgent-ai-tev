package com.resurgent.tev.parser.db;

/** One Candidate member loaded for Layer B. */
public record BindCellRow(
        long cellId,
        String coord,
        int rowNum,
        int colNum,
        String valueType,
        String textValue,
        String formulaText,
        boolean error,
        boolean mergedParticipant,
        String mergedRange) {
}
