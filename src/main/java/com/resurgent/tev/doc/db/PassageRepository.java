package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.MandateQa;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<Long, List<MandateQa.PassageFactRow>> factsByPassage = factsForMandate(mandateId);
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
                    long passageId = rs.getLong("passage_id");
                    rows.add(new MandateQa.PassageRow(
                            passageId,
                            rs.getLong("document_version_id"),
                            rs.getInt("passage_index"),
                            rs.getString("heading"),
                            rs.getString("topic"),
                            pageFrom,
                            rs.getInt("page_to"),
                            hasProvenancePage ? provenancePage : pageFrom,
                            rs.getString("verbatim"),
                            rs.getString("summary"),
                            factsByPassage.getOrDefault(passageId, List.of())));
                }
                return rows;
            }
        }
    }

    /**
     * The mandate's passage-linked facts with their verbatim evidence, grouped by
     * passage. Row facts keep their line key and evidence order, so listed tables come
     * back in source order.
     */
    private Map<Long, List<MandateQa.PassageFactRow>> factsForMandate(long mandateId)
            throws SQLException {
        Map<Long, PassageFactBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id, f.passage_id, f.field_key, f.ontology_path, f.line_key,"
                        + " f.value_text, f.value_number, f.value_date, f.unit, f.currency,"
                        + " f.confidence, f.extraction_method"
                        + " FROM fact f"
                        + " JOIN passage p ON p.passage_id = f.passage_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = p.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY f.passage_id, f.fact_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PassageFactBuilder b = new PassageFactBuilder(
                            rs.getLong("fact_id"),
                            rs.getLong("passage_id"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            rs.getString("line_key"),
                            rs.getString("value_text"),
                            asDouble(rs.getObject("value_number")),
                            rs.getString("value_date"),
                            rs.getString("unit"),
                            rs.getString("currency"),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("extraction_method"));
                    builders.put(b.factId, b);
                }
            }
        }
        if (builders.isEmpty()) {
            return Map.of();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.fact_id, COALESCE(pd.verbatim, e.source_text) AS quote,"
                        + " pg.page_index AS page_index"
                        + " FROM fact_evidence e"
                        + " JOIN fact f ON f.fact_id = e.fact_id"
                        + " JOIN passage p ON p.passage_id = f.passage_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = p.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN provenance_doc pd ON pd.provenance_id = e.provenance_id"
                        + " LEFT JOIN page pg ON pg.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " AND e.role = 'verbatim_quote'"
                        + " ORDER BY e.fact_id, e.ordinal")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PassageFactBuilder b = builders.get(rs.getLong("fact_id"));
                    if (b != null) {
                        b.addEvidence(rs.getString("quote"), asInteger(rs.getObject("page_index")));
                    }
                }
            }
        }
        Map<Long, List<MandateQa.PassageFactRow>> factsByPassage = new LinkedHashMap<>();
        for (PassageFactBuilder b : builders.values()) {
            MandateQa.PassageFactRow row = b.build();
            factsByPassage.computeIfAbsent(b.passageId, k -> new ArrayList<>()).add(row);
        }
        return factsByPassage;
    }

    /** Evidence quotes may carry no page, so the list copies tolerate nulls. */
    private static final class PassageFactBuilder {
        final long factId;
        final long passageId;
        final String fieldKey;
        final String ontologyPath;
        final String lineKey;
        final String valueText;
        final Double valueNumber;
        final String valueDate;
        final String unit;
        final String currency;
        final Double confidence;
        final String extractionMethod;
        final List<String> quotes = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();

        PassageFactBuilder(long factId, long passageId, String fieldKey, String ontologyPath,
                String lineKey, String valueText, Double valueNumber, String valueDate,
                String unit, String currency, Double confidence, String extractionMethod) {
            this.factId = factId;
            this.passageId = passageId;
            this.fieldKey = fieldKey;
            this.ontologyPath = ontologyPath;
            this.lineKey = lineKey;
            this.valueText = valueText;
            this.valueNumber = valueNumber;
            this.valueDate = valueDate;
            this.unit = unit;
            this.currency = currency;
            this.confidence = confidence;
            this.extractionMethod = extractionMethod;
        }

        void addEvidence(String quote, Integer page) {
            if (quote != null) {
                quotes.add(quote);
            }
            pages.add(page);
        }

        MandateQa.PassageFactRow build() {
            return new MandateQa.PassageFactRow(factId, fieldKey, ontologyPath, lineKey,
                    valueText, valueNumber, valueDate, unit, currency, confidence,
                    extractionMethod,
                    Collections.unmodifiableList(new ArrayList<>(quotes)),
                    Collections.unmodifiableList(new ArrayList<>(pages)));
        }
    }

    /** SQLite JDBC returns Integer for small INTEGER values; widen safely. */
    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /** SQLite JDBC returns Integer or Double for REAL/INTEGER values; widen safely. */
    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(value.toString());
    }
}
