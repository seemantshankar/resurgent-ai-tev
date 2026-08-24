package com.resurgent.tev.parser.ingest;

/**
 * Accounting facts behind Sprint 3b semantic QA. Pending review is allowed;
 * unaccounted artifacts are the only semantic shortfall.
 */
public record SemanticQaStats(
        int mappingsUnaccounted,
        int contributionsUnaccounted,
        int contributionArithmeticMismatches,
        int candidatesUnaccounted,
        int duplicatesUnaccounted,
        int scratchUnaccounted,
        int worksheetRolesUnaccounted) {
}
