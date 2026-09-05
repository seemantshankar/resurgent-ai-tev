package com.resurgent.tev.parser.db;

/**
 * Cell values loaded for on-demand Packet construction (amounts stay on the cell graph).
 */
public record CellPacketView(
        long cellId,
        long worksheetId,
        String coord,
        int rowNum,
        int colNum,
        String valueType,
        String textValue,
        String displayValue,
        String numericValue,
        String formulaText,
        boolean rowHidden,
        boolean colHidden) {
}
