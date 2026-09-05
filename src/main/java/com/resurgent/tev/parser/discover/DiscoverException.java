package com.resurgent.tev.parser.discover;

/** Domain rejection for {@code tev-parse discover} (missing DB/run or discover failure). */
public final class DiscoverException extends Exception {

    public DiscoverException(String message) {
        super(message);
    }

    public DiscoverException(String message, Throwable cause) {
        super(message, cause);
    }
}
