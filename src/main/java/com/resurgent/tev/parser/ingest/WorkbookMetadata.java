package com.resurgent.tev.parser.ingest;

import java.util.List;
import java.util.Map;

/**
 * High-level metadata extracted from an XLSX/XLSM workbook.
 *
 * <p>Kept separate from {@link XlsxSheet} so that the ingestion service can
 * persist a {@code workbook} row without re-parsing the package.
 */
public record WorkbookMetadata(
        String applicationName,
        String applicationVersion,
        int sheetCount,
        List<String> sheetNames,
        Map<String, String> definedNames,
        Map<String, Object> properties,
        boolean isProtected,
        String createdAt,
        String modifiedAt,
        List<ExternalLinkIn> externalLinks,
        String calculationMode,
        Boolean fullCalcOnLoad,
        Boolean calcChainPresent,
        boolean iterativeCalc,
        Integer iterativeCount) {
}
