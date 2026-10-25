package com.resurgent.tev.doc.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.query.MandateQa;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence and retrieval for M3-08 clauses. The repository never commits — the caller
 * owns the transaction, like {@link AssessmentRepository} and {@link FactRepository}.
 *
 * <p>Reads return the {@link MandateQa.ClauseRow} section of the mandate QA API. Writes
 * are replace-per-run: {@link #deleteClausesForVersion} drops a version's clauses (slots
 * cascade) and their provenance before the new run inserts.
 */
public final class ClauseRepository {

    /** Provenance entity type for every clause row. */
    public static final String CLAUSE_ENTITY_TYPE = "clause";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public ClauseRepository(Connection connection) {
        this.connection = connection;
    }

    /** One clause to persist. */
    public record ClauseWrite(
            long documentVersionId,
            Long parseRunId,
            Long clauseTypeId,
            String heading,
            String verbatim,
            Integer pageStart,
            Integer pageEnd,
            String status,
            String novelty,
            String summary,
            String importance,
            String extractionMethod) {}

    /** One normalised key number to persist under a clause. */
    public record SlotWrite(
            String slotKey,
            String valueText,
            Double valueNumber,
            String unit,
            String currency,
            String scale,
            String valueDate) {}

    /**
     * Drops a version's previous clause reading: its clauses (slots cascade) and the
     * provenance rows written for them. Must run before a re-run inserts its own rows.
     *
     * @throws SQLException if any statement fails
     */
    public void deleteClausesForVersion(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM clause WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = ? AND document_version_id = ?")) {
            ps.setString(1, CLAUSE_ENTITY_TYPE);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * The clause type row a pack declares, created on first use. One row per
     * {@code (pack, code)}; a re-published pack version refreshes label/family/aliases.
     *
     * @return {@code clause_type_id}
     * @throws SQLException if the upsert fails
     */
    public long upsertPackType(String packCode, String packVersion, PackClauseType type)
            throws SQLException {
        // No generated-key read here: on the conflict path a fresh connection returns
        // key 0, so the id is always resolved with a SELECT after the upsert.
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO clause_type (code, label, parent_family, aliases_json,"
                        + " pack_code, pack_version, source, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'pack', 'active')"
                        + " ON CONFLICT (pack_code, code) DO UPDATE SET"
                        + " label = excluded.label, parent_family = excluded.parent_family,"
                        + " aliases_json = excluded.aliases_json,"
                        + " pack_version = excluded.pack_version")) {
            ps.setString(1, type.key());
            ps.setString(2, type.label());
            if (type.parentFamily() == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, type.parentFamily());
            }
            ps.setString(4, aliasesJson(type.aliases()));
            ps.setString(5, packCode);
            ps.setString(6, packVersion);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT clause_type_id FROM clause_type WHERE pack_code = ? AND code = ?")) {
            ps.setString(1, packCode);
            ps.setString(2, type.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("clause_type upsert returned no row for " + packCode + "#" + type.key());
    }

    /**
     * The row for a type proposed by Pass B, created on first proposal and reused after.
     * Proposals carry no pack scope; {@code status = 'open'} until #142 promotes them.
     *
     * @return {@code clause_type_id}
     * @throws SQLException if the insert/lookup fails
     */
    public long findOrInsertProposedType(String code, String label, String parentFamily)
            throws SQLException {
        Long existing = proposedTypeId(code);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO clause_type (code, label, parent_family, aliases_json,"
                        + " pack_code, pack_version, source, status)"
                        + " VALUES (?, ?, ?, NULL, NULL, NULL, 'proposed', 'open')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, label);
            if (parentFamily == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, parentFamily);
            }
            ps.executeUpdate();
            Long id = generatedId(ps);
            if (id != null) {
                return id;
            }
        }
        Long id = proposedTypeId(code);
        if (id == null) {
            throw new SQLException("proposed clause_type insert returned no row for " + code);
        }
        return id;
    }

    private Long proposedTypeId(String code) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT clause_type_id FROM clause_type"
                        + " WHERE code = ? AND source = 'proposed'"
                        + " ORDER BY clause_type_id LIMIT 1")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * Flips an open proposal to a promoted clause type scoped to the pack version that
     * learned it (M3-09 auto-promotion, no human step). The proposal row keeps its
     * {@code source = 'proposed'} history; later Pass A binding upserts the pack type
     * onto the same {@code (pack_code, code)} row.
     *
     * @return rows updated (0 when the proposal was already promoted or absent)
     * @throws SQLException if the update fails
     */
    public int markPromoted(String code, String packCode, String packVersion) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clause_type SET status = 'promoted', pack_code = ?, pack_version = ?"
                        + " WHERE code = ? AND source = 'proposed' AND status = 'open'")) {
            ps.setString(1, packCode);
            ps.setString(2, packVersion);
            ps.setString(3, code);
            return ps.executeUpdate();
        }
    }

    /**
     * Inserts one clause row.
     *
     * @return the generated {@code clause_id}
     * @throws SQLException if the insert fails
     */
    public long insertClause(ClauseWrite write) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO clause (document_version_id, parse_run_id, clause_type_id,"
                        + " heading, verbatim_text, page_start, page_end, status, novelty,"
                        + " summary, importance, extraction_method)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, write.documentVersionId());
            setLong(ps, 2, write.parseRunId());
            setLong(ps, 3, write.clauseTypeId());
            if (write.heading() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, write.heading());
            }
            ps.setString(5, write.verbatim());
            setInt(ps, 6, write.pageStart());
            setInt(ps, 7, write.pageEnd());
            ps.setString(8, write.status());
            ps.setString(9, write.novelty());
            if (write.summary() == null) {
                ps.setNull(10, Types.VARCHAR);
            } else {
                ps.setString(10, write.summary());
            }
            if (write.importance() == null) {
                ps.setNull(11, Types.VARCHAR);
            } else {
                ps.setString(11, write.importance());
            }
            if (write.extractionMethod() == null) {
                ps.setNull(12, Types.VARCHAR);
            } else {
                ps.setString(12, write.extractionMethod());
            }
            ps.executeUpdate();
            Long id = generatedId(ps);
            if (id == null) {
                throw new SQLException("clause insert returned no generated key");
            }
            return id;
        }
    }

    /**
     * Attaches the provenance row that anchors a clause to the layout.
     *
     * @throws SQLException if the update fails
     */
    public void attachProvenance(long clauseId, long provenanceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clause SET provenance_id = ? WHERE clause_id = ?")) {
            ps.setLong(1, provenanceId);
            ps.setLong(2, clauseId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts a clause's key number; duplicates within one clause are ignored.
     *
     * @throws SQLException if the insert fails
     */
    public void insertSlot(long clauseId, SlotWrite slot) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO clause_slot (clause_id, slot_key, value_text,"
                        + " value_number, unit, currency, scale, value_date)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, clauseId);
            ps.setString(2, slot.slotKey());
            ps.setString(3, slot.valueText());
            if (slot.valueNumber() == null) {
                ps.setNull(4, Types.REAL);
            } else {
                ps.setDouble(4, slot.valueNumber());
            }
            setString(ps, 5, slot.unit());
            setString(ps, 6, slot.currency());
            setString(ps, 7, slot.scale());
            setString(ps, 8, slot.valueDate());
            ps.executeUpdate();
        }
    }

    /**
     * Page texts for clause reading: the layout block text exactly as stored (newlines
     * preserved), one entry per page, else the stored parse markdown/text. No PDF is
     * re-parsed.
     *
     * @throws SQLException if the query fails
     */
    public List<PageText> pageTextsForClauses(long documentVersionId) throws SQLException {
        // Sibling works keep physical pages on the file version. Membership is
        // document_page, so a later agreement is still mined and a neighbour's
        // page is not. A version with no recorded membership reads the pages it owns.
        if (hasMemberPages(documentVersionId)) {
            return readClausePages(documentVersionId, """
                    SELECT p.page_index AS page_index, b.text_raw AS block_text
                      FROM block b
                      JOIN page p ON p.page_id = b.page_id
                      JOIN document_page dp ON dp.page_id = p.page_id
                      JOIN document_version dv ON dv.document_id = dp.document_id
                     WHERE dv.document_version_id = ?
                     ORDER BY p.page_index, COALESCE(b.reading_order, b.block_index)
                    """);
        }
        List<PageText> pages = readClausePages(documentVersionId, """
                SELECT p.page_index AS page_index, b.text_raw AS block_text
                  FROM block b
                  JOIN page p ON p.page_id = b.page_id
                 WHERE p.document_version_id = ?
                 ORDER BY p.page_index, COALESCE(b.reading_order, b.block_index)
                """);
        if (!pages.isEmpty()) {
            return pages;
        }
        List<String> cloudPages = new CloudParsePageRepository(connection)
                .loadTextPages(documentVersionId);
        List<PageText> fallback = new ArrayList<>();
        for (int i = 0; i < cloudPages.size(); i++) {
            fallback.add(new PageText(i + 1, cloudPages.get(i)));
        }
        return fallback;
    }

    private boolean hasMemberPages(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM document_page dp"
                        + " JOIN document_version dv ON dv.document_id = dp.document_id"
                        + " WHERE dv.document_version_id = ? LIMIT 1")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<PageText> readClausePages(long documentVersionId, String sql) throws SQLException {
        List<PageText> pages = new ArrayList<>();
        int currentPage = -1;
        StringBuilder buffer = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pageIndex = rs.getInt("page_index");
                    String text = rs.getString("block_text");
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    if (pageIndex != currentPage) {
                        if (currentPage > 0 && !buffer.isEmpty()) {
                            pages.add(new PageText(currentPage, buffer.toString()));
                        }
                        currentPage = pageIndex;
                        buffer.setLength(0);
                    }
                    if (!buffer.isEmpty()) {
                        buffer.append('\n');
                    }
                    buffer.append(text.strip());
                }
                if (currentPage > 0 && !buffer.isEmpty()) {
                    pages.add(new PageText(currentPage, buffer.toString()));
                }
            }
        }
        return pages;
    }

    /**
     * Every clause of the mandate with its type and normalised key numbers, in
     * document/version/clause order.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ClauseRow> clauses(long mandateId) throws SQLException {
        Map<Long, List<MandateQa.ClauseSlotRow>> slotsByClause = slots(mandateId);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cl.clause_id, cl.document_version_id, dv.document_id, d.logical_name,"
                        + " dv.file_name, cl.heading, cl.verbatim_text, cl.page_start,"
                        + " cl.page_end, cl.status, cl.novelty, cl.summary, cl.importance,"
                        + " cl.extraction_method, ct.code AS type_code, ct.label AS type_label,"
                        + " ct.parent_family"
                        + " FROM clause cl"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = cl.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN clause_type ct ON ct.clause_type_id = cl.clause_type_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dv.document_id, dv.document_version_id, cl.clause_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ClauseRow> rows = new ArrayList<>();
                while (rs.next()) {
                    long clauseId = rs.getLong("clause_id");
                    rows.add(new MandateQa.ClauseRow(
                            clauseId,
                            rs.getLong("document_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("logical_name"),
                            rs.getString("file_name"),
                            rs.getString("heading"),
                            rs.getString("verbatim_text"),
                            asInt(rs.getObject("page_start")),
                            asInt(rs.getObject("page_end")),
                            rs.getString("type_code"),
                            rs.getString("type_label"),
                            rs.getString("parent_family"),
                            rs.getString("status"),
                            rs.getString("novelty"),
                            rs.getString("summary"),
                            rs.getString("importance"),
                            rs.getString("extraction_method"),
                            slotsByClause.getOrDefault(clauseId, List.of())));
                }
                return List.copyOf(rows);
            }
        }
    }

    private Map<Long, List<MandateQa.ClauseSlotRow>> slots(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cs.clause_id, cs.slot_key, cs.value_text, cs.value_number, cs.unit,"
                        + " cs.currency, cs.scale"
                        + " FROM clause_slot cs"
                        + " JOIN clause cl ON cl.clause_id = cs.clause_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = cl.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY cs.clause_slot_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, List<MandateQa.ClauseSlotRow>> byClause = new LinkedHashMap<>();
                while (rs.next()) {
                    byClause.computeIfAbsent(rs.getLong("clause_id"), k -> new ArrayList<>())
                            .add(new MandateQa.ClauseSlotRow(
                                    rs.getString("slot_key"),
                                    rs.getString("value_text"),
                                    asDouble(rs.getObject("value_number")),
                                    rs.getString("unit"),
                                    rs.getString("currency"),
                                    rs.getString("scale")));
                }
                return byClause;
            }
        }
    }

    private static String aliasesJson(List<String> aliases) {
        try {
            return MAPPER.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            return keys.next() ? keys.getLong(1) : null;
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static Integer asInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
