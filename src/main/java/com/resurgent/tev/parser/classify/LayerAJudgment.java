package com.resurgent.tev.parser.classify;

import java.util.List;

/** LLM Layer A judgment for one Packet, including free-text region about. */
public record LayerAJudgment(
        String scheduleFamily,
        String triage,
        String relevance,
        List<String> rowLabels,
        List<String> columnHeaders,
        String packetDefaultHead,
        String about) {

    public LayerAJudgment {
        rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
        columnHeaders = columnHeaders == null ? List.of() : List.copyOf(columnHeaders);
        if (about == null || about.isBlank()) {
            throw new IllegalArgumentException("about must be non-blank");
        }
    }
}
