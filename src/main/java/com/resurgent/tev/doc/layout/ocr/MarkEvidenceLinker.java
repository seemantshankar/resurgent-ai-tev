package com.resurgent.tev.doc.layout.ocr;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.layout.BBox;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Links stamp, signature, and handwriting blocks to facts whose cited box
 * overlaps or sits within {@link #ADJACENT_PX} pixels. Extraction calls this
 * after facts are written. Running it twice does not duplicate evidence.
 */
public final class MarkEvidenceLinker {

    /** Gap, in pixels, that still counts as adjacent to a cited box. */
    public static final double ADJACENT_PX = 24;

    private MarkEvidenceLinker() {}

    /**
     * @return how many evidence rows were inserted
     */
    public static int attach(Connection connection, long documentVersionId) throws SQLException {
        List<Mark> marks = loadMarks(connection, documentVersionId);
        if (marks.isEmpty()) {
            return 0;
        }
        List<CitedFact> facts = loadFacts(connection, documentVersionId);
        if (facts.isEmpty()) {
            return 0;
        }
        DocumentRepository docs = new DocumentRepository(connection);
        FactRepository evidence = new FactRepository(connection);
        int inserted = 0;
        for (CitedFact fact : facts) {
            for (Mark mark : marks) {
                if (fact.pageId != null && fact.pageId != mark.pageId) {
                    continue;
                }
                if (!near(fact.box, mark.box)) {
                    continue;
                }
                if (alreadyLinked(connection, fact.factId, mark.role, mark.blockId)) {
                    continue;
                }
                long provenanceId = docs.insertProvenance("fact", fact.factId, documentVersionId,
                        mark.pageId, mark.blockId, null, null,
                        mark.box.x0(), mark.box.y0(), mark.box.x1(), mark.box.y1(),
                        null, null, mark.text, null);
                evidence.appendEvidence(fact.factId, mark.role, provenanceId, mark.text, "resolved");
                inserted++;
            }
        }
        return inserted;
    }

    static boolean near(BBox fact, BBox mark) {
        double pad = ADJACENT_PX;
        return fact.x0() - pad <= mark.x1()
                && mark.x0() - pad <= fact.x1()
                && fact.y0() - pad <= mark.y1()
                && mark.y0() - pad <= fact.y1();
    }

    private static boolean alreadyLinked(Connection connection, long factId, String role,
            long blockId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM fact_evidence e"
                        + " JOIN provenance_doc p ON p.provenance_id = e.provenance_id"
                        + " WHERE e.fact_id = ? AND e.role = ? AND p.block_id = ?")) {
            ps.setLong(1, factId);
            ps.setString(2, role);
            ps.setLong(3, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<Mark> loadMarks(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT b.block_id, b.page_id, b.block_kind, b.text_raw,"
                        + " b.bbox_x0, b.bbox_y0, b.bbox_x1, b.bbox_y1"
                        + " FROM block b JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " AND b.block_kind IN ('stamp', 'signature', 'handwriting')"
                        + " AND b.bbox_x0 IS NOT NULL")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Mark> marks = new ArrayList<>();
                while (rs.next()) {
                    String kind = rs.getString("block_kind");
                    String role = switch (kind) {
                        case "stamp" -> "stamp";
                        case "signature" -> "signature_block";
                        case "handwriting" -> "handwriting";
                        default -> null;
                    };
                    if (role == null) {
                        continue;
                    }
                    marks.add(new Mark(
                            rs.getLong("block_id"),
                            rs.getLong("page_id"),
                            role,
                            rs.getString("text_raw"),
                            new BBox(rs.getDouble("bbox_x0"), rs.getDouble("bbox_y0"),
                                    rs.getDouble("bbox_x1"), rs.getDouble("bbox_y1"))));
                }
                return marks;
            }
        }
    }

    private static List<CitedFact> loadFacts(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id, pr.page_id, pr.bbox_x0, pr.bbox_y0, pr.bbox_x1, pr.bbox_y1"
                        + " FROM fact f"
                        + " JOIN provenance_doc pr ON pr.entity_type = 'fact'"
                        + " AND pr.entity_id = f.fact_id"
                        + " WHERE f.document_version_id = ?"
                        + " AND pr.bbox_x0 IS NOT NULL")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CitedFact> facts = new ArrayList<>();
                while (rs.next()) {
                    long pageId = rs.getLong("page_id");
                    Long page = rs.wasNull() ? null : pageId;
                    facts.add(new CitedFact(
                            rs.getLong("fact_id"),
                            page,
                            new BBox(rs.getDouble("bbox_x0"), rs.getDouble("bbox_y0"),
                                    rs.getDouble("bbox_x1"), rs.getDouble("bbox_y1"))));
                }
                return facts;
            }
        }
    }

    private record Mark(long blockId, long pageId, String role, String text, BBox box) {}

    private record CitedFact(long factId, Long pageId, BBox box) {}
}
