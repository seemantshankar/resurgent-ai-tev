package com.resurgent.tev.parser.db;

/** Why a formula's cell reads are incomplete or circular. */
public record FormulaGap(long fromCellId, String reason, String rawToken) {
}
