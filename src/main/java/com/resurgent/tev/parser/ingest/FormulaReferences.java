package com.resurgent.tev.parser.ingest;

import java.util.List;

/**
 * Reference categories extracted from a single formula.
 *
 * @param externalRefs     verbatim external reference tokens such as {@code [1]Sheet!A1}
 * @param sheetRefs        local sheet references, if extracted
 * @param definedNameRefs  defined names referenced by this formula
 */
public record FormulaReferences(List<String> externalRefs, List<String> sheetRefs, List<String> definedNameRefs) {
}
