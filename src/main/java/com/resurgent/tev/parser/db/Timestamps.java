package com.resurgent.tev.parser.db;

import java.time.Instant;

/** The one canonical timestamp formatter (ADR 0002): ISO-8601 TEXT, UTC. */
public final class Timestamps {

    private Timestamps() {}

    public static String now() {
        return Instant.now().toString();
    }
}
