package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import java.util.List;

/**
 * Payload sent to the LLM for Layer A: a number-redacted Packet plus structural
 * role and offered schedule families. No ontology slice in this experiment slice.
 */
public record LayerAPrompt(
        Packet packet,
        String structuralRole,
        String sheetName,
        List<String> scheduleFamilies) {

    public LayerAPrompt(Packet packet, String structuralRole, String sheetName) {
        this(packet, structuralRole, sheetName, ScheduleFamily.seeds());
    }

    public LayerAPrompt {
        scheduleFamilies = scheduleFamilies == null || scheduleFamilies.isEmpty()
                ? ScheduleFamily.seeds()
                : List.copyOf(scheduleFamilies);
    }
}
