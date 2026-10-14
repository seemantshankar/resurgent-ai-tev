package com.resurgent.tev.doc.query;

/**
 * Filter bag for fact retrieval (S2.13). All filters optional except mandate scope
 * which is applied by the caller/repository. Rows are returned by
 * {@code FactRepository.query}.
 */
public record FactQuery(
        String pathPrefix,
        Long documentId,
        Long documentVersionId,
        String valueKind,
        boolean currentOnly,
        Long mandateId) {

    /** Unfiltered query (current facts only). */
    public static FactQuery current() {
        return new FactQuery(null, null, null, null, true, null);
    }

    /**
     * Query scoped to a mandate.
     *
     * @param mandateId required mandate scope
     */
    public static FactQuery forMandate(long mandateId) {
        return new FactQuery(null, null, null, null, true, mandateId);
    }

    /**
     * Builder-style copy with a path prefix filter.
     *
     * @param pathPrefix ontology path prefix (e.g. {@code Commercial > Offtake})
     */
    public FactQuery withPathPrefix(String pathPrefix) {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, currentOnly, mandateId);
    }

    /**
     * Builder-style copy with a value_kind filter.
     *
     * @param valueKind money | quantity | rate | percent | date | duration | text | enum | boolean | list
     */
    public FactQuery withValueKind(String valueKind) {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, currentOnly, mandateId);
    }

    /**
     * Builder-style copy limited to one document version.
     *
     * @param documentVersionId version id
     */
    public FactQuery withVersion(long documentVersionId) {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, currentOnly, mandateId);
    }

    /**
     * Builder-style copy limited to one logical document.
     *
     * @param documentId logical document id
     */
    public FactQuery withDocument(long documentId) {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, currentOnly, mandateId);
    }

    /**
     * Builder-style copy with mandate scope.
     *
     * @param mandateId mandate id
     */
    public FactQuery withMandate(long mandateId) {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, currentOnly, mandateId);
    }

    /**
     * Builder-style copy including historical facts.
     */
    public FactQuery includingHistory() {
        return new FactQuery(pathPrefix, documentId, documentVersionId, valueKind, false, mandateId);
    }
}
