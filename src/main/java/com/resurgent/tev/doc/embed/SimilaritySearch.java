package com.resurgent.tev.doc.embed;

import com.resurgent.tev.doc.query.MandateQa;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Top-K cosine search over stored clause or passage embeddings for one mandate.
 * Hits carry the document, page, and verbatim snippet. A search by id omits
 * that row so the neighbours are what come back.
 */
public final class SimilaritySearch {

    private SimilaritySearch() {}

    /**
     * @param connection open workspace connection
     * @param mandateId mandate scope
     * @param kind {@code clause} or {@code passage}
     * @param text free-text query; used when non-blank
     * @param referenceId clause or passage id; used when {@code text} is blank
     * @param topK maximum hits, at least 1
     * @param client vectoriser for a text query (required)
     * @return hits best-first
     * @throws SQLException if a read fails
     * @throws IllegalArgumentException when kind, text, and id do not form a query
     */
    public static List<MandateQa.SimilarHit> topK(Connection connection, long mandateId,
            String kind, String text, Long referenceId, int topK, EmbeddingClient client)
            throws SQLException {
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!"clause".equals(normalizedKind) && !"passage".equals(normalizedKind)) {
            throw new IllegalArgumentException("--kind must be clause or passage");
        }
        int limit = topK < 1 ? 5 : topK;
        boolean byText = text != null && !text.isBlank();
        if (byText == (referenceId != null)) {
            throw new IllegalArgumentException("provide exactly one of --text or --id");
        }
        List<Candidate> candidates = "clause".equals(normalizedKind)
                ? loadClauses(connection, mandateId)
                : loadPassages(connection, mandateId);
        float[] query;
        Long exclude = null;
        if (byText) {
            query = client.embed(text);
        } else {
            Candidate reference = candidates.stream()
                    .filter(candidate -> candidate.id == referenceId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no " + normalizedKind + " " + referenceId + " in mandate " + mandateId));
            query = reference.vector;
            exclude = referenceId;
        }
        List<MandateQa.SimilarHit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (exclude != null && candidate.id == exclude) {
                continue;
            }
            double score = Cosine.similarity(query, candidate.vector);
            hits.add(new MandateQa.SimilarHit(
                    normalizedKind, candidate.id, candidate.documentId, candidate.documentVersionId,
                    candidate.logicalName, candidate.fileName, candidate.page, candidate.snippet,
                    score));
        }
        hits.sort(Comparator.comparingDouble(MandateQa.SimilarHit::score).reversed()
                .thenComparingLong(MandateQa.SimilarHit::id));
        if (hits.size() > limit) {
            return List.copyOf(hits.subList(0, limit));
        }
        return List.copyOf(hits);
    }

    private static List<Candidate> loadClauses(Connection connection, long mandateId)
            throws SQLException {
        List<Candidate> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cl.clause_id, d.document_id, dv.document_version_id, d.logical_name,"
                        + " dv.file_name, cl.page_start, cl.verbatim_text, ce.vec"
                        + " FROM clause_embedding ce"
                        + " JOIN clause cl ON cl.clause_id = ce.clause_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = cl.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int page = rs.getInt("page_start");
                    Integer pageIndex = rs.wasNull() ? null : page;
                    rows.add(new Candidate(
                            rs.getLong("clause_id"),
                            rs.getLong("document_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("logical_name"),
                            rs.getString("file_name"),
                            pageIndex,
                            rs.getString("verbatim_text"),
                            EmbeddingVectors.fromLittleEndian(rs.getBytes("vec"))));
                }
            }
        }
        return rows;
    }

    private static List<Candidate> loadPassages(Connection connection, long mandateId)
            throws SQLException {
        List<Candidate> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.passage_id, d.document_id, dv.document_version_id, d.logical_name,"
                        + " dv.file_name, p.page_from, pg.page_index AS provenance_page,"
                        + " COALESCE(pd.verbatim, p.heading) AS snippet, pe.vec"
                        + " FROM passage_embedding pe"
                        + " JOIN passage p ON p.passage_id = pe.passage_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = p.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN provenance_doc pd ON pd.provenance_id = p.provenance_id"
                        + " LEFT JOIN page pg ON pg.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int provenancePage = rs.getInt("provenance_page");
                    boolean hasProvenance = !rs.wasNull();
                    int pageFrom = rs.getInt("page_from");
                    rows.add(new Candidate(
                            rs.getLong("passage_id"),
                            rs.getLong("document_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("logical_name"),
                            rs.getString("file_name"),
                            hasProvenance ? provenancePage : pageFrom,
                            rs.getString("snippet"),
                            EmbeddingVectors.fromLittleEndian(rs.getBytes("vec"))));
                }
            }
        }
        return rows;
    }

    private static final class Candidate {
        private final long id;
        private final long documentId;
        private final long documentVersionId;
        private final String logicalName;
        private final String fileName;
        private final Integer page;
        private final String snippet;
        private final float[] vector;

        private Candidate(long id, long documentId, long documentVersionId, String logicalName,
                String fileName, Integer page, String snippet, float[] vector) {
            this.id = id;
            this.documentId = documentId;
            this.documentVersionId = documentVersionId;
            this.logicalName = logicalName;
            this.fileName = fileName;
            this.page = page;
            this.snippet = snippet;
            this.vector = vector;
        }
    }
}
