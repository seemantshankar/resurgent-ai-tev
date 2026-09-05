package com.resurgent.tev.parser.discover;

/** One cell in a Packet, marked core (Candidate member) or context (appended). */
public record PacketCell(
        long cellId,
        long worksheetId,
        String coord,
        int rowNum,
        int colNum,
        String role,
        String valueType,
        String textValue,
        String displayValue,
        String numericValue,
        String formulaText,
        boolean rowHidden,
        boolean colHidden) {

    public static final String ROLE_CORE = "core";
    public static final String ROLE_CONTEXT = "context";
}
