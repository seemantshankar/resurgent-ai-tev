package com.resurgent.tev.parser.classify;

/** Human-facing result of a classify run: counts only, no Packet dump. */
public record ClassifySummary(
        long parseRunId,
        int dispositionCount,
        int coverageParentCount,
        int bindingCount,
        int interpretationCount,
        LayerBBindingStats layerBStats) {

    public ClassifySummary {
        if (layerBStats == null) {
            layerBStats = new LayerBBindingStats();
        }
    }

    public ClassifySummary(
            long parseRunId, int dispositionCount, int coverageParentCount, int bindingCount) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, 0, new LayerBBindingStats());
    }

    public ClassifySummary(
            long parseRunId,
            int dispositionCount,
            int coverageParentCount,
            int bindingCount,
            LayerBBindingStats layerBStats) {
        this(parseRunId, dispositionCount, coverageParentCount, bindingCount, 0, layerBStats);
    }
}
