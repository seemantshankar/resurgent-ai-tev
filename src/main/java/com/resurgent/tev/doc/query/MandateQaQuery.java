package com.resurgent.tev.doc.query;

/**
 * Filter for the mandate QA read API (M3). Only the mandate scope is fixed; a
 * similarity query additionally asks for the top-K nearest clauses or passages.
 *
 * @param mandateId mandate scope
 * @param similarKind {@code clause} or {@code passage}, or null when similarity is not requested
 * @param similarText free-text query, or null
 * @param similarId reference clause or passage id, or null
 * @param topK how many hits to return when similarity is requested
 */
public record MandateQaQuery(
        long mandateId,
        String similarKind,
        String similarText,
        Long similarId,
        int topK) {

    public MandateQaQuery {
        if (topK <= 0) {
            topK = 5;
        }
    }

    /**
     * Query scoped to one mandate, without a similarity search.
     *
     * @param mandateId required mandate scope
     */
    public static MandateQaQuery forMandate(long mandateId) {
        return new MandateQaQuery(mandateId, null, null, null, 5);
    }

    /**
     * Mandate read plus top-K similarity. Provide text or a reference id, not both.
     *
     * @param mandateId mandate scope
     * @param kind {@code clause} or {@code passage}
     * @param text free-text query, or null when searching by id
     * @param id reference id, or null when searching by text
     * @param topK maximum hits
     */
    public static MandateQaQuery similar(long mandateId, String kind, String text, Long id,
            int topK) {
        return new MandateQaQuery(mandateId, kind, text, id, topK);
    }
}
