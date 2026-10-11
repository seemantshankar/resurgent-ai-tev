package com.resurgent.tev.parser.db;

/** A money cell's scale worked out from how the workbook's other numbers sit, with the numbers it rests on. */
public record CellScaleInference(long cellId, String scale, String evidence) {}
