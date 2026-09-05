package com.resurgent.tev.parser.db;

/** Minimal cell identity and coordinate for Candidate membership. */
public record CellCoordRef(
        long cellId,
        String coord,
        int rowNum,
        int colNum) {
}
