package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParallelCallsTest {

    @Test
    void outcomesComeBackInSubmissionOrder() {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            tasks.add(() -> {
                Thread.sleep((6 - n) * 20L); // later tasks finish first
                return n;
            });
        }

        var outcomes = ParallelCalls.run(tasks, 3);

        assertThat(outcomes.stream().map(ParallelCalls.Outcome::value)).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void aFailedCallIsAnOutcomeAndDoesNotAffectTheOthers() {
        List<Callable<String>> tasks = List.of(
                () -> "a",
                () -> { throw new IllegalStateException("provider down"); },
                () -> "c");

        for (int concurrency : new int[] {1, 3}) {
            var outcomes = ParallelCalls.run(tasks, concurrency);

            assertThat(outcomes.get(0).value()).isEqualTo("a");
            assertThat(outcomes.get(1).ok()).isFalse();
            assertThat(outcomes.get(1).error()).hasMessageContaining("provider down");
            assertThat(outcomes.get(2).value()).isEqualTo("c");
        }
    }

    @Test
    void callsReallyOverlapWhenConcurrencyAllows() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(3);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                allStarted.countDown();
                return allStarted.await(5, TimeUnit.SECONDS); // only true if all 3 run together
            });
        }

        assertThat(ParallelCalls.run(tasks, 3).stream().map(ParallelCalls.Outcome::value))
                .containsExactly(true, true, true);
    }

    @Test
    void concurrencyOneRunsInlineAndInOrder() {
        List<String> threads = new ArrayList<>();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            tasks.add(() -> {
                threads.add(Thread.currentThread().getName());
                return n;
            });
        }

        ParallelCalls.run(tasks, 1);

        assertThat(threads).containsOnly(Thread.currentThread().getName());
    }

    @Test
    void noTasksIsFine() {
        assertThat(ParallelCalls.run(List.<Callable<Integer>>of(), 4)).isEmpty();
    }
}
