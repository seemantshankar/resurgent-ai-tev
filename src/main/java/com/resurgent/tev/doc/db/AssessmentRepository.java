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
import java.util.Optional;

/**
 * Persistence and retrieval for the M3 mandate assessment (spec #133). This ticket owns
 * the document-date columns on {@code document_version} and the {@code assessment_run}
 * records; later M3 tickets add their own tables and read sections beside these.
 *
 * <p>The repository never commits or rolls back — the caller owns the transaction, the
 * same discipline as {@link DocumentRepository} and {@link FactRepository}.
 */
public final class AssessmentRepository {

    /** Provenance entity type for a document date read from a document. */
    public static final String DATE_ENTITY_TYPE = "document_date";

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public AssessmentRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * A document version under assessment, with its classification and ingest metadata.
     *
     * @param documentVersionId version id
     * @param documentId logical document id
     * @param logicalName document logical name
     * @param versionLabel version label
     * @param fileName stored file name
     * @param fileRole ingest file role
     * @param rawMetadata ingest metadata JSON, may be null
     * @param ingestedAt upload date (the ingest timestamp)
     * @param docTypeCode classified pack code, may be null
     */
    public record VersionRow(
            long documentVersionId,
            long documentId,
            String logicalName,
            String versionLabel,
            String fileName,
            String fileRole,
            String rawMetadata,
            String ingestedAt,
            String docTypeCode) {}

    /** One page's text for the assessment readers (layout blocks, else parsed markdown). */
    public record PageText(int pageIndex, String text) {}

    /** Layout anchor for a stated date: the block whose text contains the quote. */
    public record BlockAnchor(
            long pageId,
            int pageIndex,
            long blockId,
            Double bboxX0,
            Double bboxY0,
            Double bboxX1,
            Double bboxY1,
            String text) {}

    /**
     * Every version of every active document in the mandate, in document-then-version order.
     * M3 records dates per version and never chooses a winner (M4).
     *
     * @throws SQLException if the query fails
     */
    public List<VersionRow> versionsForMandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dv.document_version_id, dv.document_id, d.logical_name,"
                        + " dv.version_label, dv.file_name, dv.file_role, dv.raw_metadata,"
                        + " dv.ingested_at, dc.doc_type_code"
                        + " FROM document_version dv"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN doc_classification dc"
                        + "  ON dc.document_version_id = dv.document_version_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dv.document_id, dv.document_version_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<VersionRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new VersionRow(
                            rs.getLong("document_version_id"),
                            rs.getLong("document_id"),
                            rs.getString("logical_name"),
                            rs.getString("version_label"),
                            rs.getString("file_name"),
                            rs.getString("file_role"),
                            rs.getString("raw_metadata"),
                            rs.getString("ingested_at"),
                            rs.getString("doc_type_code")));
                }
                return rows;
            }
        }
    }

    /**
     * One text per page: layout blocks in reading order when the page has any, else the
     * stored parsed markdown/text. Pages without text are omitted.
     *
     * @throws SQLException if the query fails
     */
    public List<PageText> pageTexts(long documentVersionId) throws SQLException {
        List<PageText> pages = new ArrayList<>();
        int currentPage = -1;
        StringBuilder buffer = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.page_index AS page_index,"
                        + " COALESCE(NULLIF(b.text_norm, ''), b.text_raw) AS block_text"
                        + " FROM block b JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, COALESCE(b.reading_order, b.block_index)")) {
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
                    buffer.append(text.trim());
                }
                if (currentPage > 0 && !buffer.isEmpty()) {
                    pages.add(new PageText(currentPage, buffer.toString()));
                }
            }
        }
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

    /**
     * Finds the first layout block whose whitespace-normalized text contains the
     * normalized needle — the deterministic anchor for a stated date quote.
     *
     * @param documentVersionId version to search
     * @param needle quote or date text as stated
     * @return the block anchor, or empty when the text is not in the layout
     * @throws SQLException if the query fails
     */
    public Optional<BlockAnchor> findBlockAnchor(long documentVersionId, String needle)
            throws SQLException {
        String normalized = normalizeWhitespace(needle);
        if (normalized == null || normalized.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT b.block_id, p.page_id, p.page_index, b.bbox_x0, b.bbox_y0,"
                        + " b.bbox_x1, b.bbox_y1, COALESCE(b.text_norm, b.text_raw) AS block_text"
                        + " FROM block b JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " AND instr(COALESCE(b.text_norm, b.text_raw), ?) > 0"
                        + " ORDER BY p.page_index, COALESCE(b.reading_order, b.block_index)"
                        + " LIMIT 1")) {
            ps.setLong(1, documentVersionId);
            ps.setString(2, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BlockAnchor(
                        rs.getLong("page_id"),
                        rs.getInt("page_index"),
                        rs.getLong("block_id"),
                        asDouble(rs.getObject("bbox_x0")),
                        asDouble(rs.getObject("bbox_y0")),
                        asDouble(rs.getObject("bbox_x1")),
                        asDouble(rs.getObject("bbox_y1")),
                        rs.getString("block_text")));
            }
        }
    }

    /**
     * Drops a version's previous date assessment: the date provenance written by the
     * previous run and the stored date columns. Must run before a re-run inserts its
     * own provenance, so re-running never duplicates.
     *
     * @throws SQLException if any statement fails
     */
    public void clearDocumentDate(long documentVersionId) throws SQLException {
        // Detach the reference first: SQLite enforces the FK immediately, so the
        // provenance row cannot be deleted while document_version still points at it.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET document_date = NULL, date_precision = NULL,"
                        + " date_source = NULL, date_provenance_id = NULL"
                        + " WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = ? AND entity_id = ?")) {
            ps.setString(1, DATE_ENTITY_TYPE);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * Stores a version's assessed date; {@code provenanceId} is null for
     * file-metadata/none sources or when the quote was not anchored.
     *
     * @throws SQLException if the update fails
     */
    public void storeDocumentDate(long documentVersionId, String dateKind,
            String documentDate, String precision, String source, Long provenanceId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET document_date = ?, date_precision = ?,"
                        + " date_source = ?, date_kind = ?, date_provenance_id = ?"
                        + " WHERE document_version_id = ?")) {
            ps.setString(1, documentDate);
            ps.setString(2, precision);
            ps.setString(3, source);
            ps.setString(4, dateKind);
            if (provenanceId == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setLong(5, provenanceId);
            }
            ps.setLong(6, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts (or replaces) the run record of one stage for a mandate. One row per
     * {@code (mandate, stage)}: a re-run replaces the previous run — history is M4.
     *
     * @return the {@code assessment_run_id}
     * @throws SQLException if the upsert fails
     */
    public long upsertAssessmentRun(long mandateId, String stage, String status,
            Long parseRunId, String startedAt, String finishedAt, String metricsJson)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO assessment_run (mandate_id, stage, status, parse_run_id,"
                        + " started_at, finished_at, metrics_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (mandate_id, stage) DO UPDATE SET"
                        + " status = excluded.status, parse_run_id = excluded.parse_run_id,"
                        + " started_at = excluded.started_at, finished_at = excluded.finished_at,"
                        + " metrics_json = excluded.metrics_json",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, stage);
            ps.setString(3, status);
            if (parseRunId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, parseRunId);
            }
            ps.setString(5, startedAt);
            ps.setString(6, finishedAt);
            ps.setString(7, metricsJson);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT assessment_run_id FROM assessment_run WHERE mandate_id = ? AND stage = ?")) {
            ps.setLong(1, mandateId);
            ps.setString(2, stage);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("assessment_run upsert returned no row for stage " + stage);
    }

    /**
     * The run record of each stage executed for the mandate, oldest stage first.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.AssessmentRunRow> assessmentRuns(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT stage, status, started_at, finished_at, parse_run_id"
                        + " FROM assessment_run WHERE mandate_id = ? ORDER BY assessment_run_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.AssessmentRunRow> rows = new ArrayList<>();
                while (rs.next()) {
                    long parseRunRaw = rs.getLong("parse_run_id");
                    rows.add(new MandateQa.AssessmentRunRow(
                            rs.getString("stage"),
                            rs.getString("status"),
                            rs.getString("started_at"),
                            rs.getString("finished_at"),
                            rs.wasNull() ? null : parseRunRaw));
                }
                return rows;
            }
        }
    }

    /**
     * Document dates for every version of the mandate, section of {@link MandateQa}.
     *
     * @throws SQLException if the query fails
     */
    public List<MandateQa.DocumentDateRow> documentDates(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT d.document_id, dv.document_version_id, d.logical_name, dv.version_label,"
                        + " dv.file_name, dv.date_kind, dv.document_date, dv.date_precision,"
                        + " dv.date_source, dv.ingested_at, p.page_index, pd.verbatim"
                        + " FROM document_version dv"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN provenance_doc pd"
                        + "  ON pd.provenance_id = dv.date_provenance_id"
                        + " LEFT JOIN page p ON p.page_id = pd.page_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dv.document_id, dv.document_version_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MandateQa.DocumentDateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    int pageIndex = rs.getInt("page_index");
                    rows.add(new MandateQa.DocumentDateRow(
                            rs.getLong("document_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("logical_name"),
                            rs.getString("version_label"),
                            rs.getString("file_name"),
                            rs.getString("date_kind"),
                            rs.getString("document_date"),
                            rs.getString("date_precision"),
                            rs.getString("date_source"),
                            rs.getString("verbatim"),
                            rs.wasNull() ? null : pageIndex,
                            rs.getString("ingested_at")));
                }
                return rows;
            }
        }
    }

    /**
     * The source file the mandate's assessment runs hang their usage stats on: the
     * source file of the mandate's first document version. Null when the mandate has
     * no version attached to a source file.
     *
     * @throws SQLException if the query fails
     */
    public Long anchorSourceFileId(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dv.source_file_id FROM document_version dv"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " AND dv.source_file_id IS NOT NULL"
                        + " ORDER BY dv.document_version_id LIMIT 1")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * The full mandate QA read {@link MandateQa}.
     *
     * @throws SQLException if any query fails
     */
    public MandateQa readMandateQa(long mandateId) throws SQLException {
        return new MandateQa(mandateId, documentDates(mandateId), assessmentRuns(mandateId),
                new EntityRepository(connection).entities(mandateId),
                new ClauseRepository(connection).clauses(mandateId),
                new SiteRepository(connection).sitesForMandate(mandateId),
                new ChecklistRepository(connection).results(mandateId),
                new ChecklistRepository(connection).summaries(mandateId));
    }

    /** Collapses whitespace runs to single spaces, like the layout's {@code text_norm}. */
    public static String normalizeWhitespace(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim();
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
