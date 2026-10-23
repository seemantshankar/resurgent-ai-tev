package com.resurgent.tev.doc.db;

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
 * Persistence and retrieval for the mandate's sites (M3-04, spec #133). Sites are
 * written by the {@code assess_profile} stage ({@code assess.SiteScan}) and read through
 * the mandate QA surface; {@code fact.site_id} carries the site a fact is about.
 *
 * <p>The repository never commits or rolls back — the caller owns the transaction, the
 * same discipline as {@link AssessmentRepository} and {@link DocumentRepository}.
 */
public final class SiteRepository {

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public SiteRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * A fact row the site scan inspects for site-name mentions and tags with its site.
     *
     * @param factId fact id
     * @param documentVersionId version the fact came from
     * @param fieldKey pack field key, may be null
     * @param ontologyPath bound path, may be null
     * @param valueKind fact value kind
     * @param valueText text value, may be null
     */
    public record FactSiteRow(
            long factId,
            long documentVersionId,
            String fieldKey,
            String ontologyPath,
            String valueKind,
            String valueText) {}

    /**
     * Every fact of every active document of the mandate, in fact-id (extraction) order.
     *
     * @param mandateId mandate scope
     * @return the facts to scan and tag
     * @throws SQLException if the query fails
     */
    public List<FactSiteRow> factsForMandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id, f.document_version_id, f.field_key, f.ontology_path,"
                        + " f.value_kind, f.value_text"
                        + " FROM fact f"
                        + " JOIN document_version dv ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY f.fact_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FactSiteRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new FactSiteRow(
                            rs.getLong("fact_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("field_key"),
                            rs.getString("ontology_path"),
                            rs.getString("value_kind"),
                            rs.getString("value_text")));
                }
                return rows;
            }
        }
    }

    /**
     * Drops the mandate's previous site assessment: the site tags on its facts, its alias
     * mentions and its site rows. A re-run replaces, never duplicates.
     *
     * @param mandateId mandate scope
     * @throws SQLException if any statement fails
     */
    public void clearMandateSites(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET site_id = NULL WHERE site_id IS NOT NULL"
                        + " AND document_version_id IN ("
                        + "  SELECT dv.document_version_id FROM document_version dv"
                        + "  JOIN document d ON d.document_id = dv.document_id"
                        + "  WHERE d.mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM site_alias WHERE site_id IN"
                        + " (SELECT site_id FROM site WHERE mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM site WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one site.
     *
     * @return the {@code site_id}
     * @throws SQLException if the insert fails
     */
    public long insertSite(long mandateId, String siteName, String createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO site (mandate_id, site_name, created_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, siteName);
            ps.setString(3, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("site insert returned no row for " + siteName);
    }

    /**
     * The mandate site with this name, ignoring case, or a newly inserted one.
     *
     * @return the {@code site_id}
     * @throws SQLException if the lookup or insert fails
     */
    public long findOrCreate(long mandateId, String siteName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT site_id FROM site WHERE mandate_id = ? AND lower(site_name) = lower(?)")) {
            ps.setLong(1, mandateId);
            ps.setString(2, siteName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return insertSite(mandateId, siteName, com.resurgent.tev.parser.db.Timestamps.now());
    }

    /**
     * Records one spelling mention of a site against the document it was seen in.
     *
     * @throws SQLException if the insert fails
     */
    public void insertAlias(long siteId, long documentVersionId, String aliasText,
            Double matchScore, String matchReason, String createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO site_alias (site_id, document_version_id, alias_text,"
                        + " match_score, match_reason, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, siteId);
            ps.setLong(2, documentVersionId);
            ps.setString(3, aliasText);
            if (matchScore == null) {
                ps.setNull(4, Types.REAL);
            } else {
                ps.setDouble(4, matchScore);
            }
            ps.setString(5, matchReason);
            ps.setString(6, createdAt);
            ps.executeUpdate();
        }
    }

    /**
     * Tags one fact with the site it is about, or clears the tag when {@code siteId} is null.
     *
     * @throws SQLException if the update fails
     */
    public void tagFact(long factId, Long siteId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET site_id = ? WHERE fact_id = ?")) {
            if (siteId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setLong(1, siteId);
            }
            ps.setLong(2, factId);
            ps.executeUpdate();
        }
    }

    /**
     * The mandate's sites with their aliases and mentioning documents — section of
     * {@link MandateQa}. Sites, aliases and documents are each in insertion order.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.SiteRow> sitesForMandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT s.site_id, s.site_name, a.alias_id, a.alias_text, a.match_score,"
                        + " a.match_reason, a.document_version_id, dv.file_name, d.logical_name"
                        + " FROM site s"
                        + " LEFT JOIN site_alias a ON a.site_id = s.site_id"
                        + " LEFT JOIN document_version dv"
                        + "  ON dv.document_version_id = a.document_version_id"
                        + " LEFT JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE s.mandate_id = ?"
                        + " ORDER BY s.site_id, a.alias_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, SiteBuilder> builders = new LinkedHashMap<>();
                while (rs.next()) {
                    long siteId = rs.getLong("site_id");
                    SiteBuilder builder = builders.computeIfAbsent(siteId,
                            id -> new SiteBuilder(siteId));
                    builder.siteName = rs.getString("site_name");
                    if (rs.getObject("alias_id") != null) {
                        long documentVersionId = rs.getLong("document_version_id");
                        builder.aliases.add(new MandateQa.SiteAliasRow(
                                documentVersionId,
                                rs.getString("logical_name"),
                                rs.getString("file_name"),
                                rs.getString("alias_text"),
                                asDouble(rs.getObject("match_score")),
                                rs.getString("match_reason")));
                        builder.documents.putIfAbsent(documentVersionId,
                                new MandateQa.SiteDocumentRow(
                                        documentVersionId,
                                        rs.getString("logical_name"),
                                        rs.getString("file_name")));
                    }
                }
                List<MandateQa.SiteRow> rows = new ArrayList<>(builders.size());
                for (SiteBuilder builder : builders.values()) {
                    rows.add(new MandateQa.SiteRow(builder.siteId, builder.siteName,
                            List.copyOf(builder.aliases), List.copyOf(builder.documents.values())));
                }
                return rows;
            }
        }
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static final class SiteBuilder {
        final long siteId;
        String siteName;
        final List<MandateQa.SiteAliasRow> aliases = new ArrayList<>();
        final Map<Long, MandateQa.SiteDocumentRow> documents = new LinkedHashMap<>();

        SiteBuilder(long siteId) {
            this.siteId = siteId;
        }
    }
}
