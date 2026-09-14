package com.resurgent.tev.parser.classify;

import java.util.Objects;

/** LLM-proposed peer link from one money line to another cell. */
public record LinePeerRef(String peerCoord, String peerReason) {

    public LinePeerRef {
        Objects.requireNonNull(peerCoord, "peerCoord");
        Objects.requireNonNull(peerReason, "peerReason");
        peerCoord = peerCoord.trim();
        peerReason = PeerReason.normalize(peerReason);
    }
}
