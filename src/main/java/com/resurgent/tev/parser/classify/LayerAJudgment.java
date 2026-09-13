package com.resurgent.tev.parser.classify;

import java.util.List;

/** LLM Layer A judgment for one Packet. No nomenclature line bindings. */
public record LayerAJudgment(
        String scheduleFamily,
        String triage,
        String relevance,
        List<String> rowLabels,
        List<String> columnHeaders,
        String packetDefaultHead) {

    public LayerAJudgment {
        rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
        columnHeaders = columnHeaders == null ? List.of() : List.copyOf(columnHeaders);
    }
}
