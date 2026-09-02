package com.resurgent.tev.parser.enrichment;

/** Mockable boundary around the external model transport. */
@FunctionalInterface
public interface EnrichmentModelClient {

    String generate(EnrichmentModelRequest request) throws Exception;
}
