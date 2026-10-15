package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.MandateQa;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Chapter passages (M3-06, spec #133 / issue #139): persistence of the passages split
 * from the stored parse output, plus the per-mandate read section of {@link MandateQa}.
 *
 * <p>A passage's source anchor is a {@code provenance_doc} row with entity type
 * {@link #ENTITY_TYPE}. The repository never commits or rolls back — the caller owns
 * the transaction, the same discipline as {@link AssessmentRepository}.
 */
public final class PassageRepository {

    /** Provenance entity type of a passage source anchor. */
    public static final String ENTITY_TYPE = "passage";

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public PassageRepository(Connection connection) {
        this.connection = connection;
    }

    /** One stored parse page: the parser's markdown preferred over its plain text. */
    public record ParsePage(int pageIndex, String text) {}

    /**
     * The version's stored parse pages in order — the only input passages are split
     * from, so assessing passages never triggers a new parse call. Empty when the
     * version was never parsed.
     *
     * @throws SQLException if the query fails
     */
    public List<ParsePage> parsePages(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_index, COALESCE(NULLIF(page_markdown, ''), page_text) AS page_text"
                        + " FROM cloud_parse_page WHERE document_version_id = ?"
                        + " ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ParsePage> pages = new ArrayList<>();
                while (rs.next()) {
                    pages.add(new ParsePage(rs.getInt("page_index"), rs.getString("page_text")));
                }
                return pages;
            }
        }
    }

    /**
     * Drops a version's previous passages and their provenance before a re-run inserts
     * its own, so re-running replaces and never duplicates. References are detached
     * first: SQLite enforces foreign keys immediately.
     *
     * @throws SQLException if any statement fails
     */
    public void clearForDocumentVersion(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET passage_id = NULL WHERE passage_id IN"
                        + " (SELECT passage_id FROM passage WHERE document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE passage SET provenance_id = NULL WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = ? AND entity_id IN"
                        + " (SELECT passage_id FROM passage WHERE document_version_id = ?)")) {
            ps.setString(1, ENTITY_TYPE);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM passage WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one passage of a version.
     *
     * @param documentVersionId version the passage was split from
     * @param passageIndex 1-based chapter order
     * @param heading the chapter heading as stored
     * @param topic pack passage topic, or null when the heading matched no spec
     * @param pageFrom first page of the passage
     * @param pageTo last page carrying the passage's text
     * @param summary one-line chapter summary, or null
     * @return the new {@code passage_id}
     * @throws SQLException if the insert fails
     */
    public long insertPassage(long documentVersionId, int passageIndex, String heading,
            String topic, int pageFrom, int pageTo, String summary) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO passage (document_version_id, passage_index, heading, topic,"
                        + " page_from, page_to, summary) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, passageIndex);
            ps.setString(3, heading);
            if (topic == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, topic);
            }
            ps.setInt(5, pageFrom);
            ps.setInt(6, pageTo);
            if (summary == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, summary);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("passage insert returned no id for heading " + heading);
    }

    /**
     * Points a passage at its source provenance row.
     *
     * @throws SQLException if the update fails
     */
    public void linkProvenance(long passageId, long provenanceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE passage SET provenance_id = ? WHERE passage_id = ?")) {
            ps.setLong(1, provenanceId);
            ps.setLong(2, passageId);
            ps.executeUpdate();
        }
    }

    /**
     * The mandate's passages in document-then-chapter order, each carrying the page of
     * its heading and its stored text as verbatim. Section of {@link MandateQa}.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.PassageRow> passagesForMandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.passage_id, p.document_version_id, p.passage_index, p.heading,"
                        + " p.topic, p.page_from, p.page_to, p.summary,"
                        + " pg.page_index AS provenance_page, pd.verbatim"
                        + " FROM passage p"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = p.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN provenance_doc pd ON pd.provenance_id = p.provenance_id"
                        + " LEFT JOIN page pg ON pg.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dv.document_id, p.document_version_id, p.passage_index")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.PassageRow> rows = new ArrayList<>();
                while (rs.next()) {
                    int provenancePage = rs.getInt("provenance_page");
                    boolean hasProvenancePage = !rs.wasNull();
                    int pageFrom = rs.getInt("page_from");
                    rows.add(new MandateQa.PassageRow(
                            rs.getLong("passage_id"),
                            rs.getLong("document_version_id"),
                            rs.getInt("passage_index"),
                            rs.getString("heading"),
                            rs.getString("topic"),
                            pageFrom,
                            rs.getInt("page_to"),
                            hasProvenancePage ? provenancePage : pageFrom,
                            rs.getString("verbatim"),
                            rs.getString("summary"),
                            List.of()));
                }
                return rows;
            }
        }
    }
}
