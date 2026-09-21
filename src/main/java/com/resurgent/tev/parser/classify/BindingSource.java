package com.resurgent.tev.parser.classify;

/**
 * How a nomenclature binding was produced. {@code llm_line} is the per-cell Layer B
 * answer; the rest come from the dependency graph, either directly or via one
 * group-level LLM answer reused across every member sharing that label.
 */
public final class BindingSource {

    /** A hardcoded cell typed from its own row label. */
    public static final String INPUT = "input";
    /** A formula cell that took its binding by propagation from its inputs. */
    public static final String DERIVED = "derived";
    /** The head of an aggregation; the group's own total. */
    public static final String AGGREGATION_HEAD = "aggregation_head";
    /** One LLM answer for a distinct qualified label, reused across its members. */
    public static final String LLM_LABEL = "llm_label";
    /** The per-cell Layer B answer. */
    public static final String LLM_LINE = "llm_line";

    private BindingSource() {}

    public static boolean isKnown(String value) {
        return INPUT.equals(value)
                || DERIVED.equals(value)
                || AGGREGATION_HEAD.equals(value)
                || LLM_LABEL.equals(value)
                || LLM_LINE.equals(value);
    }

    /** True when the graph, not an LLM answer, decided this binding. */
    public static boolean deterministic(String value) {
        return INPUT.equals(value) || DERIVED.equals(value) || AGGREGATION_HEAD.equals(value);
    }
}
