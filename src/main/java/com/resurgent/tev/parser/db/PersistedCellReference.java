package com.resurgent.tev.parser.db;

/** Persisted formula-reference edge with its database identity. */
public record PersistedCellReference(long cellReferenceId, CellReferenceEdge edge) {
}
