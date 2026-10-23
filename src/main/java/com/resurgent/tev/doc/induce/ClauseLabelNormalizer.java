package com.resurgent.tev.doc.induce;

import java.util.List;

/**
 * Groups proposed clause-type labels into canonical types (M3-09, spec "Auto-promotion").
 * The live implementation normalises with the LLM. The offline implementation groups
 * only literally identical labels; {@code induce-clauses} then merges groups whose
 * stored clause embeddings are cosine-similar. The promoter never invents types: it
 * may only group labels the model actually proposed.
 */
public interface ClauseLabelNormalizer {

    /** Stable adapter id, e.g. {@code llm-label-normalisation}. */
    String id();

    /**
     * Groups proposed labels into canonical clause types.
     *
     * @param request the labels proposed by open clauses (required)
     * @return one group per canonical type; every proposed code appears in at most one
     */
    NormalizationResult normalize(NormalizationRequest request);

    /** One open clause's proposed type, as the model proposed it. */
    record ProposedLabel(String code, String label, String parentFamily) {}

    /** The labels to normalise, in reading order. */
    record NormalizationRequest(List<ProposedLabel> labels) {

        public NormalizationRequest {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    /**
     * One canonical clause type and the proposed codes it groups.
     *
     * @param key canonical snake_case type key
     * @param label canonical human label
     * @param parentFamily canonical parent family, or null when standalone
     * @param aliases additional phrasings that bind the type in Pass A
     * @param memberCodes the proposed type codes this group covers
     */
    record LabelGroup(
            String key,
            String label,
            String parentFamily,
            List<String> aliases,
            List<String> memberCodes) {

        public LabelGroup {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            memberCodes = memberCodes == null ? List.of() : List.copyOf(memberCodes);
        }
    }

    /** The normalisation outcome. */
    record NormalizationResult(List<LabelGroup> groups) {

        public NormalizationResult {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }
}
