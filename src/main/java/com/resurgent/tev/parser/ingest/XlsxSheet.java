package com.resurgent.tev.parser.ingest;

import java.util.List;

/**
 * One worksheet from an XLSX/XLSM workbook after extraction and normalization.
 */
public record XlsxSheet(String sheetName, int sheetIndex, String sheetState,
        List<NormalizedCell> cells) {
}
