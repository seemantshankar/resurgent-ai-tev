package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Groups Layer A candidates into calls. A call carries as many candidates as fit under both a candidate
 * count and a size budget: one whole-sheet region can be a few hundred thousand characters, and a batch of
 * those is a request too big to answer, to wait for, or to price at the ordinary rate.
 */
final class LayerABatching {

    private LayerABatching() {}

    /**
     * Keeps the order. A candidate that alone is over {@code budget} gets a call of its own; a batch is
     * never empty.
     */
    static <T> List<List<T>> split(List<T> items, ToLongFunction<T> weight, int maxItems, long budget) {
        List<List<T>> batches = new ArrayList<>();
        List<T> current = new ArrayList<>();
        long used = 0;
        for (T item : items) {
            long w = weight.applyAsLong(item);
            if (!current.isEmpty() && (current.size() >= maxItems || used + w > budget)) {
                batches.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(item);
            used += w;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }
}
