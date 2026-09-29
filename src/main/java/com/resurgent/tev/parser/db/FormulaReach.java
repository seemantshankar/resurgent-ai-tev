package com.resurgent.tev.parser.db;

/**
 * Shortest-path precedent: {@code fromCellId} calculates from {@code toCellId}
 * at {@code depth} (1 = the formula reads that cell directly).
 */
public record FormulaReach(long fromCellId, long toCellId, int depth) {
}
