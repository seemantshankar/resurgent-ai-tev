package com.resurgent.tev.parser.enrichment;

/** How much structure the model must return in one enrichment call. */
public enum EnrichmentPromptMode {
    /** Region boxes plus per-cell roles and labels for every filled cell. */
    FULL,
    /** Region boxes only; each region has an empty cells array. */
    REGIONS_ONLY
}
