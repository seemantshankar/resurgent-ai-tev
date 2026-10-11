package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Money whose scale nothing states. A money input with no scale word in its labels, no
 * conversion in its formula and no region or sheet statement gets an unknown here instead of
 * a guessed {@code unit}, so a default never outranks a statement downstream.
 *
 * <p>The arithmetic links unknowns to each other and to stated scales: adding two unknowns
 * says they share a scale, adding one to lakhs says it is lakhs, dividing one by 100000 says
 * it was rupees, and a formula on a sheet that states lakhs says what it reads is lakhs.
 * An unknown takes a scale only when every such link agrees; otherwise it stays unknown.
 */
final class UnstatedScales {

    private final List<Integer> parent = new ArrayList<>();
    private final List<Long> origin = new ArrayList<>();
    private final List<Requirement> requirements = new ArrayList<>();
    private Map<Integer, Map<CellScale, Long>> solved; // root -> scale -> first cell that required it

    private record Requirement(int unknown, CellScale scale, long evidenceCellId) {}

    /** A new unknown scale for the money in {@code cellId}. */
    int fresh(long cellId) {
        parent.add(parent.size());
        origin.add(cellId);
        solved = null;
        return parent.size() - 1;
    }

    /** The two unknowns are one scale: they were added, subtracted or compared. */
    void same(int a, int b) {
        int ra = root(a);
        int rb = root(b);
        if (ra != rb) {
            parent.set(Math.max(ra, rb), Math.min(ra, rb));
            solved = null;
        }
    }

    /** {@code evidenceCellId}'s formula fixes the unknown to {@code scale}. */
    void require(int unknown, CellScale scale, long evidenceCellId) {
        if (scale == null) {
            return;
        }
        requirements.add(new Requirement(unknown, scale, evidenceCellId));
        solved = null;
    }

    /** The one scale every link agrees on, or {@code null} when none or several were required. */
    CellScale resolved(int unknown) {
        Map<CellScale, Long> found = required(unknown);
        return found.size() == 1 ? found.keySet().iterator().next() : null;
    }

    /** Scale to the first cell whose formula required it; two or more entries is a conflict. */
    Map<CellScale, Long> required(int unknown) {
        if (solved == null) {
            solved = new HashMap<>();
            for (Requirement r : requirements) {
                solved.computeIfAbsent(root(r.unknown()), k -> new EnumMap<>(CellScale.class))
                        .putIfAbsent(r.scale(), r.evidenceCellId());
            }
        }
        return solved.getOrDefault(root(unknown), Map.of());
    }

    /** The cell whose money introduced the unknown's class first. */
    long originOf(int unknown) {
        return origin.get(root(unknown));
    }

    int root(int unknown) {
        int r = unknown;
        while (parent.get(r) != r) {
            r = parent.get(r);
        }
        int at = unknown;
        while (parent.get(at) != r) {
            int next = parent.get(at);
            parent.set(at, r);
            at = next;
        }
        return r;
    }
}
