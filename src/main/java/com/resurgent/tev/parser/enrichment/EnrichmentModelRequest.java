package com.resurgent.tev.parser.enrichment;

import java.net.URI;

/** Provider-neutral request passed through the mockable model-client seam. */
public record EnrichmentModelRequest(
        String apiKey,
        String modelId,
        URI endpoint,
        String prompt) {}
