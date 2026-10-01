package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.Progress;
import com.resurgent.tev.parser.classify.LlmActivity;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Prints one status line every interval: elapsed time, the stage running, and what the model
 * calls are doing. It is what tells a slow stage from a stuck one while a run is in progress.
 */
final class Heartbeat implements AutoCloseable {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });

    Heartbeat(long intervalSeconds) {
        long started = System.nanoTime();
        scheduler.scheduleAtFixedRate(() -> {
            long elapsed = (System.nanoTime() - started) / 1_000_000_000L;
            System.err.println(String.format("[status] elapsed %02d:%02d | %s | %s",
                    elapsed / 60, elapsed % 60, Progress.current(), LlmActivity.GLOBAL.snapshot().statusLine()));
            System.err.flush();
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
