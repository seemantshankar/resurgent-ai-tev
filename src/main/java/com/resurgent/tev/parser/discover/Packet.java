package com.resurgent.tev.parser.discover;

/**
 * On-demand LLM-facing payload for one Candidate. Amounts are read from the cell graph
 * at build time — never snapshotted into a Packet table.
 */
public record Packet(
        long candidateId,
        long parseRunId,
        long worksheetId,
        String candidateKind,
        java.util.List<PacketCell> cells,
        java.util.List<PacketRangeRef> largeRangeRefs,
        boolean contextClosureSucceeded) {
}
