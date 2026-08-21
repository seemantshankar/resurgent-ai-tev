package com.resurgent.tev.parser.ingest;

import java.util.List;

/**
 * Result of parsing an XLSX/XLSM workbook: normalized sheets plus workbook-level
 * metadata required by the {@code workbook} and {@code external_link} tables.
 */
public record XlsxWorkbook(List<XlsxSheet> sheets, WorkbookMetadata metadata) {
}
