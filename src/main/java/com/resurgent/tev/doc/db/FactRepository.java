package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistence for extracted facts, evidence, and provenance (Architecture §6.5, S2.5).
 * Re-extract replaces the fact set for a parse_run (children first). Quote resolution
 * matches {@code span.text_raw} → {@code block.text_raw} → {@code table_cell.text_raw}
 * on the cited page; unresolved quotes stay citable with {@code resolution='missing'}.
 *
 * <p>Connection is owned by the caller; methods never commit or roll back.
 */
public final class FactRepository {

    private final Connection connection;

    public FactRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /** One normalized fact ready for insert, with its citations and derived parents. */
    public record FactWrite(
            String fieldKey,
            String ontologyPath,
            String pathRoot,
            String subjectEntity,
            String valueKind,
            String valueText,
            Double valueNumber,
            String valueDate,
            String unit,
            String currency,
            String scale,
            String absoluteAmount,
            String langSource,
            String extractionMethod,
            String extractorId,
            Double confidence,
            String statedOrDerived,
            String lineKey,
            String groupKeysJson,
            List<Citation> citations,
            List<String> derivedFromKeys) {

        public FactWrite {
            citations = citations == null ? List.of() : List.copyOf(citations);
            derivedFromKeys = derivedFromKeys == null ? List.of() : List.copyOf(derivedFromKeys);
        }
    }

    /** Bind mutation applied by {@code PackPathBinder}. */
    public record FactBindRow(
            long factId,
            String fieldKey,
            String extractionMethod,
            String ontologyPath,
            String pathRoot,
            String statedOrDerived) {}

    /** Outcome of a replace: counts for run_stat / tests. */
    public record ReplaceStats(
            int factsInserted,
            int evidenceInserted,
            int provenanceInserted,
            int quotesResolved,
            int quotesMissing) {}

    /**
     * Replaces every fact (and its {@code fact_value} / {@code fact_evidence} children)
     * for the parse_run, then inserts the new set with evidence and provenance.
     * Callers wrap this in a transaction so a failure leaves no half-written set.
     *
     * @param parseRunId extract parse_run identity
     * @param documentVersionId version the facts are about
     * @param facts normalized candidates (derived facts should follow their parents)
     * @return insert counts
     * @throws SQLException if any insert or delete fails
     */
    public ReplaceStats replaceFacts(long parseRunId, long documentVersionId, List<FactWrite> facts)
            throws SQLException {
        deleteFactsForParseRun(parseRunId);
        if (facts == null || facts.isEmpty()) {
            return new ReplaceStats(0, 0, 0, 0, 0);
        }

        String now = Timestamps.now();
        List<Long> factIds = new ArrayList<>(facts.size());
        Map<String, List<Long>> idsByFieldKey = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact (parse_run_id, document_version_id, region_id, field_key,"
                        + " ontology_path, path_root, subject_entity, value_kind, value_text,"
                        + " value_number, value_date, unit, currency, scale, absolute_amount,"
                        + " lang_source, extraction_method, extractor_id, confidence,"
                        + " nomenclature_status, unbound_reason, stated_or_derived, line_key,"
                        + " group_keys_json, valid_from, valid_to, is_current, created_at)"
                        + " VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " 'unbound', NULL, ?, ?, ?, NULL, NULL, 1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (FactWrite f : facts) {
                ps.setLong(1, parseRunId);
                ps.setLong(2, documentVersionId);
                setNullableString(ps, 3, f.fieldKey());
                setNullableString(ps, 4, f.ontologyPath());
                setNullableString(ps, 5, f.pathRoot());
                setNullableString(ps, 6, f.subjectEntity());
                ps.setString(7, f.valueKind());
                setNullableString(ps, 8, f.valueText());
                setNullableDouble(ps, 9, f.valueNumber());
                setNullableString(ps, 10, f.valueDate());
                setNullableString(ps, 11, f.unit());
                setNullableString(ps, 12, f.currency());
                setNullableString(ps, 13, f.scale());
                setNullableString(ps, 14, f.absoluteAmount());
                setNullableString(ps, 15, f.langSource());
                ps.setString(16, f.extractionMethod());
                setNullableString(ps, 17, f.extractorId());
                setNullableDouble(ps, 18, f.confidence());
                setNullableString(ps, 19, f.statedOrDerived());
                setNullableString(ps, 20, f.lineKey());
                setNullableString(ps, 21, f.groupKeysJson());
                ps.setString(22, now);
                ps.executeUpdate();
                long factId = generatedId(ps);
                factIds.add(factId);
                if (f.fieldKey() != null) {
                    idsByFieldKey.computeIfAbsent(f.fieldKey(), k -> new ArrayList<>()).add(factId);
                }
            }
        }

        int evidenceInserted = 0;
        int provenanceInserted = 0;
        int quotesResolved = 0;
        int quotesMissing = 0;
        for (int i = 0; i < facts.size(); i++) {
            FactWrite f = facts.get(i);
            long factId = factIds.get(i);
            int ordinal = 0;
            for (Citation citation : f.citations()) {
                QuoteHit hit = resolveQuote(documentVersionId, citation);
                long provenanceId = insertProvenance(factId, documentVersionId, citation, hit);
                provenanceInserted++;
                String resolution = hit.resolved() ? "resolved" : "missing";
                if (hit.resolved()) {
                    quotesResolved++;
                } else {
                    quotesMissing++;
                }
                insertFactEvidence(factId, ordinal++, "verbatim_quote", provenanceId,
                        citation.verbatim(), resolution, null, null);
                evidenceInserted++;
            }
            if ("derived".equals(f.extractionMethod()) && !f.derivedFromKeys().isEmpty()) {
                for (String parentKey : f.derivedFromKeys()) {
                    List<Long> parentIds = idsByFieldKey.get(parentKey);
                    Long parentId = parentIds == null || parentIds.isEmpty() ? null : parentIds.get(0);
                    insertFactEvidence(factId, ordinal++, "derived_from", null,
                            parentKey, "resolved", null,
                            parentId == null ? null : String.valueOf(parentId));
                    evidenceInserted++;
                }
            }
        }
        return new ReplaceStats(facts.size(), evidenceInserted, provenanceInserted,
                quotesResolved, quotesMissing);
    }

    /**
     * Deletes the fact set for a parse_run (children first). Used by replace and tests.
     *
     * @throws SQLException if any delete fails
     */
    public void deleteFactsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_value WHERE fact_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_evidence WHERE fact_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = 'fact' AND entity_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    /**
     * Quote resolution: exact match on {@code span.text_raw}, then {@code block.text_raw},
     * then {@code table_cell.text_raw} for the cited page. First hit wins.
     */
    public QuoteHit resolveQuote(long documentVersionId, Citation citation) throws SQLException {
        Long pageId = findPageId(documentVersionId, citation.pageIndex());
        if (pageId == null) {
            return QuoteHit.missing(citation.verbatim(), citation.pageIndex());
        }
        // span
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT s.span_id, s.block_id FROM span s"
                        + " JOIN block b ON b.block_id = s.block_id"
                        + " WHERE b.page_id = ? AND s.text_raw = ? LIMIT 1")) {
            ps.setLong(1, pageId);
            ps.setString(2, citation.verbatim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new QuoteHit(true, pageId, rs.getLong("block_id"),
                            rs.getLong("span_id"), null, citation);
                }
            }
        }
        // block
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT block_id FROM block WHERE page_id = ? AND text_raw = ? LIMIT 1")) {
            ps.setLong(1, pageId);
            ps.setString(2, citation.verbatim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new QuoteHit(true, pageId, rs.getLong("block_id"), null, null, citation);
                }
            }
        }
        // table_cell
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT tc.table_cell_id, tg.block_id FROM table_cell tc"
                        + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " WHERE b.page_id = ? AND tc.text_raw = ? LIMIT 1")) {
            ps.setLong(1, pageId);
            ps.setString(2, citation.verbatim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new QuoteHit(true, pageId, rs.getLong("block_id"), null,
                            rs.getLong("table_cell_id"), citation);
                }
            }
        }
        return QuoteHit.missing(citation, pageId);
    }

    /**
     * Retrieval surface (S2.13): fact rows with joined evidence, sorted by
     * ontology path then confidence descending. No PDF re-parse.
     *
     * @param query filter bag (all filters optional)
     * @return matching rows with evidence lists
     * @throws SQLException if the lookup fails
     */
    public List<FactRow> query(FactQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT f.fact_id, f.ontology_path, f.path_root, f.field_key, f.value_kind,"
                        + " f.value_text,"
                        + " f.value_number, f.value_date, f.unit, f.currency, f.scale,"
                        + " f.absolute_amount, f.subject_entity, f.line_key, f.document_version_id,"
                        + " dv.file_name, f.extraction_method, f.confidence, f.stated_or_derived"
                        + " FROM fact f"
                        + " JOIN document_version dv ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (query.mandateId() != null) {
            sql.append(" AND d.mandate_id = ?");
            params.add(query.mandateId());
        }
        if (query.documentId() != null) {
            sql.append(" AND d.document_id = ?");
            params.add(query.documentId());
        }
        if (query.documentVersionId() != null) {
            sql.append(" AND f.document_version_id = ?");
            params.add(query.documentVersionId());
        }
        if (query.pathPrefix() != null && !query.pathPrefix().isBlank()) {
            sql.append(" AND f.ontology_path LIKE ?");
            params.add(query.pathPrefix().trim() + "%");
        }
        if (query.valueKind() != null && !query.valueKind().isBlank()) {
            sql.append(" AND f.value_kind = ?");
            params.add(query.valueKind().trim().toLowerCase(Locale.ROOT));
        }
        if (query.currentOnly()) {
            sql.append(" AND f.is_current = 1");
        }
        sql.append(" ORDER BY f.ontology_path IS NULL,"
                + " CASE f.path_root"
                + " WHEN 'identity' THEN 0"
                + " WHEN 'economic' THEN 1"
                + " WHEN 'technical' THEN 2"
                + " WHEN 'commercial' THEN 3"
                + " WHEN 'legal' THEN 4"
                + " WHEN 'regulatory' THEN 5"
                + " WHEN 'frame' THEN 6"
                + " ELSE 7 END,"
                + " f.ontology_path, f.confidence DESC, f.fact_id");

        Map<Long, FactRowBuilder> builders = new LinkedHashMap<>();
        List<Long> order = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                bindObject(ps, i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long factId = rs.getLong("fact_id");
                    FactRowBuilder b = new FactRowBuilder(
                            factId,
                            rs.getString("ontology_path"),
                            rs.getString("path_root"),
                            rs.getString("field_key"),
                            rs.getString("value_kind"),
                            rs.getString("value_text"),
                            asDouble(rs.getObject("value_number")),
                            rs.getString("value_date"),
                            rs.getString("unit"),
                            rs.getString("currency"),
                            rs.getString("scale"),
                            rs.getString("absolute_amount"),
                            rs.getString("subject_entity"),
                            rs.getString("line_key"),
                            rs.getLong("document_version_id"),
                            rs.getString("file_name"),
                            rs.getString("extraction_method"),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("stated_or_derived"));
                    builders.put(factId, b);
                    order.add(factId);
                }
            }
        }
        if (order.isEmpty()) {
            return List.of();
        }
        loadEvidence(builders);
        List<FactRow> rows = new ArrayList<>(order.size());
        for (Long id : order) {
            rows.add(builders.get(id).build());
        }
        return rows;
    }

    /** Facts of a parse_run for bind (id, field_key, method, current path, stated flag). */
    public List<FactBindRow> selectFactsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT fact_id, field_key, extraction_method, ontology_path, path_root,"
                        + " stated_or_derived FROM fact WHERE parse_run_id = ?"
                        + " ORDER BY fact_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FactBindRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new FactBindRow(
                            rs.getLong("fact_id"),
                            rs.getString("field_key"),
                            rs.getString("extraction_method"),
                            rs.getString("ontology_path"),
                            rs.getString("path_root"),
                            rs.getString("stated_or_derived")));
                }
                return rows;
            }
        }
    }

    /**
     * Applies a bind decision to one fact. {@code nomenclatureStatus} is
     * {@code bound} | {@code not_applicable} | {@code unbound}.
     */
    public void updateFactBinding(long factId, String ontologyPath, String pathRoot,
            String nomenclatureStatus, String unboundReason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET ontology_path = ?, path_root = ?, nomenclature_status = ?,"
                        + " unbound_reason = ? WHERE fact_id = ?")) {
            setNullableString(ps, 1, ontologyPath);
            setNullableString(ps, 2, pathRoot);
            ps.setString(3, nomenclatureStatus);
            setNullableString(ps, 4, unboundReason);
            ps.setLong(5, factId);
            ps.executeUpdate();
        }
    }

    /** Upserts a run_stat row (stage {@code doc-extract} / {@code doc-bind}). */
    public void recordRunStat(long parseRunId, String stage, String name, Double num, String text)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_stat (parse_run_id, stage, name, value_num, value_text)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (parse_run_id, stage, name) DO UPDATE SET"
                        + " value_num = excluded.value_num, value_text = excluded.value_text,"
                        + " recorded_at = CURRENT_TIMESTAMP")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, stage);
            ps.setString(3, name);
            if (num != null) {
                ps.setDouble(4, num);
            } else {
                ps.setNull(4, Types.DOUBLE);
            }
            ps.setString(5, text);
            ps.executeUpdate();
        }
    }

    /** Writes an audit_log event (e.g. {@code extract_completed}). */
    public long insertAuditLog(Long parseRunId, String eventType, String eventAt,
            String payload, String severity) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (parse_run_id, event_type, event_at, payload, severity)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (parseRunId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setLong(1, parseRunId);
            }
            ps.setString(2, eventType);
            ps.setString(3, eventAt);
            ps.setString(4, payload);
            ps.setString(5, severity);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** Sets {@code document_version.ingest_status} (e.g. {@code extracted}). */
    public void updateVersionIngestStatus(long documentVersionId, String ingestStatus)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET ingest_status = ? WHERE document_version_id = ?")) {
            ps.setString(1, ingestStatus);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    /** Count of facts for a parse_run (tests / run_stat). */
    public long countFactsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM fact WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Count of fact_evidence rows for a parse_run. */
    public long countEvidenceForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM fact_evidence e"
                        + " JOIN fact f ON f.fact_id = e.fact_id"
                        + " WHERE f.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- internals ---

    /** Resolved (or missing) layout anchors for one quote. */
    public record QuoteHit(
            boolean resolved,
            Long pageId,
            Long blockId,
            Long spanId,
            Long tableCellId,
            Citation citation) {

        static QuoteHit missing(String verbatim, int pageIndex) {
            return new QuoteHit(false, null, null, null, null, Citation.of(pageIndex, verbatim));
        }

        static QuoteHit missing(Citation citation, Long pageId) {
            return new QuoteHit(false, pageId, null, null, null, citation);
        }
    }

    private static final class FactRowBuilder {
        final long factId;
        final String ontologyPath;
        final String pathRoot;
        final String fieldKey;
        final String valueKind;
        final String valueText;
        final Double valueNumber;
        final String valueDate;
        final String unit;
        final String currency;
        final String scale;
        final String absoluteAmount;
        final String subjectEntity;
        final String lineKey;
        final long documentVersionId;
        final String fileName;
        final String extractionMethod;
        final Double confidence;
        final String statedOrDerived;
        final List<FactRow.EvidenceRow> evidence = new ArrayList<>();

        FactRowBuilder(long factId, String ontologyPath, String pathRoot, String fieldKey,
                String valueKind, String valueText, Double valueNumber, String valueDate,
                String unit, String currency, String scale, String absoluteAmount,
                String subjectEntity, String lineKey, long documentVersionId, String fileName,
                String extractionMethod, Double confidence, String statedOrDerived) {
            this.factId = factId;
            this.ontologyPath = ontologyPath;
            this.pathRoot = pathRoot;
            this.fieldKey = fieldKey;
            this.valueKind = valueKind;
            this.valueText = valueText;
            this.valueNumber = valueNumber;
            this.valueDate = valueDate;
            this.unit = unit;
            this.currency = currency;
            this.scale = scale;
            this.absoluteAmount = absoluteAmount;
            this.subjectEntity = subjectEntity;
            this.lineKey = lineKey;
            this.documentVersionId = documentVersionId;
            this.fileName = fileName;
            this.extractionMethod = extractionMethod;
            this.confidence = confidence;
            this.statedOrDerived = statedOrDerived;
        }

        FactRow build() {
            return new FactRow(factId, ontologyPath, pathRoot, fieldKey, valueKind, valueText,
                    valueNumber, valueDate, unit, currency, scale, absoluteAmount, subjectEntity,
                    lineKey, documentVersionId, fileName, extractionMethod, confidence,
                    statedOrDerived, evidence);
        }
    }

    private void loadEvidence(Map<Long, FactRowBuilder> builders) throws SQLException {
        if (builders.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT e.fact_id, e.role, e.provenance_id, e.source_text, e.resolution,"
                        + " p.verbatim, p.bbox_x0, p.bbox_y0, p.bbox_x1, p.bbox_y1,"
                        + " pg.page_index"
                        + " FROM fact_evidence e"
                        + " LEFT JOIN provenance_doc p ON p.provenance_id = e.provenance_id"
                        + " LEFT JOIN page pg ON pg.page_id = p.page_id"
                        + " WHERE e.fact_id IN (");
        List<Long> ids = new ArrayList<>(builders.keySet());
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") ORDER BY e.fact_id, e.ordinal");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FactRowBuilder b = builders.get(rs.getLong("fact_id"));
                    if (b == null) {
                        continue;
                    }
                    Long provenanceId = asLong(rs.getObject("provenance_id"));
                    Integer pageIndex = asInteger(rs.getObject("page_index"));
                    b.evidence.add(new FactRow.EvidenceRow(
                            rs.getString("role"),
                            rs.getString("verbatim") != null
                                    ? rs.getString("verbatim") : rs.getString("source_text"),
                            pageIndex,
                            asDouble(rs.getObject("bbox_x0")),
                            asDouble(rs.getObject("bbox_y0")),
                            asDouble(rs.getObject("bbox_x1")),
                            asDouble(rs.getObject("bbox_y1")),
                            provenanceId,
                            rs.getString("resolution")));
                }
            }
        }
    }

    /** SQLite JDBC returns Integer for small INTEGER values; widen safely. */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    private Long findPageId(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long insertProvenance(long factId, long documentVersionId, Citation citation,
            QuoteHit hit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO provenance_doc (entity_type, entity_id, document_version_id,"
                        + " page_id, block_id, span_id, table_cell_id, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, char_start, char_end, verbatim, quote_hash,"
                        + " confidence, is_derived)"
                        + " VALUES ('fact', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, factId);
            ps.setLong(2, documentVersionId);
            setLong(ps, 3, hit.pageId());
            setLong(ps, 4, hit.blockId());
            setLong(ps, 5, hit.spanId());
            setLong(ps, 6, hit.tableCellId());
            setDouble(ps, 7, citation.bboxX0());
            setDouble(ps, 8, citation.bboxY0());
            setDouble(ps, 9, citation.bboxX1());
            setDouble(ps, 10, citation.bboxY1());
            ps.setString(11, citation.verbatim());
            setDouble(ps, 12, null);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void insertFactEvidence(long factId, int ordinal, String role, Long provenanceId,
            String sourceText, String resolution, String normalizedValue, String ruleId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact_evidence (fact_id, ordinal, role, provenance_id, source_text,"
                        + " resolution, normalized_value, rule_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, factId);
            ps.setInt(2, ordinal);
            ps.setString(3, role);
            setLong(ps, 4, provenanceId);
            setNullableString(ps, 5, sourceText);
            ps.setString(6, resolution);
            setNullableString(ps, 7, normalizedValue);
            setNullableString(ps, 8, ruleId);
            ps.executeUpdate();
        }
    }

    private static void bindObject(PreparedStatement ps, int index, Object value)
            throws SQLException {
        if (value instanceof Long l) {
            ps.setLong(index, l);
        } else if (value instanceof String s) {
            ps.setString(index, s);
        } else {
            ps.setObject(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }
}
