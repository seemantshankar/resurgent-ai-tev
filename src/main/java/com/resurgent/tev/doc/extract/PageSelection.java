package com.resurgent.tev.doc.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * A validated 1-based page selection parsed from the {@code --pages} spec
 * (e.g. {@code "1-5,8"}). Pages are sorted and de-duplicated, matching the
 * LlamaCloud {@code target_pages} contract (1-based; duplicate pages are an
 * error upstream, so they are collapsed here).
 *
 * <p>{@link #wire()} renders the canonical vendor string, compressing runs of
 * consecutive pages ({@code "1-5,8"}). The live adapter sends it as
 * {@code configuration.target_pages}; citations still report the original
 * document page numbers, so provenance needs no remapping.
 */
public final class PageSelection {

    private final List<Integer> indices;

    private PageSelection(List<Integer> indices) {
        this.indices = List.copyOf(indices);
    }

    /**
     * Parses a page spec of comma-separated single pages and inclusive ranges.
     *
     * @param spec e.g. {@code "1-5,8"} (required)
     * @return validated selection, sorted and de-duplicated
     * @throws IllegalArgumentException when blank, non-numeric, non-positive, or reversed
     */
    public static PageSelection parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("--pages must not be blank");
        }
        TreeSet<Integer> pages = new TreeSet<>();
        for (String token : spec.split(",", -1)) {
            String part = token.trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("--pages has an empty token: '" + spec + "'");
            }
            int dash = part.indexOf('-');
            if (dash < 0) {
                pages.add(one(part, spec));
            } else {
                int from = one(part.substring(0, dash), spec);
                int to = one(part.substring(dash + 1), spec);
                if (to < from) {
                    throw new IllegalArgumentException("--pages range is reversed: '" + part + "'");
                }
                for (int p = from; p <= to; p++) {
                    pages.add(p);
                }
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("--pages selects no pages: '" + spec + "'");
        }
        return new PageSelection(new ArrayList<>(pages));
    }

    /**
     * @param spec spec, or {@code null}/blank
     * @return the parsed selection, or {@code null} when blank (meaning all pages)
     */
    public static PageSelection parseOptional(String spec) {
        return spec == null || spec.isBlank() ? null : parse(spec);
    }

    private static int one(String raw, String spec) {
        String text = raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("--pages has a missing page number: '" + spec + "'");
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--pages is not a number: '" + text + "'");
        }
        if (value < 1) {
            throw new IllegalArgumentException("--pages is 1-based; got " + value);
        }
        return value;
    }

    /** Sorted, de-duplicated 1-based page numbers. */
    public List<Integer> indices() {
        return indices;
    }

    /** Canonical vendor string for this selection, e.g. {@code "1-5,8"}. */
    public String wire() {
        return wire(indices);
    }

    /** True when {@code page} is selected. */
    public boolean contains(int page) {
        return Collections.binarySearch(indices, page) >= 0;
    }

    /**
     * Renders 1-based page numbers as a canonical {@code target_pages} string,
     * compressing consecutive runs.
     *
     * @param indices 1-based pages (any order; duplicates tolerated)
     * @return comma-separated ranges, or {@code null} when the list is null or empty
     */
    public static String wire(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(indices));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int start = sorted.get(i);
            int end = start;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) {
                end = sorted.get(++i);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(start);
            if (end > start) {
                sb.append('-').append(end);
            }
            i++;
        }
        return sb.toString();
    }
}
