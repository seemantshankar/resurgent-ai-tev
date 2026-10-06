package com.resurgent.tev.parser.classify;

import java.util.Locale;

/**
 * Turns the triage and relevance a model wrote for a region into the stored vocabulary
 * ({@code main | scratch | orphan} and {@code primary | supporting | noise}).
 *
 * <p>A region that supports the model but is not its main schedule (a summary, a driver table, an
 * assumption block, a supporting calculation) is {@code main} with relevance {@code supporting}. It
 * used to be written as {@code scratch}, which the rest of the pipeline treats as noise and never
 * binds. Only a model that says scratch or orphan produces those; a missing or unknown answer keeps
 * the region, because a wrongly kept region costs a model call and a wrongly dropped one is lost.
 */
final class LayerATriageNormalizer {

    private LayerATriageNormalizer() {}

    /** @return {@code {triage, relevance}} */
    static String[] normalize(String triage, String relevance) {
        String t = triage == null ? "" : triage.trim().toLowerCase(Locale.ROOT);
        String r = relevance == null ? "" : relevance.trim().toLowerCase(Locale.ROOT);
        if (t.equals(Triage.SCRATCH) || t.equals(Triage.ORPHAN)) {
            return new String[] {t, Relevance.NOISE};
        }
        if (t.equals("helper")) {
            return new String[] {Triage.MAIN, Relevance.SUPPORTING};
        }
        // "main", and anything the model did not say or we do not know: kept.
        String kept = r.equals("primary")
                ? Relevance.PRIMARY
                : Relevance.SUPPORTING; // secondary, tertiary, supporting, noise (a main region is never noise), unknown
        return new String[] {Triage.MAIN, kept};
    }
}
