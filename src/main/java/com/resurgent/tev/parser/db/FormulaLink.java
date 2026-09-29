package com.resurgent.tev.parser.db;

/** A formula cell and one persisted cell its formula reads directly. */
public record FormulaLink(long fromCellId, long toCellId) {
}
