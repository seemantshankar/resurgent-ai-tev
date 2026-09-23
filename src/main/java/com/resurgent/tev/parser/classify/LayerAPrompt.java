package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.nomenclature.OntologySlice;

/**
 * Payload sent to the LLM for Layer A: a number-redacted Packet, the ontology
 * slice, and parent disposition when the cheap coverage-parent pass already ran.
 */
public record LayerAPrompt(
        Packet packet,
        OntologySlice ontologySlice,
        LayerAJudgment parentDisposition,
        boolean cheapPass,
        java.util.List<String> scheduleFamilies) {

    public LayerAPrompt(
            Packet packet,
            OntologySlice ontologySlice,
            LayerAJudgment parentDisposition,
            boolean cheapPass) {
        this(packet, ontologySlice, parentDisposition, cheapPass, ScheduleFamily.seeds());
    }
}
