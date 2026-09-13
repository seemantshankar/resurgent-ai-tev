package com.resurgent.tev.parser.classify;

import java.util.List;

/** Persisted Layer A row for one Candidate on a parse run. */
public record PacketDisposition(
        long candidateId,
        long parseRunId,
        String scheduleFamily,
        String triage,
        String relevance,
        List<String> rowLabels,
        List<String> columnHeaders,
        String packetDefaultHead,
        Long parentCandidateId,
        boolean cheapPass) {

    public PacketDisposition {
        rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
        columnHeaders = columnHeaders == null ? List.of() : List.copyOf(columnHeaders);
    }
}
