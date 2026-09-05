package com.resurgent.tev.parser.db;

/** Worksheet identity for a parse run (discover bulk read). */
public record WorksheetRef(
        long worksheetId,
        String sheetName,
        int sheetIndex,
        String sheetState) {
}
