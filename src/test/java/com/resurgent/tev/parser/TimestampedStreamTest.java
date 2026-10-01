package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TimestampedStreamTest {

    private static String run(java.util.function.Consumer<PrintStream> writer) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream stamped = TimestampedStream.wrap(sink, () -> LocalTime.of(14, 5, 9));
        writer.accept(stamped);
        stamped.flush();
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyLineStartsWithATimestamp() {
        String out = run(p -> {
            p.println("first");
            p.println("second");
        });

        assertThat(out).isEqualTo("14:05:09 first\n14:05:09 second\n");
    }

    @Test
    void aLineWrittenInPiecesIsStampedOnce() {
        String out = run(p -> {
            p.print("half ");
            p.print("and half");
            p.println();
        });

        assertThat(out).isEqualTo("14:05:09 half and half\n");
    }

    @Test
    void multiLineWritesStampEachLine() {
        String out = run(p -> p.print("a\nb\nc\n"));

        assertThat(out).isEqualTo("14:05:09 a\n14:05:09 b\n14:05:09 c\n");
    }

    @Test
    void blankLinesAndNoTrailingNewlineArePreserved() {
        String out = run(p -> p.print("x\n\ny"));

        assertThat(out).isEqualTo("14:05:09 x\n14:05:09 \n14:05:09 y");
    }

    @Test
    void textIsFlushedImmediatelyNotHeldBack() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream stamped = TimestampedStream.wrap(sink, () -> LocalTime.of(1, 2, 3));

        stamped.println("visible now");

        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("01:02:03 visible now\n");
    }
}
