package com.resurgent.tev.parser.classify;

import java.util.List;

/**
 * Persisted Layer A row for one Candidate on a parse run. {@code statedScale} (a
 * {@link CellScale} wire name) and {@code scaleEvidenceCell} (e.g. {@code J6}) are what Layer A
 * reported about the unit the region's money is shown in; they are verified against the
 * cell before use and are null when none was reported.
 */
public record PacketDisposition(
        long candidateId,
        long parseRunId,
        String scheduleFamily,
        String triage,
        String relevance,
        List<String> rowLabels,
        List<String> columnHeaders,
        String packetDefaultHead,
        String about,
        Long parentCandidateId,
        boolean cheapPass,
        String statedScale,
        String scaleEvidenceCell) {

    /** A disposition with no stated scale (Layer A did not report one). */
    public PacketDisposition(
            long candidateId,
            long parseRunId,
            String scheduleFamily,
            String triage,
            String relevance,
            List<String> rowLabels,
            List<String> columnHeaders,
            String packetDefaultHead,
            String about,
            Long parentCandidateId,
            boolean cheapPass) {
        this(candidateId, parseRunId, scheduleFamily, triage, relevance, rowLabels, columnHeaders,
                packetDefaultHead, about, parentCandidateId, cheapPass, null, null);
    }

    public PacketDisposition {
        rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
        columnHeaders = columnHeaders == null ? List.of() : List.copyOf(columnHeaders);
        if (about == null || about.isBlank()) {
            throw new IllegalArgumentException("about must be non-blank");
        }
    }
}
