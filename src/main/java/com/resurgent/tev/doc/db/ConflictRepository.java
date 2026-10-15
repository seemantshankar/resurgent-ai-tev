package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.parser.db.Timestamps;
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
 * Persistence and retrieval for the M3-12/M3-13 conflicts (issues #145, #146,
 * ADR 0026): one {@code conflict} row per disagreement over the same data point —
 * between two documents ({@code doc-doc}) or between two sections of one document
 * ({@code intra-doc}, both sides' fact and passage kept) — carrying both sides
 * (source, page, value, quote, fact id) and its settlement — the losing value is
 * never overwritten. {@code versions-differ} rows are revisions (same document type,
 * different document dates) whose winner M4 chooses. The repository never commits or
 * rolls back — the caller owns the transaction, the same discipline as
 * {@link AssessmentRepository}.
 */
public final class ConflictRepository {

    /** A disagreement between two different documents (possibly different types). */
    public static final String KIND_DOC_DOC = "doc-doc";

    /** A document statement versus a read-only Layer B financial model cell. */
    public static final String KIND_DOC_FM = "doc-fm";

    /** A disagreement between two sections (passages) of one document (M3-13). */
    public static final String KIND_INTRA_DOC = "intra-doc";

    /** A revision: the same document type on two different document dates (M4 picks). */
    public static final String KIND_VERSIONS_DIFFER = "versions-differ";

    /** Settled by the versioned source-precedence rule set. */
    public static final String SETTLEMENT_RULE = "rule";

    /** Settled by AI judgement with written reasoning; no action required. */
    public static final String SETTLEMENT_AI = "ai";

    /** Still open: the checklist item shows Conflicting and the row is flagged. */
    public static final String SETTLEMENT_OPEN = "open";

    /** review_queue category for a conflict left open. */
    public static final String FLAG_CONFLICTING = "conflicting";

    /** review_queue category for a conflict settled by AI (no action required). */
    public static final String FLAG_SETTLED_BY_AI = "settled_by_ai";

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public ConflictRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * One fact with the document metadata, scope tags and best evidence line the
     * conflict scan compares on.
     *
     * @param factId fact id
     * @param documentVersionId version the fact came from
     * @param documentId logical document id
     * @param logicalName document logical name
     * @param fileName stored file name
     * @param docTypeCode classified pack code, or null
     * @param documentDate canonical document date, or null
     * @param fieldKey pack field key, or null
     * @param ontologyPath bound ontology path, or null
     * @param lineKey row/line identifier for row facts, or null
     * @param valueKind value kind wire name
     * @param valueText the stated value text, or null
     * @param valueNumber numeric value, or null
     * @param valueDate date value, or null
     * @param unit unit, or null
     * @param currency currency code, or null
     * @param entityId entity tag, or null
     * @param entityName that entity's name, or null
     * @param siteId site tag, or null
     * @param siteName that site's name, or null
     * @param periodEvidence the stated period from a {@code role='period'} evidence
     *     row, or null
     * @param pageIndex best evidence page, or null
     * @param quote best evidence quote, or null
     * @param passageId the chapter passage the fact was read from, or null for
     *     document-level facts
     * @param passageHeading that passage's heading, or null
     */
    public record ConflictFactRow(
            long factId,
            long documentVersionId,
            long documentId,
            String logicalName,
            String fileName,
            String docTypeCode,
            String documentDate,
            String fieldKey,
            String ontologyPath,
            String lineKey,
            String valueKind,
            String valueText,
            Double valueNumber,
            String valueDate,
            String unit,
            String currency,
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String periodEvidence,
            Integer pageIndex,
            String quote,
            Long passageId,
            String passageHeading) {}

    /**
     * One conflict row to persist. Values are never overwritten: both sides stay on
     * the row whatever the settlement.
     *
     * @param kind {@code doc-doc} | {@code versions-differ} (others reserved)
     * @param dataPoint data point disagreed on (pack field key, else ontology path)
     * @param fieldKey pack field key, or null
     * @param ontologyPath bound ontology path, or null
     * @param entityId entity the comparison was made within, or null
     * @param siteId site the comparison was made within, or null
     * @param period stated period, or null
     * @param verdict comparison verdict wire name
     * @param leftFactId left fact id, or null
     * @param leftDocumentVersionId left version, or null
     * @param leftPageIndex left cited page, or null
     * @param leftValue left value as stated
     * @param leftQuote left verbatim, or null
     * @param rightFactId right fact id, or null
     * @param rightDocumentVersionId right version, or null
     * @param rightPageIndex right cited page, or null
     * @param rightValue right value as stated
     * @param rightQuote right verbatim, or null
     * @param settlement {@code rule} | {@code ai} | {@code open}, or null for
     *     {@code versions-differ}
     * @param chosenSide {@code left} | {@code right}, or null when open/versions-differ
     * @param ruleId precedence rule id, or null
     * @param rulesVersion precedence rule set version, or null
     * @param reasoning why it was settled this way (never blank)
     */
    public record ConflictWrite(
            String kind,
            String dataPoint,
            String fieldKey,
            String ontologyPath,
            Long entityId,
            Long siteId,
            String period,
            String verdict,
            Long leftFactId,
            Long leftDocumentVersionId,
            Integer leftPageIndex,
            String leftValue,
            String leftQuote,
            Long rightFactId,
            Long rightDocumentVersionId,
            Integer rightPageIndex,
            String rightValue,
            String rightQuote,
            String settlement,
            String chosenSide,
            String ruleId,
            String rulesVersion,
            String reasoning) {

        /** The value used (the chosen side's stated value), or null when open. */
        public String chosenValue() {
            if (SETTLEMENT_RULE.equals(settlement) || SETTLEMENT_AI.equals(settlement)) {
                return "left".equals(chosenSide) ? leftValue : rightValue;
            }
            return null;
        }

        /** The value rejected, still kept on the row and in the fact table, or null. */
        public String rejectedValue() {
            if (SETTLEMENT_RULE.equals(settlement) || SETTLEMENT_AI.equals(settlement)) {
                return "left".equals(chosenSide) ? rightValue : leftValue;
            }
            return null;
        }
    }

    /**
     * Every mandate fact with its document metadata, scope tags, stated period
     * evidence and best evidence line (resolved and paged evidence preferred), plus
     * the chapter passage it was read from when it is passage-linked.
     *
     * @throws SQLException if the query fails
     */
    public List<ConflictFactRow> factsForMandate(long mandateId) throws SQLException {
        Map<Long, FactAccumulator> accumulated = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id, f.document_version_id, dv.document_id, d.logical_name,"
                        + " dv.file_name, dc.doc_type_code, dv.document_date, f.field_key,"
                        + " f.ontology_path, f.line_key, f.value_kind, f.value_text,"
                        + " f.value_number, f.value_date, f.unit, f.currency, f.entity_id,"
                        + " e.name AS entity_name, f.site_id, s.site_name,"
                        + " ev.role AS evidence_role, ev.source_text, ev.resolution,"
                        + " p.page_index, f.passage_id, psg.heading AS passage_heading"
                        + " FROM fact f"
                        + " JOIN document_version dv ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN doc_classification dc"
                        + "  ON dc.document_version_id = f.document_version_id"
                        + " LEFT JOIN entity e ON e.entity_id = f.entity_id"
                        + " LEFT JOIN site s ON s.site_id = f.site_id"
                        + " LEFT JOIN passage psg ON psg.passage_id = f.passage_id"
                        + " LEFT JOIN fact_evidence ev ON ev.fact_id = f.fact_id"
                        + " LEFT JOIN provenance_doc pd ON pd.provenance_id = ev.provenance_id"
                        + " LEFT JOIN page p ON p.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY f.fact_id, ev.ordinal")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long factId = rs.getLong("fact_id");
                    FactAccumulator acc = accumulated.get(factId);
                    if (acc == null) {
                        acc = new FactAccumulator(factId, rs);
                        accumulated.put(factId, acc);
                    }
                    String role = rs.getString("evidence_role");
                    if ("period".equals(role) && acc.periodEvidence == null) {
                        acc.periodEvidence = rs.getString("source_text");
                        continue;
                    }
                    if ("period".equals(role)) {
                        continue;
                    }
                    int pageIndex = rs.getInt("page_index");
                    Integer page = rs.wasNull() ? null : pageIndex;
                    String resolution = rs.getString("resolution");
                    double score = ("resolved".equals(resolution) ? 2 : 0) + (page != null ? 1 : 0);
                    if (score > acc.bestScore) {
                        acc.bestScore = score;
                        acc.pageIndex = page;
                        acc.quote = rs.getString("source_text");
                    }
                }
            }
        }
        List<ConflictFactRow> rows = new ArrayList<>();
        for (FactAccumulator acc : accumulated.values()) {
            rows.add(acc.toRow());
        }
        return rows;
    }

    /** Mutable per-fact accumulation while the evidence join is read. */
    private static final class FactAccumulator {

        final long factId;
        final long documentVersionId;
        final long documentId;
        final String logicalName;
        final String fileName;
        final String docTypeCode;
        final String documentDate;
        final String fieldKey;
        final String ontologyPath;
        final String lineKey;
        final String valueKind;
        final String valueText;
        final Double valueNumber;
        final String valueDate;
        final String unit;
        final String currency;
        final Long entityId;
        final String entityName;
        final Long siteId;
        final String siteName;
        final Long passageId;
        final String passageHeading;
        String periodEvidence;
        Integer pageIndex;
        String quote;
        double bestScore = -1;

        FactAccumulator(long factId, ResultSet rs) throws SQLException {
            this.factId = factId;
            this.documentVersionId = rs.getLong("document_version_id");
            this.documentId = rs.getLong("document_id");
            this.logicalName = rs.getString("logical_name");
            this.fileName = rs.getString("file_name");
            this.docTypeCode = rs.getString("doc_type_code");
            this.documentDate = rs.getString("document_date");
            this.fieldKey = rs.getString("field_key");
            this.ontologyPath = rs.getString("ontology_path");
            this.lineKey = rs.getString("line_key");
            this.valueKind = rs.getString("value_kind");
            this.valueText = rs.getString("value_text");
            this.valueNumber = asDouble(rs.getObject("value_number"));
            this.valueDate = rs.getString("value_date");
            this.unit = rs.getString("unit");
            this.currency = rs.getString("currency");
            this.entityId = asLong(rs.getObject("entity_id"));
            this.entityName = rs.getString("entity_name");
            this.siteId = asLong(rs.getObject("site_id"));
            this.siteName = rs.getString("site_name");
            this.passageId = asLong(rs.getObject("passage_id"));
            this.passageHeading = rs.getString("passage_heading");
        }

        ConflictFactRow toRow() {
            return new ConflictFactRow(factId, documentVersionId, documentId, logicalName,
                    fileName, docTypeCode, documentDate, fieldKey, ontologyPath, lineKey,
                    valueKind, valueText, valueNumber, valueDate, unit, currency,
                    entityId, entityName, siteId, siteName, periodEvidence, pageIndex,
                    quote, passageId, passageHeading);
        }
    }

    /**
     * Drops the mandate's previous conflict rows, so a re-run replaces and never
     * duplicates. Facts are untouched — nothing is ever overwritten.
     *
     * @throws SQLException if the delete fails
     */
    public void clearConflicts(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM conflict_skip WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM conflict WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one conflict row.
     *
     * @return the {@code conflict_id}
     * @throws SQLException if the insert fails or yields no id
     */
    public long insertConflict(long mandateId, Long assessmentRunId, ConflictWrite write)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO conflict (mandate_id, assessment_run_id, kind, data_point,"
                        + " field_key, ontology_path, entity_id, site_id, period, verdict,"
                        + " left_fact_id, left_document_version_id, left_page_index,"
                        + " left_value, left_quote, right_fact_id, right_document_version_id,"
                        + " right_page_index, right_value, right_quote, settlement,"
                        + " chosen_side, rule_id, rules_version, reasoning, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            setLong(ps, 2, assessmentRunId);
            ps.setString(3, write.kind());
            ps.setString(4, write.dataPoint());
            setString(ps, 5, write.fieldKey());
            setString(ps, 6, write.ontologyPath());
            setLong(ps, 7, write.entityId());
            setLong(ps, 8, write.siteId());
            setString(ps, 9, write.period());
            ps.setString(10, write.verdict());
            setLong(ps, 11, write.leftFactId());
            setLong(ps, 12, write.leftDocumentVersionId());
            setInt(ps, 13, write.leftPageIndex());
            ps.setString(14, write.leftValue());
            setString(ps, 15, write.leftQuote());
            setLong(ps, 16, write.rightFactId());
            setLong(ps, 17, write.rightDocumentVersionId());
            setInt(ps, 18, write.rightPageIndex());
            ps.setString(19, write.rightValue());
            setString(ps, 20, write.rightQuote());
            setString(ps, 21, write.settlement());
            setString(ps, 22, write.chosenSide());
            setString(ps, 23, write.ruleId());
            setString(ps, 24, write.rulesVersion());
            ps.setString(25, write.reasoning());
            ps.setString(26, Timestamps.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("conflict insert yielded no id for data point "
                + write.dataPoint());
    }

    /**
     * The mandate's conflicts — section of {@link MandateQa}, oldest first.
     * {@code versions-differ} rows are not conflicts and are returned by
     * {@link #versionsDiffer} instead.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ConflictRow> conflicts(long mandateId) throws SQLException {
        return read(mandateId, false);
    }

    /** One persisted analyst flag: AI settlements are Resolved; open conflicts are Pending. */
    public record ConflictFlagRow(String category, String status, String summary, String detail) {}

    /**
     * The mandate's latest conflict-assessment flags, including AI settlements that
     * need no analyst action. Reads the stored queue rather than inferring flags from
     * settlements, so callers can verify that a pick was visibly recorded.
     *
     * @throws SQLException if the query fails
     */
    public List<ConflictFlagRow> flags(long mandateId) throws SQLException {
        List<ConflictFlagRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT rq.category, rq.status, rq.summary, rq.detail FROM review_queue rq"
                        + " JOIN assessment_run ar ON ar.parse_run_id = rq.parse_run_id"
                        + " WHERE ar.mandate_id = ? AND ar.stage = 'assess_conflicts'"
                        + " AND rq.category IN (?, ?) ORDER BY rq.review_queue_id")) {
            ps.setLong(1, mandateId);
            ps.setString(2, FLAG_CONFLICTING);
            ps.setString(3, FLAG_SETTLED_BY_AI);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ConflictFlagRow(rs.getString("category"), rs.getString("status"),
                            rs.getString("summary"), rs.getString("detail")));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The mandate's revisions — section of {@link MandateQa}, newest first (the left
     * side of each row is the newest document date; M4 chooses winners).
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ConflictRow> versionsDiffer(long mandateId) throws SQLException {
        return read(mandateId, true);
    }

    private List<MandateQa.ConflictRow> read(long mandateId, boolean versionsDiffer)
            throws SQLException {
        String filter = versionsDiffer
                ? " AND c.kind = '" + KIND_VERSIONS_DIFFER + "'"
                : " AND c.kind != '" + KIND_VERSIONS_DIFFER + "'";
        String order = versionsDiffer
                ? " ORDER BY (SELECT dv2.document_date FROM document_version dv2"
                        + " WHERE dv2.document_version_id = c.left_document_version_id)"
                        + " DESC, c.conflict_id"
                : " ORDER BY c.conflict_id";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.conflict_id, c.kind, c.data_point, c.field_key, c.ontology_path,"
                        + " c.entity_id, e.name AS entity_name, c.site_id, s.site_name,"
                        + " c.period, c.verdict, c.left_fact_id, c.left_document_version_id,"
                        + " ld.logical_name AS left_logical_name,"
                        + " ldv.file_name AS left_file_name, c.left_page_index,"
                        + " c.left_value, c.left_quote, lpsg.heading AS left_passage_heading,"
                        + " c.right_fact_id,"
                        + " c.right_document_version_id, rd.logical_name AS right_logical_name,"
                        + " rdv.file_name AS right_file_name, c.right_page_index,"
                        + " c.right_value, c.right_quote, rpsg.heading AS right_passage_heading,"
                        + " c.settlement, c.chosen_side,"
                        + " c.rule_id, c.rules_version, c.reasoning"
                        + " FROM conflict c"
                        + " LEFT JOIN document_version ldv"
                        + "  ON ldv.document_version_id = c.left_document_version_id"
                        + " LEFT JOIN document ld ON ld.document_id = ldv.document_id"
                        + " LEFT JOIN fact lf ON lf.fact_id = c.left_fact_id"
                        + " LEFT JOIN passage lpsg ON lpsg.passage_id = lf.passage_id"
                        + " LEFT JOIN document_version rdv"
                        + "  ON rdv.document_version_id = c.right_document_version_id"
                        + " LEFT JOIN document rd ON rd.document_id = rdv.document_id"
                        + " LEFT JOIN fact rf ON rf.fact_id = c.right_fact_id"
                        + " LEFT JOIN passage rpsg ON rpsg.passage_id = rf.passage_id"
                        + " LEFT JOIN entity e ON e.entity_id = c.entity_id"
                        + " LEFT JOIN site s ON s.site_id = c.site_id"
                        + " WHERE c.mandate_id = ?" + filter + order)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ConflictRow> rows = new ArrayList<>();
                while (rs.next()) {
                    String settlement = rs.getString("settlement");
                    String chosenSide = rs.getString("chosen_side");
                    String leftValue = rs.getString("left_value");
                    String rightValue = rs.getString("right_value");
                    boolean chosen = SETTLEMENT_RULE.equals(settlement)
                            || SETTLEMENT_AI.equals(settlement);
                    rows.add(new MandateQa.ConflictRow(
                            rs.getLong("conflict_id"),
                            rs.getString("kind"),
                            rs.getString("data_point"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            asLong(rs.getObject("entity_id")),
                            rs.getString("entity_name"),
                            asLong(rs.getObject("site_id")),
                            rs.getString("site_name"),
                            rs.getString("period"),
                            rs.getString("verdict"),
                            new MandateQa.ConflictSideRow(
                                    asLong(rs.getObject("left_document_version_id")),
                                    rs.getString("left_logical_name"),
                                    rs.getString("left_file_name"),
                                    asInt(rs.getObject("left_page_index")),
                                    leftValue,
                                    rs.getString("left_quote"),
                                    rs.getString("left_passage_heading")),
                            new MandateQa.ConflictSideRow(
                                    asLong(rs.getObject("right_document_version_id")),
                                    rs.getString("right_logical_name"),
                                    rs.getString("right_file_name"),
                                    asInt(rs.getObject("right_page_index")),
                                    rightValue,
                                    rs.getString("right_quote"),
                                    rs.getString("right_passage_heading")),
                            settlement,
                            chosenSide,
                            chosen ? ("left".equals(chosenSide) ? leftValue : rightValue) : null,
                            chosen ? ("left".equals(chosenSide) ? rightValue : leftValue) : null,
                            rs.getString("rule_id"),
                            rs.getString("rules_version"),
                            rs.getString("reasoning")));
                }
                return rows;
            }
        }
    }

    /** Persist a refused pair in the caller's assessment transaction; no review flag. */
    public void insertConflictSkip(long mandateId, Long assessmentRunId, ConflictFactRow fact,
            String fmSource, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO conflict_skip (mandate_id, assessment_run_id, kind, data_point,"
                        + " ontology_path, document_version_id, page_index, fm_source, reason,"
                        + " created_at) VALUES (?, ?, 'doc-fm', ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, mandateId);
            setLong(ps, 2, assessmentRunId);
            ps.setString(3, fact.fieldKey() != null ? fact.fieldKey() : fact.ontologyPath());
            ps.setString(4, fact.ontologyPath());
            ps.setLong(5, fact.documentVersionId());
            setInt(ps, 6, fact.pageIndex());
            ps.setString(7, fmSource);
            ps.setString(8, reason);
            ps.setString(9, Timestamps.now());
            ps.executeUpdate();
        }
    }

    /** The mandate's skipped document/model pairs in stable assessment order. */
    public List<MandateQa.ConflictSkipRow> conflictSkips(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT skip_id, kind, data_point, ontology_path, document_version_id,"
                        + " page_index, fm_source, reason FROM conflict_skip"
                        + " WHERE mandate_id = ? ORDER BY skip_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ConflictSkipRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new MandateQa.ConflictSkipRow(rs.getLong("skip_id"),
                            rs.getString("kind"), rs.getString("data_point"),
                            rs.getString("ontology_path"), rs.getLong("document_version_id"),
                            asInt(rs.getObject("page_index")), rs.getString("fm_source"),
                            rs.getString("reason")));
                }
                return rows;
            }
        }
    }

    private static void setString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
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

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer asInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
