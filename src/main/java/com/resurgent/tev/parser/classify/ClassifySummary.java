package com.resurgent.tev.parser.classify;

/** Human-facing result of a classify run: counts only, no Packet dump. */
public record ClassifySummary(long parseRunId, int dispositionCount, int coverageParentCount) {}
