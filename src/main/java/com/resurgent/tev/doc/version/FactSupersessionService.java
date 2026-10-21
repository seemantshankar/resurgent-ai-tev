package com.resurgent.tev.doc.version;

import com.resurgent.tev.doc.version.VersionPrecedenceService.DocumentVersionInfo;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * M4-05: Per-fact supersession engine (ADR 0028 §2–§4).
 * Determines active current facts and marks superseded fact rows with {@code is_current = 0}
 * and {@code superseded_by_fact_id} pointing to the winning newer fact.
 */
public final class FactSupersessionService {

    private FactSupersessionService() {}

    public record FactIdentityKey(
            boolean isBound,
            String ontologyPath,
            String docTypeCode,
            String fieldKey,
            String subjectEntity,
            long siteId,
            String period,
            String lineKey
    ) {
        public static FactIdentityKey of(
                String ontologyPath,
                String docTypeCode,
                String fieldKey,
                String subjectEntity,
                Long siteId,
                String period,
                String lineKey) {
            boolean bound = ontologyPath != null && !ontologyPath.isBlank();
            return new FactIdentityKey(
                    bound,
                    bound ? ontologyPath.trim() : "",
                    docTypeCode == null ? "" : docTypeCode.trim().toLowerCase(Locale.ROOT),
                    fieldKey == null ? "" : fieldKey.trim().toLowerCase(Locale.ROOT),
                    subjectEntity == null ? "" : subjectEntity.trim().toLowerCase(Locale.ROOT),
                    siteId == null ? 0L : siteId,
                    period == null ? "" : period.trim().toLowerCase(Locale.ROOT),
                    lineKey == null ? "" : lineKey.trim().toLowerCase(Locale.ROOT)
            );
        }
    }

    private record RawFactRow(
            long factId,
            long documentVersionId,
            String ontologyPath,
            String docTypeCode,
            String fieldKey,
            String subjectEntity,
            Long siteId,
            String period,
            String lineKey,
            boolean isCurrent,
            Long supersededByFactId
    ) {}

    /**
     * Resolves per-fact supersession for all facts in the mandate.
     */
    public static void resolveSupersession(Path dbPath, long mandateId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            resolveSupersession(db.connection(), mandateId);
        }
    }

    /**
     * Resolves per-fact supersession using an existing database connection.
     */
    public static void resolveSupersession(Connection connection, long mandateId) throws SQLException {
        // First resolve document precedence ladder so version order is up to date
        VersionPrecedenceService.resolvePrecedenceAndRelations(connection, mandateId);

        // Load document versions for precedence comparisons
        Map<Long, DocumentVersionInfo> versionMap = loadVersionMap(connection, mandateId);

        // Load all facts for the mandate
        List<RawFactRow> facts = loadMandateFacts(connection, mandateId);
        if (facts.isEmpty()) {
            return;
        }

        // Group facts by canonical identity tuple (ADR 0028 §4)
        Map<FactIdentityKey, List<RawFactRow>> groups = new LinkedHashMap<>();
        for (RawFactRow f : facts) {
            FactIdentityKey key = FactIdentityKey.of(
                    f.ontologyPath(), f.docTypeCode(), f.fieldKey(),
                    f.subjectEntity(), f.siteId(), f.period(), f.lineKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }

        String updateSql = "UPDATE fact SET is_current = ?, superseded_by_fact_id = ? WHERE fact_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            for (List<RawFactRow> group : groups.values()) {
                if (group.size() == 1) {
                    RawFactRow single = group.get(0);
                    if (!single.isCurrent() || single.supersededByFactId() != null) {
                        ps.setInt(1, 1);
                        ps.setObject(2, null);
                        ps.setLong(3, single.factId());
                        ps.addBatch();
                    }
                    continue;
                }

                // Rank facts from oldest to newest
                group.sort((f1, f2) -> {
                    DocumentVersionInfo v1 = versionMap.get(f1.documentVersionId());
                    DocumentVersionInfo v2 = versionMap.get(f2.documentVersionId());
                    if (v1 != null && v2 != null && f1.documentVersionId() != f2.documentVersionId()) {
                        int vCmp = VersionPrecedenceService.comparePrecedence(v1, v2);
                        if (vCmp != 0) {
                            return vCmp;
                        }
                    }
                    return Long.compare(f1.factId(), f2.factId());
                });

                RawFactRow winner = group.get(group.size() - 1);

                // Winner is active and not superseded
                if (!winner.isCurrent() || winner.supersededByFactId() != null) {
                    ps.setInt(1, 1);
                    ps.setObject(2, null);
                    ps.setLong(3, winner.factId());
                    ps.addBatch();
                }

                // Each older fact is superseded by the next newer fact in the chain
                for (int i = 0; i < group.size() - 1; i++) {
                    RawFactRow older = group.get(i);
                    RawFactRow newer = group.get(i + 1);
                    if (older.isCurrent() || !Objects.equals(older.supersededByFactId(), newer.factId())) {
                        ps.setInt(1, 0);
                        ps.setLong(2, newer.factId());
                        ps.setLong(3, older.factId());
                        ps.addBatch();
                    }
                }
            }
            ps.executeBatch();
        }
    }

    private static Map<Long, DocumentVersionInfo> loadVersionMap(Connection connection, long mandateId)
            throws SQLException {
        String sql = "SELECT d.document_id, d.logical_name, d.doc_family, d.doc_type_code,"
                + " v.document_version_id, v.version_label, v.file_name, v.file_hash,"
                + " v.ingested_at, v.raw_metadata, v.document_date, v.date_precision, v.date_source"
                + " FROM document d"
                + " JOIN document_version v ON d.document_id = v.document_id"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'";

        Map<Long, DocumentVersionInfo> map = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long vId = rs.getLong("document_version_id");
                    map.put(vId, new DocumentVersionInfo(
                            rs.getLong("document_id"),
                            rs.getString("logical_name"),
                            rs.getString("doc_family"),
                            rs.getString("doc_type_code"),
                            vId,
                            rs.getString("version_label"),
                            rs.getString("file_name"),
                            rs.getString("file_hash"),
                            rs.getString("ingested_at"),
                            rs.getString("raw_metadata"),
                            rs.getString("document_date"),
                            rs.getString("date_precision"),
                            rs.getString("date_source"),
                            ""
                    ));
                }
            }
        }
        return map;
    }

    private static List<RawFactRow> loadMandateFacts(Connection connection, long mandateId)
            throws SQLException {
        String sql = "SELECT f.fact_id, f.document_version_id, f.ontology_path, f.field_key,"
                + " f.subject_entity, f.site_id, f.line_key, f.is_current, f.superseded_by_fact_id,"
                + " d.doc_type_code,"
                + " (SELECT e.source_text FROM fact_evidence e WHERE e.fact_id = f.fact_id AND e.role = 'period' LIMIT 1) AS period"
                + " FROM fact f"
                + " JOIN document_version v ON v.document_version_id = f.document_version_id"
                + " JOIN document d ON d.document_id = v.document_id"
                + " WHERE d.mandate_id = ?"
                + " ORDER BY f.fact_id";

        List<RawFactRow> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long siteRaw = rs.getLong("site_id");
                    Long siteId = rs.wasNull() ? null : siteRaw;
                    long superRaw = rs.getLong("superseded_by_fact_id");
                    Long supersededByFactId = rs.wasNull() ? null : superRaw;

                    list.add(new RawFactRow(
                            rs.getLong("fact_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("ontology_path"),
                            rs.getString("doc_type_code"),
                            rs.getString("field_key"),
                            rs.getString("subject_entity"),
                            siteId,
                            rs.getString("period"),
                            rs.getString("line_key"),
                            rs.getInt("is_current") == 1,
                            supersededByFactId
                    ));
                }
            }
        }
        return list;
    }
}
