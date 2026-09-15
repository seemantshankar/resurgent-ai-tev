package com.resurgent.tev.parser.classify;

import java.util.List;

/** LLM Layer A judgment for one Packet plus optional ProjectFact proposals. */
public record LayerAJudgment(
        String scheduleFamily,
        String triage,
        String relevance,
        List<String> rowLabels,
        List<String> columnHeaders,
        String packetDefaultHead,
        List<ProjectFactJudgment> facts) {

    public LayerAJudgment(
            String scheduleFamily,
            String triage,
            String relevance,
            List<String> rowLabels,
            List<String> columnHeaders,
            String packetDefaultHead) {
        this(scheduleFamily, triage, relevance, rowLabels, columnHeaders, packetDefaultHead, List.of());
    }

    public LayerAJudgment {
        rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
        columnHeaders = columnHeaders == null ? List.of() : List.copyOf(columnHeaders);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
