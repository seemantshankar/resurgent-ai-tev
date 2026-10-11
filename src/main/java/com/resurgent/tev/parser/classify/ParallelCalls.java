package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs independent LLM calls a few at a time and hands the outcomes back in submission order,
 * so the caller can apply them sequentially and deterministically. A failed call is an
 * outcome, not an exception: one bad answer never takes the others down.
 */
final class ParallelCalls {

    /** The value of a call, or why it failed. */
    record Outcome<T>(T value, Exception error) {
        boolean ok() {
            return error == null;
        }
    }

    private ParallelCalls() {}

    /** With {@code concurrency} of 1 the calls run inline, in order, on the caller's thread. */
    static <T> List<Outcome<T>> run(List<Callable<T>> tasks, int concurrency) {
        List<Outcome<T>> outcomes = new ArrayList<>(tasks.size());
        if (concurrency <= 1 || tasks.size() <= 1) {
            for (Callable<T> task : tasks) {
                outcomes.add(attempt(task));
            }
            return outcomes;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, tasks.size()));
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(), null));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    outcomes.add(new Outcome<>(null, cause instanceof Exception ex ? ex : new RuntimeException(cause)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(new Outcome<>(null, e));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private static <T> Outcome<T> attempt(Callable<T> task) {
        try {
            return new Outcome<>(task.call(), null);
        } catch (Exception e) {
            return new Outcome<>(null, e);
        }
    }
}
