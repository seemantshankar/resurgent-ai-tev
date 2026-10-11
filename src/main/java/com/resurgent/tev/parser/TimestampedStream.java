package com.resurgent.tev.parser;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * A stream that prefixes every line with the wall-clock time and never holds text back, so a
 * long-running command shows when each thing happened and whether it is still moving.
 */
public final class TimestampedStream extends OutputStream {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final OutputStream target;
    private final Supplier<LocalTime> clock;
    private boolean atLineStart = true;

    private TimestampedStream(OutputStream target, Supplier<LocalTime> clock) {
        this.target = target;
        this.clock = clock;
    }

    /** A print stream over {@code target} that stamps each line and flushes on every write. */
    public static PrintStream wrap(OutputStream target, Supplier<LocalTime> clock) {
        return new PrintStream(new TimestampedStream(target, clock), true, StandardCharsets.UTF_8);
    }

    public static PrintStream wrap(OutputStream target) {
        return wrap(target, LocalTime::now);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        int lineStart = offset;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (atLineStart) {
                target.write((clock.get().format(FORMAT) + " ").getBytes(StandardCharsets.UTF_8));
                atLineStart = false;
            }
            if (bytes[i] == '\n') {
                target.write(bytes, lineStart, i - lineStart + 1);
                lineStart = i + 1;
                atLineStart = true;
            }
        }
        if (lineStart < end) {
            target.write(bytes, lineStart, end - lineStart);
        }
        target.flush();
    }

    @Override
    public synchronized void flush() throws IOException {
        target.flush();
    }
}
