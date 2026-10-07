package com.resurgent.tev.parser.db;

/** How a cell's unit of measurement is known: {@code stated} in its labels, or {@code inferred} by a model. */
public record CellUnitBasis(long cellId, String unit, String basis, String evidence) {}
