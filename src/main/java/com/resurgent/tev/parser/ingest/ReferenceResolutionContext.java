package com.resurgent.tev.parser.ingest;

import java.util.Map;
import java.util.Set;

/**
 * Data clump for {@link ReferenceResolver#resolveAndPersist}: the workbook-scoped lookup
 * tables and identifiers that travel together across every call for one ingest run.
 */
public record ReferenceResolutionContext(
        Map<String, Long> sheetNameToId,
        Map<Long, String> worksheetIdToSheetName,
        Map<Integer, Long> externalLinkMap,
        Map<String, Map<String, Long>> cellCoordMap,
        Set<String> knownDefinedNames,
        long parseRunId,
        String now
) {}
