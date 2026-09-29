package com.resurgent.tev.parser.classify;

import java.util.List;

/** Narrow port for region layout + Layer A + cell type classification. Tests inject a fake. */
public interface ClassifierLlm {

    /**
     * Propose main/helper/scratch bboxes for one worksheet. Return an empty list
     * to leave existing Candidates unchanged (test fakes).
     */
    List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt);

    /**
     * Batch propose regions for multiple worksheets. Default returns empty map
     * so existing code stays valid; batch regions falls back to individual calls.
     */
    default java.util.Map<String, List<RegionProposal>> proposeRegionsBatch(
            java.util.List<RegionLayoutPrompt> prompts) {
        return java.util.Collections.emptyMap();
    }

    LayerAJudgment classifyLayerA(LayerAPrompt prompt);

    /**
     * Name still-unbound cells in one Candidate. Default returns nothing so
     * region-layout fakes stay valid; the binder keeps what the graph already proved.
     */
    default List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        return List.of();
    }

    /**
     * Classify a numeric cell with LLM fallback. Default returns empty string
     * so cell-reading fakes stay valid; cell type classifier keeps cells untyped.
     */
    default String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
        return "";
    }

    /**
     * Batch classify Layer A candidates (multiple regions). Default returns empty string
     * so Layer A fakes stay valid; batch classification falls back to individual.
     */
    default String classifyLayerAJson(String userPrompt, int maxTokens) {
        return "";
    }
}
