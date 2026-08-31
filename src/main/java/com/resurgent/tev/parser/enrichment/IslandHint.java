package com.resurgent.tev.parser.enrichment;

/** 8-connected filled-cell component; exact bounds for LLM classification hints. */
public record IslandHint(String id, String bounds, int cellCount) {}
