package com.resurgent.tev.parser.classify;

import java.util.List;

/** Production default until a live provider is wired. Tests inject a fake. */
public final class UnconfiguredClassifierLlm implements ClassifierLlm {

    @Override
    public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
        throw new IllegalStateException("no LLM provider configured");
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        throw new IllegalStateException("no LLM provider configured");
    }

    @Override
    public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        throw new IllegalStateException("no LLM provider configured");
    }
}
