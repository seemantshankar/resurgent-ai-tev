package com.resurgent.tev.parser.classify;

/**
 * What Layer A said about the region a cell sits in: its schedule family and the prose
 * describing it. This is what lets a label with no unit or currency word ("Firefighting &
 * Misc.") be typed as money, and it scopes what the learned dictionary may remember.
 *
 * @param learnable whether the region is a main, non-noise packet; scratch/orphan/noise
 *     regions are described to the LLM but never train the dictionary
 */
record RegionContext(
        long candidateId,
        String scheduleFamily,
        String about,
        String packetHead,
        String sheetName,
        boolean learnable) {

    static final RegionContext NONE = new RegionContext(-1L, "", "", "", "", false);

    RegionContext {
        scheduleFamily = scheduleFamily == null ? "" : scheduleFamily;
        about = about == null ? "" : about;
        packetHead = packetHead == null ? "" : packetHead;
        sheetName = sheetName == null ? "" : sheetName;
    }

    boolean known() {
        return candidateId >= 0 && !scheduleFamily.isBlank();
    }
}
