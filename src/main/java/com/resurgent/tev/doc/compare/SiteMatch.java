package com.resurgent.tev.doc.compare;

/**
 * The result of loose site-name matching (issue #135; GLOSSARY "Site").
 *
 * <p>{@code score} is the reported similarity of the two names' loose keys
 * (lower-cased alphanumeric tokens concatenated), 1.0 when identical: it is
 * the escalation signal for later M3 tickets, so an ambiguous score is
 * visible even when {@link #matched()} is true. {@code matched} is
 * {@code score >= }{@link Comparisons#SITE_MATCH_SCORE}.
 *
 * @param matched true when the score reaches the match threshold
 * @param score similarity in [0.0, 1.0]; 0.0 when either name is blank
 */
public record SiteMatch(boolean matched, double score) {}
