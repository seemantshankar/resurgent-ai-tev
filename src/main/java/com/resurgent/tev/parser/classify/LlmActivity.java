package com.resurgent.tev.parser.classify;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * What the model calls are doing right now: how many are waiting, for how long, how many have
 * finished or failed, and how many requests had to move to another model. A heartbeat prints
 * this so a long wait is visibly a wait, not a silent hang.
 */
public final class LlmActivity {

    /** The process-wide tracker the HTTP client reports to. */
    public static final LlmActivity GLOBAL = new LlmActivity(System::nanoTime);

    private record Call(String model, long startedNanos) {}

    /** A moment-in-time reading. */
    public record Snapshot(
            int inFlight,
            long longestWaitSeconds,
            String longestWaitModel,
            long completed,
            long failed,
            long failovers) {

        public String statusLine() {
            String waiting = inFlight == 0
                    ? "no LLM call in flight"
                    : inFlight + " LLM call" + (inFlight == 1 ? "" : "s") + " in flight (longest wait "
                            + longestWaitSeconds + "s on " + longestWaitModel + ")";
            return waiting + " | " + completed + " done, " + failed + " failed, "
                    + failovers + " moved to another model";
        }
    }

    private final LongSupplier nanoClock;
    private final Map<Long, Call> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong failovers = new AtomicLong();

    LlmActivity(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** A call to {@code model} is starting; pass the returned id to {@link #end}. */
    public long begin(String model) {
        long id = ids.incrementAndGet();
        inFlight.put(id, new Call(model, nanoClock.getAsLong()));
        return id;
    }

    public void end(long id, boolean succeeded) {
        if (inFlight.remove(id) != null) {
            (succeeded ? completed : failed).incrementAndGet();
        }
    }

    /** A request failed on one model and was sent to the next. */
    public void failover() {
        failovers.incrementAndGet();
    }

    public Snapshot snapshot() {
        long now = nanoClock.getAsLong();
        long longest = -1;
        String model = "";
        for (Call call : inFlight.values()) {
            long waited = now - call.startedNanos();
            if (waited > longest) {
                longest = waited;
                model = call.model();
            }
        }
        return new Snapshot(
                inFlight.size(),
                Math.max(longest, 0) / 1_000_000_000L,
                model,
                completed.get(),
                failed.get(),
                failovers.get());
    }
}
