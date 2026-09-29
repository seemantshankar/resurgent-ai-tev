package com.resurgent.tev.parser.classify;

import java.util.List;

/** Narrow port for region layout + Layer A. Tests inject a fake. */
public interface ClassifierLlm {

    /**
     * Propose main/helper/scratch bboxes for one worksheet. Return an empty list
     * to leave existing Candidates unchanged (test fakes).
     */
    List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt);

    LayerAJudgment classifyLayerA(LayerAPrompt prompt);

    /**
     * Name still-unbound cells in one Candidate. Default returns nothing so
     * region-layout fakes stay valid; the binder keeps what the graph already proved.
     */
    default List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        return List.of();
    }
}
