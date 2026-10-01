package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LlmActivityTest {

    @Test
    void tracksCallsInFlightAndTheLongestWait() {
        AtomicLong clock = new AtomicLong(0);
        LlmActivity activity = new LlmActivity(clock::get);

        long a = activity.begin("m1");
        clock.addAndGet(10_000_000_000L);
        long b = activity.begin("m3");
        clock.addAndGet(5_000_000_000L);

        var snap = activity.snapshot();
        assertThat(snap.inFlight()).isEqualTo(2);
        assertThat(snap.longestWaitSeconds()).isEqualTo(15);
        assertThat(snap.longestWaitModel()).isEqualTo("m1");

        activity.end(a, true);
        activity.end(b, false);
        snap = activity.snapshot();
        assertThat(snap.inFlight()).isZero();
        assertThat(snap.completed()).isEqualTo(1);
        assertThat(snap.failed()).isEqualTo(1);
    }

    @Test
    void failoversAreCounted() {
        LlmActivity activity = new LlmActivity(System::nanoTime);

        activity.failover();
        activity.failover();

        assertThat(activity.snapshot().failovers()).isEqualTo(2);
    }

    @Test
    void statusLineIsReadableAndSaysWhenNothingIsWaiting() {
        LlmActivity activity = new LlmActivity(System::nanoTime);

        String idle = activity.snapshot().statusLine();

        assertThat(idle).contains("no LLM call in flight").contains("0 done");
    }
}
