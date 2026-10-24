package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Under {@code local_only}, facts that rest on a locked page, a low-confidence
 * block, or handwriting are recorded as ambiguous and queued for review.
 * Checklist assessment then treats that evidence as unclear, never provided.
 */
public final class OcrAmbiguity {

    public static final String REVIEW_CATEGORY = "ocr_low_confidence";

    private OcrAmbiguity() {}

    /**
     * @return how many facts were marked ambiguous
     */
    public static int apply(Connection connection, long documentVersionId, long parseRunId)
            throws SQLException {
        int facts;
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact_evidence SET resolution = 'ambiguous'"
                        + " WHERE fact_id IN ("
                        + " SELECT f.fact_id FROM fact f"
                        + " WHERE f.document_version_id = ?"
                        + " AND ("
                        + "  EXISTS (SELECT 1 FROM provenance_doc pd"
                        + "    JOIN page pg ON pg.page_id = pd.page_id"
                        + "    WHERE pd.entity_type = 'fact' AND pd.entity_id = f.fact_id"
                        + "    AND (pg.routing_outcome = 'local_ocr_unverified_residency_locked'"
                        + "      OR (pg.ocr_confidence IS NOT NULL AND pg.ocr_confidence < 0.70)))"
                        + "  OR EXISTS (SELECT 1 FROM provenance_doc pd"
                        + "    JOIN span s ON s.span_id = pd.span_id"
                        + "    WHERE pd.entity_type = 'fact' AND pd.entity_id = f.fact_id"
                        + "    AND s.confidence IS NOT NULL AND s.confidence < 0.70)"
                        + "  OR EXISTS (SELECT 1 FROM provenance_doc pd"
                        + "    JOIN block b ON b.block_id = pd.block_id"
                        + "    WHERE pd.entity_type = 'fact' AND pd.entity_id = f.fact_id"
                        + "    AND (b.is_handwritten = 1"
                        + "      OR (b.ocr_confidence IS NOT NULL AND b.ocr_confidence < 0.70)))"
                        + " ))")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(DISTINCT fact_id) FROM fact_evidence"
                        + " WHERE resolution = 'ambiguous' AND fact_id IN ("
                        + " SELECT fact_id FROM fact WHERE document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                facts = rs.getInt(1);
            }
        }
        int locked = lockedPages(connection, documentVersionId);
        if (facts == 0 && locked == 0) {
            return 0;
        }
        FactRepository factsRepo = new FactRepository(connection);
        factsRepo.deleteReviewQueueCategory(parseRunId, REVIEW_CATEGORY);
        new WorkspaceRepository(connection).insertReviewQueue(parseRunId, REVIEW_CATEGORY,
                "Low-confidence or handwritten readings stayed local under data residency"
                        + " local_only",
                "{\"document_version_id\":" + documentVersionId
                        + ",\"ambiguous_facts\":" + facts
                        + ",\"locked_pages\":" + locked + "}",
                "Pending", false, Timestamps.now(), null);
        return facts;
    }

    private static int lockedPages(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM page WHERE document_version_id = ?"
                        + " AND routing_outcome = 'local_ocr_unverified_residency_locked'")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
