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
 * Persistence and retrieval for the M3-10 spine checklist (issue #143): the
 * instantiated {@code checklist_item}s, their per-scope {@code checklist_result}s,
 * the analyst's {@code checklist_override}s and the persisted {@code checklist_summary}
 * counts. The repository never commits or rolls back — the caller owns the
 * transaction, the same discipline as {@link AssessmentRepository}.
 */
public final class ChecklistRepository {

    /** Provenance entity type of a checklist result's cited evidence (M3-10). */
    public static final String PROVENANCE_ENTITY_TYPE = "checklist_result";

    /** review_queue category for a Not provided checklist item. */
    public static final String FLAG_NOT_PROVIDED = "checklist_not_provided";

    /** review_queue category for an Unclear checklist item. */
    public static final String FLAG_UNCLEAR = "checklist_unclear";

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public ChecklistRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /** An append-only learning decision or run summary, with its JSON provenance. */
    public record LearningEvent(long parseRunId, String eventType, String payload) {}

    /** Reads the mandate's learning history, including decisions from earlier versions. */
    public List<LearningEvent> learningHistory(long mandateId) throws SQLException {
        List<LearningEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT a.parse_run_id, a.event_type, a.payload FROM audit_log a"
                        + " JOIN parse_run r ON r.parse_run_id = a.parse_run_id"
                        + " WHERE r.mandate_id = ? AND a.event_type IN"
                        + " ('checklist_learned', 'checklist_learning_summary') ORDER BY a.audit_log_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new LearningEvent(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return List.copyOf(events);
    }

    /**
     * A document version's classification signals the checklist engine matches on.
     *
     * @param documentVersionId version id
     * @param fileName stored file name
     * @param docTypeCode classified pack code, or null when unclassified
     * @param confidence classification confidence, or null
     * @param industryTag industry tag recorded at classification, or null
     * @param memberPageIndex lowest file page recorded as belonging to this document,
     *     or null when the file was not stored as that document
     */
    public record DocScopeRow(
            long documentVersionId,
            String fileName,
            String docTypeCode,
            Double confidence,
            String industryTag,
            Integer memberPageIndex) {}

    /**
     * One fact with its best evidence line, for checklist matching.
     *
     * @param factId fact id
     * @param documentVersionId version the fact came from
     * @param fieldKey pack field key, or null
     * @param ontologyPath bound ontology path, or null
     * @param entityId entity tag, or null
     * @param siteId site tag, or null
     * @param confidence fact confidence, or null
     * @param valueText the stated value text, or null
     * @param evidenceVerbatim best evidence quote, or null when unanchored
     * @param pageIndex best evidence page, or null
     * @param resolution best evidence resolution ({@code resolved}/{@code missing}/
     *     {@code ambiguous}), or null with no evidence
     * @param evidenceProvenanceId the best evidence's provenance row, or null
     */
    public record FactEvidenceRow(
            long factId,
            long documentVersionId,
            String fieldKey,
            String ontologyPath,
            Long entityId,
            Long siteId,
            Double confidence,
            String valueText,
            String evidenceVerbatim,
            Integer pageIndex,
            String resolution,
            Long evidenceProvenanceId,
            boolean unstableAcrossRuns) {

        public FactEvidenceRow(
                long factId,
                long documentVersionId,
                String fieldKey,
                String ontologyPath,
                Long entityId,
                Long siteId,
                Double confidence,
                String valueText,
                String evidenceVerbatim,
                Integer pageIndex,
                String resolution,
                Long evidenceProvenanceId) {
            this(factId, documentVersionId, fieldKey, ontologyPath, entityId, siteId, confidence,
                    valueText, evidenceVerbatim, pageIndex, resolution, evidenceProvenanceId, false);
        }
    }

    /**
     * Instantiated checklist items for re-run replacement and the checklist CLI.
     *
     * @param itemKey stable item key
     * @param label human label
     * @param level {@code document} | {@code data_point}
     * @param granularity {@code mandate} | {@code entity} | {@code site}
     * @param entityRole entity role the item is expected for, or null
     * @param documentType pack code of the expected document
     * @param fieldKey data-point field key, or null
     * @param ontologyPath data-point ontology path, or null
     * @param mandatory whether the item is mandatory
     * @param basisKind {@code regulation} | {@code practice} | {@code observed}
     * @param basisText the named regulation or practice
     * @param origin {@code drafted} | {@code observed} | {@code promoted}
     * @param clientRequest ask-the-client template
     * @param checklistCode definition code the item was assessed from
     * @param checklistVersion definition version the item was assessed from
     * @param scope {@code spine} | {@code industry}
     * @param country country the definition applies to
     * @param industry industry for an industry checklist, or null
     * @param note the item's own note when it carries one, else the definition's
     *     label when it carries one, or null
     */
    public record ItemRow(
            String itemKey,
            String label,
            String level,
            String granularity,
            String entityRole,
            String documentType,
            String fieldKey,
            String ontologyPath,
            boolean mandatory,
            String basisKind,
            String basisText,
            String origin,
            String clientRequest,
            String checklistCode,
            String checklistVersion,
            String scope,
            String country,
            String industry,
            String note) {}

    /**
     * Drops the mandate's previous checklist wholesale — result provenance first
     * (the FKs are enforced immediately by SQLite), then results, items and
     * summaries — so a re-run replaces and never duplicates.
     *
     * @throws SQLException if any delete fails
     */
    public void clearChecklist(long mandateId) throws SQLException {
        // Detach the references first: SQLite enforces FKs immediately, so no
        // provenance row can be deleted while a result still points at it.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE checklist_result SET provenance_id = NULL WHERE checklist_item_id IN"
                        + " (SELECT checklist_item_id FROM checklist_item WHERE mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = ? AND entity_id IN"
                        + " (SELECT r.checklist_result_id FROM checklist_result r"
                        + " JOIN checklist_item i"
                        + "  ON i.checklist_item_id = r.checklist_item_id"
                        + " WHERE i.mandate_id = ?)")) {
            ps.setString(1, PROVENANCE_ENTITY_TYPE);
            ps.setLong(2, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM checklist_result WHERE checklist_item_id IN"
                        + " (SELECT checklist_item_id FROM checklist_item WHERE mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM checklist_item WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM checklist_summary WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
    }

    /**
     * Removes the previous run's checklist flags for the stage's parse run, so a
     * re-run replaces them like {@code extract_empty} does.
     *
     * @throws SQLException if the delete fails
     */
    public void clearFlags(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM review_queue WHERE parse_run_id = ? AND category IN (?, ?)")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, FLAG_NOT_PROVIDED);
            ps.setString(3, FLAG_UNCLEAR);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one instantiated checklist item.
     *
     * @return the {@code checklist_item_id}
     * @throws SQLException if the insert fails
     */
    public long insertItem(long mandateId, String itemKey, String label, String checklistCode,
            String checklistVersion, String scope, String country, String industry, String level,
            String granularity, String entityRole, String documentType, String fieldKey,
            String ontologyPath, boolean mandatory, String basisKind, String basisText,
            String origin, String clientRequest, String note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO checklist_item (mandate_id, item_key, label, checklist_code,"
                        + " checklist_version, scope, country, industry, level, granularity,"
                        + " entity_role, document_type, field_key, ontology_path, mandatory,"
                        + " basis_kind, basis_text, origin, client_request, note, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, itemKey);
            ps.setString(3, label);
            ps.setString(4, checklistCode);
            ps.setString(5, checklistVersion);
            ps.setString(6, scope);
            ps.setString(7, country);
            setString(ps, 8, industry);
            ps.setString(9, level);
            ps.setString(10, granularity);
            setString(ps, 11, entityRole);
            ps.setString(12, documentType);
            setString(ps, 13, fieldKey);
            setString(ps, 14, ontologyPath);
            ps.setInt(15, mandatory ? 1 : 0);
            ps.setString(16, basisKind);
            ps.setString(17, basisText);
            ps.setString(18, origin);
            ps.setString(19, clientRequest);
            setString(ps, 20, note);
            ps.setString(21, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Inserts one checklist result.
     *
     * @return the {@code checklist_result_id}
     * @throws SQLException if the insert fails
     */
    public long insertResult(long itemId, Long assessmentRunId, Long entityId, Long siteId,
            String status, String reason, Long documentVersionId, Integer pageIndex,
            Double confidence, String clientRequest) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO checklist_result (checklist_item_id, assessment_run_id, entity_id,"
                        + " site_id, status, reason, document_version_id, page_index, confidence,"
                        + " client_request, checked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, itemId);
            setLong(ps, 2, assessmentRunId);
            setLong(ps, 3, entityId);
            setLong(ps, 4, siteId);
            ps.setString(5, status);
            ps.setString(6, reason);
            setLong(ps, 7, documentVersionId);
            if (pageIndex == null) {
                ps.setNull(8, Types.INTEGER);
            } else {
                ps.setInt(8, pageIndex);
            }
            if (confidence == null) {
                ps.setNull(9, Types.REAL);
            } else {
                ps.setDouble(9, confidence);
            }
            setString(ps, 10, clientRequest);
            ps.setString(11, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Attaches the cited-evidence provenance row to a result.
     *
     * @throws SQLException if the update fails
     */
    public void attachProvenance(long resultId, long provenanceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE checklist_result SET provenance_id = ? WHERE checklist_result_id = ?")) {
            ps.setLong(1, provenanceId);
            ps.setLong(2, resultId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one persisted summary count.
     *
     * @throws SQLException if the insert fails
     */
    public void insertSummary(long mandateId, Long assessmentRunId, String scopeKind,
            Long entityId, Long siteId, String status, int itemCount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO checklist_summary (mandate_id, assessment_run_id, scope_kind,"
                        + " entity_id, site_id, status, item_count, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, mandateId);
            setLong(ps, 2, assessmentRunId);
            ps.setString(3, scopeKind);
            setLong(ps, 4, entityId);
            setLong(ps, 5, siteId);
            ps.setString(6, status);
            ps.setInt(7, itemCount);
            ps.setString(8, Timestamps.now());
            ps.executeUpdate();
        }
    }

    /**
     * Records one analyst override.
     *
     * @return the {@code checklist_override_id}
     * @throws SQLException if the insert fails; a blank reason is rejected by the table
     */
    public long insertOverride(long mandateId, String itemKey, String action, String reason)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO checklist_override (mandate_id, item_key, action, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, itemKey);
            ps.setString(3, action);
            ps.setString(4, reason);
            ps.setString(5, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * The mandate's overrides in recording order.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ChecklistOverrideRow> overrides(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT checklist_override_id, item_key, action, reason, created_at"
                        + " FROM checklist_override WHERE mandate_id = ?"
                        + " ORDER BY checklist_override_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ChecklistOverrideRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new MandateQa.ChecklistOverrideRow(
                            rs.getLong("checklist_override_id"),
                            rs.getString("item_key"),
                            rs.getString("action"),
                            rs.getString("reason"),
                            rs.getString("created_at")));
                }
                return rows;
            }
        }
    }

    /**
     * The item instantiated for the mandate's last assessment, or null.
     *
     * @throws SQLException if the query fails
     */
    public ItemRow item(long mandateId, String itemKey) throws SQLException {
        for (ItemRow row : items(mandateId)) {
            if (row.itemKey().equals(itemKey)) {
                return row;
            }
        }
        return null;
    }

    /**
     * The items the mandate was last assessed against, in instantiation order.
     *
     * @throws SQLException if the query fails
     */
    public List<ItemRow> items(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT item_key, label, level, granularity, entity_role, document_type,"
                        + " field_key, ontology_path, mandatory, basis_kind, basis_text, origin,"
                        + " client_request, checklist_code, checklist_version, scope, country,"
                        + " industry, note FROM checklist_item WHERE mandate_id = ?"
                        + " ORDER BY checklist_item_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ItemRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ItemRow(
                            rs.getString("item_key"),
                            rs.getString("label"),
                            rs.getString("level"),
                            rs.getString("granularity"),
                            rs.getString("entity_role"),
                            rs.getString("document_type"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            rs.getInt("mandatory") == 1,
                            rs.getString("basis_kind"),
                            rs.getString("basis_text"),
                            rs.getString("origin"),
                            rs.getString("client_request"),
                            rs.getString("checklist_code"),
                            rs.getString("checklist_version"),
                            rs.getString("scope"),
                            rs.getString("country"),
                            rs.getString("industry"),
                            rs.getString("note")));
                }
                return rows;
            }
        }
    }

    /**
     * The mandate's classification signals per document version.
     *
     * @throws SQLException if the query fails
     */
    public List<DocScopeRow> documentScopes(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dv.document_version_id, dv.file_name, dc.doc_type_code, dc.confidence,"
                        + " dc.industry_tag,"
                        + " (SELECT MIN(p.page_index) FROM document_page dp"
                        + "   JOIN page p ON p.page_id = dp.page_id"
                        + "   WHERE dp.document_id = d.document_id) AS member_page"
                        + " FROM document_version dv"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN doc_classification dc"
                        + "  ON dc.document_version_id = dv.document_version_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dv.document_version_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DocScopeRow> rows = new ArrayList<>();
                while (rs.next()) {
                    int memberPage = rs.getInt("member_page");
                    Integer member = rs.wasNull() ? null : memberPage;
                    rows.add(new DocScopeRow(
                            rs.getLong("document_version_id"),
                            rs.getString("file_name"),
                            rs.getString("doc_type_code"),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("industry_tag"),
                            member));
                }
                return rows;
            }
        }
    }

    /**
     * Every mandate fact with its best evidence line (resolved and paged evidence
     * preferred), for checklist matching.
     *
     * @throws SQLException if the query fails
     */
    public List<FactEvidenceRow> facts(long mandateId) throws SQLException {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, FactEvidenceRow> best = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id,"
                        + " CASE WHEN f.document_id IS NOT NULL AND f.document_id <> dv.document_id"
                        + " THEN COALESCE((SELECT owner.current_version_id FROM document owner"
                        + "    WHERE owner.document_id = f.document_id), f.document_version_id)"
                        + " ELSE f.document_version_id END AS document_version_id,"
                        + " f.field_key, f.ontology_path,"
                        + " f.entity_id, f.site_id, f.confidence, f.value_text, f.unstable_across_runs,"
                        + " e.ordinal, e.source_text, e.resolution, e.provenance_id, p.page_index"
                        + " FROM fact f"
                        + " JOIN document_version dv ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = COALESCE(f.document_id, dv.document_id)"
                        + " LEFT JOIN fact_evidence e ON e.fact_id = f.fact_id"
                        + " LEFT JOIN provenance_doc pd ON pd.provenance_id = e.provenance_id"
                        + " LEFT JOIN page p ON p.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY f.fact_id, e.ordinal")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long factId = rs.getLong("fact_id");
                    int pageIndex = rs.getInt("page_index");
                    Integer page = rs.wasNull() ? null : pageIndex;
                    String resolution = rs.getString("resolution");
                    long provenance = rs.getLong("provenance_id");
                    Long provenanceId = rs.wasNull() ? null : provenance;
                    double score = ("resolved".equals(resolution) ? 2 : 0) + (page != null ? 1 : 0);
                    FactEvidenceRow row = new FactEvidenceRow(
                            factId,
                            rs.getLong("document_version_id"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            asLong(rs.getObject("entity_id")),
                            asLong(rs.getObject("site_id")),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("value_text"),
                            rs.getString("source_text"),
                            page,
                            resolution,
                            provenanceId,
                            rs.getInt("unstable_across_runs") == 1);
                    Double current = scores.get(factId);
                    if (current == null || score > current) {
                        scores.put(factId, score);
                        best.put(factId, row);
                    }
                }
            }
        }
        return List.copyOf(best.values());
    }

    /**
     * The first page index of a document version, or null when it has no pages.
     *
     * @throws SQLException if the query fails
     */
    public Integer firstPageIndex(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MIN(page_index) FROM page WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int min = rs.getInt(1);
                    return rs.wasNull() ? null : min;
                }
            }
        }
        return null;
    }

    /**
     * The page row id of one version's page, or null when absent.
     *
     * @throws SQLException if the query fails
     */
    public Long pageId(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * Every checklist result of the mandate's last assessment — section of
     * {@link MandateQa}; read joins the item, the entity/site scope and the
     * evidence document in one pass.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ChecklistResultRow> results(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.checklist_result_id, i.item_key, i.checklist_version, i.label,"
                        + " i.level, i.granularity,"
                        + " i.mandatory, i.basis_kind, i.basis_text, i.origin, i.document_type,"
                        + " i.field_key, i.ontology_path, r.entity_id, e.name AS entity_name,"
                        + " r.site_id, s.site_name, r.status, r.reason, r.document_version_id,"
                        + " d.logical_name, dv.file_name, r.page_index, r.confidence,"
                        + " r.client_request, i.note"
                        + " FROM checklist_result r"
                        + " JOIN checklist_item i ON i.checklist_item_id = r.checklist_item_id"
                        + " LEFT JOIN entity e ON e.entity_id = r.entity_id"
                        + " LEFT JOIN site s ON s.site_id = r.site_id"
                        + " LEFT JOIN document_version dv"
                        + "  ON dv.document_version_id = r.document_version_id"
                        + " LEFT JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE i.mandate_id = ?"
                        + " ORDER BY i.checklist_item_id, r.entity_id, r.site_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ChecklistResultRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new MandateQa.ChecklistResultRow(
                            rs.getLong("checklist_result_id"),
                            rs.getString("item_key"),
                            rs.getString("checklist_version"),
                            rs.getString("label"),
                            rs.getString("level"),
                            rs.getString("granularity"),
                            rs.getInt("mandatory") == 1,
                            rs.getString("basis_kind"),
                            rs.getString("basis_text"),
                            rs.getString("origin"),
                            rs.getString("document_type"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            asLong(rs.getObject("entity_id")),
                            rs.getString("entity_name"),
                            asLong(rs.getObject("site_id")),
                            rs.getString("site_name"),
                            rs.getString("status"),
                            rs.getString("reason"),
                            asLong(rs.getObject("document_version_id")),
                            rs.getString("logical_name"),
                            rs.getString("file_name"),
                            asInt(rs.getObject("page_index")),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("client_request"),
                            rs.getString("note")));
                }
                return rows;
            }
        }
    }

    /**
     * The persisted summary counts of the mandate's last assessment — section of
     * {@link MandateQa}.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.ChecklistSummaryRow> summaries(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cs.scope_kind, cs.entity_id, e.name AS entity_name, cs.site_id,"
                        + " s.site_name, cs.status, cs.item_count"
                        + " FROM checklist_summary cs"
                        + " LEFT JOIN entity e ON e.entity_id = cs.entity_id"
                        + " LEFT JOIN site s ON s.site_id = cs.site_id"
                        + " WHERE cs.mandate_id = ?"
                        + " ORDER BY cs.checklist_summary_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.ChecklistSummaryRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new MandateQa.ChecklistSummaryRow(
                            rs.getString("scope_kind"),
                            asLong(rs.getObject("entity_id")),
                            rs.getString("entity_name"),
                            asLong(rs.getObject("site_id")),
                            rs.getString("site_name"),
                            rs.getString("status"),
                            rs.getInt("item_count")));
                }
                return rows;
            }
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
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
