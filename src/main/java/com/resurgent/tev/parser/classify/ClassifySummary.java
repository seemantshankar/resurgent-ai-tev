package com.resurgent.tev.parser.classify;

import java.util.List;

/** Human-facing result of a classify run: counts only, no Packet dump. */
public record ClassifySummary(
        long parseRunId,
        int dispositionCount,
        int coverageParentCount,
        int bindingCount,
        int interpretationCount,
        List<Long> unclassifiedCandidateIds,
        LayerBBindingStats layerBStats) {

    public ClassifySummary {
        if (layerBStats == null) {
            layerBStats = new LayerBBindingStats();
        }
        if (unclassifiedCandidateIds == null) {
            unclassifiedCandidateIds = List.of();
        }
    }

    public ClassifySummary(
            long parseRunId, int dispositionCount, int coverageParentCount, int bindingCount) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, 0, List.of(),
                new LayerBBindingStats());
    }

    public ClassifySummary(
            long parseRunId,
            int dispositionCount,
            int coverageParentCount,
            int bindingCount,
            int interpretationCount) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, interpretationCount,
                List.of(), new LayerBBindingStats());
    }

    public ClassifySummary(
            long parseRunId,
            int dispositionCount,
            int coverageParentCount,
            int bindingCount,
            int interpretationCount,
            LayerBBindingStats layerBStats) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, interpretationCount,
                List.of(), layerBStats);
    }

    public ClassifySummary(
            long parseRunId,
            int dispositionCount,
            int coverageParentCount,
            int bindingCount,
            LayerBBindingStats layerBStats) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, 0, List.of(),
                layerBStats);
    }
}
