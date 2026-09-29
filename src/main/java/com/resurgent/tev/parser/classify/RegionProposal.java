package com.resurgent.tev.parser.classify;

/** One LLM-proposed region bbox with structural role. */
public record RegionProposal(
        String structuralRole,
        String bbox,
        String label,
        String why) {

    public RegionProposal {
        if (structuralRole == null || structuralRole.isBlank()) {
            throw new IllegalArgumentException("structuralRole required");
        }
        if (bbox == null || bbox.isBlank()) {
            throw new IllegalArgumentException("bbox required");
        }
    }
}
