package com.resurgent.tev.parser.classify;

/**
 * A hardcoded numeric cell: a literal, or a formula that reads no other cell
 * ({@code =1500*0.8}). Everything else on the sheet is derived from these, so these
 * are the only cells anyone has to judge.
 *
 * <p>{@code seriesKey} groups a repeated row-series — cells sharing a row label and
 * an R1C1-relative shape across a period band — so a ten-year row is one decision.
 */
record InputCell(long cellId, String rowLabel, String seriesKey) {}
