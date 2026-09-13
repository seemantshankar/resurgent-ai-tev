package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.Objects;

/** Inputs for Layer B money-line binding on one Packet. */
public record LayerBPrompt(
        Packet packet,
        OntologySlice ontologySlice,
        LayerAJudgment layerA,
        LayerAJudgment parentDisposition) {

    public LayerBPrompt {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(ontologySlice, "ontologySlice");
        Objects.requireNonNull(layerA, "layerA");
    }
}
