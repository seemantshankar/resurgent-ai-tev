package com.resurgent.tev.parser.classify;

/** Persisted peer edge from one amount cell to another within a parse run. */
public record BindingPeer(
        long parseRunId,
        long cellId,
        long peerCellId,
        String peerReason,
        boolean pathResolved) {}
