package com.resurgent.tev.doc.db;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for document identity, versions, and the layout graph
 * (Architecture §6.2–6.3). Operational tables ({@code parse_run}, {@code audit_log})
 * stay on {@link WorkspaceRepository}.
 */
public final class DocumentRepository {

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations. The caller owns its lifecycle
     * and transaction boundaries; repository methods do not commit or roll back.
     */
    public DocumentRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Creates an active logical document with creation and update timestamps.
     *
     * @return the generated document ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertDocument(long mandateId, String logicalName, String docFamily,
            String docTypeCode) throws SQLException {
        String now = Timestamps.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document (mandate_id, logical_name, doc_family, doc_type_code,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'active', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, logicalName);
            ps.setString(3, docFamily);
            ps.setString(4, docTypeCode);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Returns the lowest ID matching mandate and logical name, excluding documents
     * marked deleted_by_analyst, or empty when none match.
     *
     * @throws SQLException if the lookup fails
     */
    public Optional<Long> findDocumentId(long mandateId, String logicalName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_id FROM document WHERE mandate_id = ? AND logical_name = ?"
                        + " AND status != 'deleted_by_analyst' ORDER BY document_id LIMIT 1")) {
            ps.setLong(1, mandateId);
            ps.setString(2, logicalName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
                return Optional.empty();
            }
        }
    }

    public void touchDocument(long documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document SET updated_at = ? WHERE document_id = ?")) {
            ps.setString(1, Timestamps.now());
            ps.setLong(2, documentId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the mandate owning the document.
     *
     * @throws SQLException if the document is unknown or the lookup fails
     */
    public long findMandateId(long documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mandate_id FROM document WHERE document_id = ?")) {
            ps.setLong(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("unknown document " + documentId);
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * Updates the current-version pointer and document timestamp. Does nothing for
     * an unknown document ID; does not check whether the version belongs to it.
     *
     * @throws SQLException if the update fails
     */
    public void setCurrentVersion(long documentId, long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document SET current_version_id = ?, updated_at = ? WHERE document_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setString(2, Timestamps.now());
            ps.setLong(3, documentId);
            ps.executeUpdate();
        }
    }

    /**
     * Finds a version with matching bytes within one logical document, or returns
     * empty when no version matches.
     *
     * @throws SQLException if the lookup fails
     */
    public Optional<Long> findVersionIdByHash(long documentId, String fileHash)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_version_id FROM document_version"
                        + " WHERE document_id = ? AND file_hash = ?")) {
            ps.setLong(1, documentId);
            ps.setString(2, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
                return Optional.empty();
            }
        }
    }

    /** Every version that carries these exact bytes (any logical document). */
    public List<Long> findVersionIdsByHash(String fileHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_version_id FROM document_version WHERE file_hash = ?"
                        + " ORDER BY document_version_id")) {
            ps.setString(1, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    /**
     * Inserts a version with ingested status and the current ingest timestamp.
     * Does not change the logical document's current-version pointer.
     *
     * @return the generated version ID
     * @throws SQLException if constraints reject the version, the insert otherwise
     *         fails, or no generated key is returned
     */
    public long insertDocumentVersion(long documentId, String versionLabel, String fileName,
            String fileHash, String fileExt, String fileRole, Integer pageCount,
            boolean ocrRequired, Long sourceFileId, String rawMetadata) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_version (document_id, version_label, file_name, file_hash,"
                        + " file_ext, file_role, page_count, lang_primary, ocr_required,"
                        + " ingest_status, ingested_at, source_file_id, raw_metadata)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, 'ingested', ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, documentId);
            ps.setString(2, versionLabel);
            ps.setString(3, fileName);
            ps.setString(4, fileHash);
            ps.setString(5, fileExt);
            ps.setString(6, fileRole);
            if (pageCount == null) {
                ps.setObject(7, null);
            } else {
                ps.setInt(7, pageCount);
            }
            ps.setInt(8, ocrRequired ? 1 : 0);
            ps.setString(9, Timestamps.now());
            if (sourceFileId == null) {
                ps.setObject(10, null);
            } else {
                ps.setLong(10, sourceFileId);
            }
            ps.setString(11, rawMetadata);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Returns the classified pack code for a version, if any ({@code doc_type_code},
     * which is a pack code or {@code unknown}).
     *
     * @throws SQLException if the lookup fails
     */
    public Optional<String> findClassificationCode(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT doc_type_code FROM doc_classification WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String code = rs.getString(1);
                    return code == null || code.isBlank() ? Optional.empty() : Optional.of(code);
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Returns stored version metadata, preserving absent page count and source
     * file ID as null, or empty if the version does not exist.
     *
     * @throws SQLException if the lookup fails
     */
    public Optional<DocumentVersionRow> findVersion(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_version_id, document_id, version_label, file_name, file_hash,"
                        + " file_ext, file_role, page_count, ocr_required, ingest_status,"
                        + " source_file_id"
                        + " FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Integer pageCount = (Integer) rs.getObject("page_count");
                long sourceRaw = rs.getLong("source_file_id");
                Long sourceFileId = rs.wasNull() ? null : sourceRaw;
                return Optional.of(new DocumentVersionRow(
                        rs.getLong("document_version_id"),
                        rs.getLong("document_id"),
                        rs.getString("version_label"),
                        rs.getString("file_name"),
                        rs.getString("file_hash"),
                        rs.getString("file_ext"),
                        rs.getString("file_role"),
                        pageCount,
                        rs.getInt("ocr_required") == 1,
                        rs.getString("ingest_status"),
                        sourceFileId));
            }
        }
    }

    public void updateVersionPageCount(long documentVersionId, int pageCount,
            boolean ocrRequired) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET page_count = ?, ocr_required = ?"
                        + " WHERE document_version_id = ?")) {
            ps.setInt(1, pageCount);
            ps.setInt(2, ocrRequired ? 1 : 0);
            ps.setLong(3, documentVersionId);
            ps.executeUpdate();
        }
    }

    public void updateVersionIngestStatus(long documentVersionId, String ingestStatus)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET ingest_status = ? WHERE document_version_id = ?")) {
            ps.setString(1, ingestStatus);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * Records (or refreshes) the registry row for a published induced pack. The YAML
     * itself lives in the pack store; this row is the queryable metadata mirror.
     *
     * @param fileHash SHA-256 of the published YAML (required, non-null)
     * @param yamlPath path relative to the pack store root
     * @param inductionRunId opaque induction run id (e.g. {@code induce:<parse_run_id>})
     * @throws SQLException if the upsert fails
     */
    public void upsertDocTypePack(String code, String version, String family, String fileRole,
            String industryTag, String fileHash, String yamlPath, String sourceMandatesJson,
            String inductionRunId, String status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO doc_type_pack (code, version, family, file_role, industry_tag,"
                        + " file_hash, yaml_path, source_mandates_json, induction_run_id, status,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(code, version) DO UPDATE SET"
                        + " family = excluded.family, file_role = excluded.file_role,"
                        + " industry_tag = excluded.industry_tag, file_hash = excluded.file_hash,"
                        + " yaml_path = excluded.yaml_path,"
                        + " source_mandates_json = excluded.source_mandates_json,"
                        + " induction_run_id = excluded.induction_run_id,"
                        + " status = excluded.status")) {
            ps.setString(1, code);
            ps.setString(2, version);
            ps.setString(3, family);
            ps.setString(4, fileRole);
            ps.setString(5, industryTag);
            ps.setString(6, fileHash == null ? "" : fileHash);
            ps.setString(7, yamlPath);
            ps.setString(8, sourceMandatesJson);
            ps.setString(9, inductionRunId);
            ps.setString(10, status == null ? "published" : status);
            ps.setString(11, Timestamps.now());
            ps.executeUpdate();
        }
    }

    /**
     * Creates a page row without writing an image file.
     *
     * @param pageIndex one-based page number within the version
     * @param widthPx raster width in pixels, or null when unknown
     * @param heightPx raster height in pixels, or null when unknown
     * @param imagePath path relative to the blob root, or null when no raster is stored
     * @return the generated page ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertPage(long documentVersionId, int pageIndex, Integer widthPx,
            Integer heightPx, String imagePath) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page (document_version_id, page_index, width_px, height_px,"
                        + " image_path) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            if (widthPx == null) {
                ps.setObject(3, null);
            } else {
                ps.setInt(3, widthPx);
            }
            if (heightPx == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, heightPx);
            }
            ps.setString(5, imagePath);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Records which engine read a page. {@code none} means PDFBox digital text;
     * a local engine id means OCR. Confidence is in {@code [0, 1]} or null.
     */
    public void updatePageOcr(long pageId, String ocrEngine, Double ocrConfidence,
            String langDetected) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page SET ocr_engine = ?, ocr_confidence = ?, lang_detected = ?"
                        + " WHERE page_id = ?")) {
            ps.setString(1, ocrEngine);
            setDouble(ps, 2, ocrConfidence);
            ps.setString(3, langDetected);
            ps.setLong(4, pageId);
            ps.executeUpdate();
        }
    }

    /**
     * Active documents in a mandate, one row per version. A document whose
     * pages were not recorded has an empty {@link #pageIndexesOf(long)} list.
     *
     * @param mandateId mandate scope
     * @return documents in id order
     * @throws SQLException if the lookup fails
     */
    public List<HeldDocument> documentsIn(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT d.document_id, dv.document_version_id, d.doc_type_code"
                        + " FROM document d"
                        + " JOIN document_version dv ON dv.document_id = d.document_id"
                        + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY d.document_id, dv.document_version_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<HeldDocument> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new HeldDocument(
                            rs.getLong("document_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("doc_type_code")));
                }
                return rows;
            }
        }
    }

    /**
     * Replaces the file pages recorded for a document. An empty list leaves the
     * document with no pages.
     *
     * @param documentId logical document
     * @param pageIds pages of the file, in any order
     * @throws SQLException if the write fails
     */
    public void replaceMemberPages(long documentId, List<Long> pageIds) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM document_page WHERE document_id = ?")) {
            ps.setLong(1, documentId);
            ps.executeUpdate();
        }
        if (pageIds == null || pageIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
            for (Long pageId : pageIds) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * File pages recorded as belonging to this document, in page order.
     * Empty when the file was not stored as that document.
     *
     * @param documentId logical document
     * @return one-based page indexes
     * @throws SQLException if the lookup fails
     */
    public List<Integer> pageIndexesOf(long documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.page_index FROM document_page dp"
                        + " JOIN page p ON p.page_id = dp.page_id"
                        + " WHERE dp.document_id = ? ORDER BY p.page_index")) {
            ps.setLong(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Integer> pages = new ArrayList<>();
                while (rs.next()) {
                    pages.add(rs.getInt(1));
                }
                return pages;
            }
        }
    }

    public Optional<Long> findPageId(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Deletes the version's provenance, table cells/grids, spans, blocks, and pages.
     * Leaves the version row and blob files intact. Uses the caller's transaction;
     * a failure can leave partial deletions when auto-commit is enabled.
     *
     * @throws SQLException if any deletion fails
     */
    public void deleteLayoutForVersion(long documentVersionId) throws SQLException {
        // provenance_doc → spans/tables → blocks → pages. FKs are ON, so children first.
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM table_cell WHERE table_grid_id IN ("
                        + " SELECT tg.table_grid_id FROM table_grid tg"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM table_grid WHERE block_id IN ("
                        + " SELECT b.block_id FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM span WHERE block_id IN ("
                        + " SELECT b.block_id FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM block WHERE page_id IN ("
                        + " SELECT page_id FROM page WHERE document_version_id = ?)")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM page WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts a block marked as not handwritten.
     *
     * @param blockIndex zero-based block position on the page
     * @param x0 left box coordinate in PDF points from the page's top-left, or null
     * @param y0 top box coordinate in PDF points from the page's top-left, or null
     * @param x1 right box coordinate in PDF points from the page's top-left, or null
     * @param y1 bottom box coordinate in PDF points from the page's top-left, or null
     * @return the generated block ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertBlock(long pageId, int blockIndex, String blockKind,
            Double x0, Double y0, Double x1, Double y1, String textRaw, String textNorm,
            Integer readingOrder, String extractor) throws SQLException {
        return insertBlock(pageId, blockIndex, blockKind, x0, y0, x1, y1, textRaw, textNorm,
                readingOrder, extractor, false, null);
    }

    /**
     * Inserts a block, including handwriting and the block's OCR confidence.
     *
     * @param handwritten {@code true} only for a handwriting block ({@code is_handwritten = 1})
     * @param ocrConfidence mean confidence in {@code [0, 1]}, or null for digital text
     */
    public long insertBlock(long pageId, int blockIndex, String blockKind,
            Double x0, Double y0, Double x1, Double y1, String textRaw, String textNorm,
            Integer readingOrder, String extractor, boolean handwritten, Double ocrConfidence)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO block (page_id, block_index, block_kind, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, text_raw, text_norm, reading_order,"
                        + " is_handwritten, ocr_confidence, extractor)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pageId);
            ps.setInt(2, blockIndex);
            ps.setString(3, blockKind);
            setDouble(ps, 4, x0);
            setDouble(ps, 5, y0);
            setDouble(ps, 6, x1);
            setDouble(ps, 7, y1);
            ps.setString(8, textRaw);
            ps.setString(9, textNorm);
            if (readingOrder == null) {
                ps.setObject(10, null);
            } else {
                ps.setInt(10, readingOrder);
            }
            ps.setInt(11, handwritten ? 1 : 0);
            setDouble(ps, 12, ocrConfidence);
            ps.setString(13, extractor);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Inserts a text span with nullable box coordinates in PDF points from the
     * page's top-left and optional confidence.
     *
     * @param spanIndex zero-based span position within the block
     * @return the generated span ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertSpan(long blockId, int spanIndex, String textRaw,
            Double x0, Double y0, Double x1, Double y1, Double confidence) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO span (block_id, span_index, text_raw, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, confidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, blockId);
            ps.setInt(2, spanIndex);
            ps.setString(3, textRaw);
            setDouble(ps, 4, x0);
            setDouble(ps, 5, y0);
            setDouble(ps, 6, x1);
            setDouble(ps, 7, y1);
            setDouble(ps, 8, confidence);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Inserts table dimensions and the number of leading header rows for a block.
     *
     * @return the generated grid ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertTableGrid(long blockId, int nRows, int nCols, int headerRowCount)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO table_grid (block_id, n_rows, n_cols, header_row_count)"
                        + " VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, blockId);
            ps.setInt(2, nRows);
            ps.setInt(3, nCols);
            ps.setInt(4, headerRowCount);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Inserts an unmerged table cell with nullable box coordinates in PDF points
     * from the page's top-left. Row and column spans are both one.
     *
     * @param rowIndex zero-based row position
     * @param colIndex zero-based column position
     * @return the generated cell ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertTableCell(long tableGridId, int rowIndex, int colIndex, String textRaw,
            Double x0, Double y0, Double x1, Double y1, boolean isHeader) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO table_cell (table_grid_id, row_index, col_index, text_raw,"
                        + " bbox_x0, bbox_y0, bbox_x1, bbox_y1, row_span, col_span, is_header)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, tableGridId);
            ps.setInt(2, rowIndex);
            ps.setInt(3, colIndex);
            ps.setString(4, textRaw);
            setDouble(ps, 5, x0);
            setDouble(ps, 6, y0);
            setDouble(ps, 7, x1);
            setDouble(ps, 8, y1);
            ps.setInt(9, isHeader ? 1 : 0);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Links an entity to a verbatim source quote and optional layout anchors.
     * Stores the supplied offsets, geometry, and confidence without deriving them;
     * marks the provenance as not derived and leaves its quote hash unset.
     *
     * @return the generated provenance ID
     * @throws SQLException if the insert fails or yields no generated key
     */
    public long insertProvenance(String entityType, long entityId, long documentVersionId,
            Long pageId, Long blockId, Long spanId, Long tableCellId,
            Double x0, Double y0, Double x1, Double y1,
            Integer charStart, Integer charEnd, String verbatim, Double confidence)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO provenance_doc (entity_type, entity_id, document_version_id,"
                        + " page_id, block_id, span_id, table_cell_id, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, char_start, char_end, verbatim, quote_hash,"
                        + " confidence, is_derived)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entityType);
            ps.setLong(2, entityId);
            ps.setLong(3, documentVersionId);
            setLong(ps, 4, pageId);
            setLong(ps, 5, blockId);
            setLong(ps, 6, spanId);
            setLong(ps, 7, tableCellId);
            setDouble(ps, 8, x0);
            setDouble(ps, 9, y0);
            setDouble(ps, 10, x1);
            setDouble(ps, 11, y1);
            if (charStart == null) {
                ps.setObject(12, null);
            } else {
                ps.setInt(12, charStart);
            }
            if (charEnd == null) {
                ps.setObject(13, null);
            } else {
                ps.setInt(13, charEnd);
            }
            ps.setString(14, verbatim);
            setDouble(ps, 15, confidence);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Creates a directed relation from one document version to another with the
     * current timestamp. An existing identical edge is rejected by the database.
     *
     * @return the generated relation ID
     * @throws SQLException if the insert fails, including for a duplicate edge,
     *         or yields no generated key
     */
    public long insertRelation(long fromVersionId, long toVersionId, String relationKind,
            String note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_relation (from_document_version_id,"
                        + " to_document_version_id, relation_kind, note, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fromVersionId);
            ps.setLong(2, toVersionId);
            ps.setString(3, relationKind);
            ps.setString(4, note);
            ps.setString(5, Timestamps.now());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Returns page IDs in page-index order, or an empty list when the version
     * has no page rows.
     *
     * @throws SQLException if the query fails
     */
    public List<Long> selectPageIds(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    public int countBlocks(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM block b JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * A finished layout that is still a raster placeholder: {@code layout_ok},
     * {@code ocr_required = 1}, and at least one page with no OCR engine.
     * Re-layout upgrades those pages in place instead of returning the cache.
     */
    public boolean needsLocalOcrUpgrade(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM document_version v"
                        + " WHERE v.document_version_id = ?"
                        + " AND v.ingest_status = 'layout_ok'"
                        + " AND v.ocr_required = 1"
                        + " AND EXISTS (SELECT 1 FROM page p"
                        + "  WHERE p.document_version_id = v.document_version_id"
                        + "  AND p.ocr_engine IS NULL)")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Drops blocks, spans, tables, and provenance hanging off one page.
     * The page row itself stays so a placeholder can be upgraded in place.
     */
    public void clearPageGraph(long pageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_evidence WHERE provenance_id IN ("
                        + " SELECT provenance_id FROM provenance_doc"
                        + " WHERE page_id = ? OR block_id IN"
                        + " (SELECT block_id FROM block WHERE page_id = ?))")) {
            ps.setLong(1, pageId);
            ps.setLong(2, pageId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE page_id = ? OR block_id IN"
                        + " (SELECT block_id FROM block WHERE page_id = ?)")) {
            ps.setLong(1, pageId);
            ps.setLong(2, pageId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM table_cell WHERE table_grid_id IN ("
                        + " SELECT tg.table_grid_id FROM table_grid tg"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " WHERE b.page_id = ?)")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM table_grid WHERE block_id IN"
                        + " (SELECT block_id FROM block WHERE page_id = ?)")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM span WHERE block_id IN"
                        + " (SELECT block_id FROM block WHERE page_id = ?)")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM block WHERE page_id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
    }

    /** Updates stored pixel size and the relative page-image path. */
    public void updatePageRaster(long pageId, Integer widthPx, Integer heightPx, String imagePath)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page SET width_px = ?, height_px = ?, image_path = ? WHERE page_id = ?")) {
            if (widthPx == null) {
                ps.setObject(1, null);
            } else {
                ps.setInt(1, widthPx);
            }
            if (heightPx == null) {
                ps.setObject(2, null);
            } else {
                ps.setInt(2, heightPx);
            }
            ps.setString(3, imagePath);
            ps.setLong(4, pageId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns true when the version is marked layout_ok and has at least one
     * page row. Text blocks and image files are not checked.
     */
    public boolean hasLayout(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ingest_status FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "layout_ok".equals(rs.getString(1))
                        && countPages(documentVersionId) > 0;
            }
        }
    }

    public int countPages(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM page WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Another version of the same bytes that already has layout. Callers clone
     * from this instead of re-parsing / re-rasterizing (no waste).
     */
    public Optional<Long> findLayoutedSibling(long documentVersionId, String fileHash)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT v.document_version_id FROM document_version v"
                        + " WHERE v.file_hash = ? AND v.document_version_id != ?"
                        + " AND v.ingest_status = 'layout_ok'"
                        + " AND EXISTS (SELECT 1 FROM page p"
                        + "  WHERE p.document_version_id = v.document_version_id)"
                        + " ORDER BY v.document_version_id LIMIT 1")) {
            ps.setString(1, fileHash);
            ps.setLong(2, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Copy page/block/span/table rows from a sibling version of the same file
     * hash. Image paths stay shared under {@code blobs/{hash}/…}.
     * The caller must ensure matching file hashes and clear destination pages.
     * Does not copy provenance or update version status. Uses the caller's
     * transaction, so auto-commit can leave a partial clone on failure.
     *
     * @return the number of page rows copied
     * @throws SQLException if any insert fails, including page-index conflicts
     */
    public int cloneLayout(long fromVersionId, long toVersionId) throws SQLException {
        int pages = 0;
        // pages
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page (document_version_id, page_index, width_px, height_px,"
                        + " image_path, ocr_engine, ocr_confidence, lang_detected)"
                        + " SELECT ?, page_index, width_px, height_px, image_path,"
                        + " ocr_engine, ocr_confidence, lang_detected"
                        + " FROM page WHERE document_version_id = ?")) {
            ps.setLong(1, toVersionId);
            ps.setLong(2, fromVersionId);
            pages = ps.executeUpdate();
        }
        // blocks (join via page_index)
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO block (page_id, block_index, block_kind, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, text_raw, text_norm, reading_order,"
                        + " is_handwritten, ocr_confidence, extractor)"
                        + " SELECT tp.page_id, b.block_index, b.block_kind, b.bbox_x0, b.bbox_y0,"
                        + "  b.bbox_x1, b.bbox_y1, b.text_raw, b.text_norm, b.reading_order,"
                        + "  b.is_handwritten, b.ocr_confidence, b.extractor"
                        + " FROM block b"
                        + " JOIN page sp ON sp.page_id = b.page_id"
                        + " JOIN page tp ON tp.document_version_id = ?"
                        + "  AND tp.page_index = sp.page_index"
                        + " WHERE sp.document_version_id = ?")) {
            ps.setLong(1, toVersionId);
            ps.setLong(2, fromVersionId);
            ps.executeUpdate();
        }
        // spans
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO span (block_id, span_index, text_raw, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, confidence)"
                        + " SELECT tb.block_id, s.span_index, s.text_raw, s.bbox_x0, s.bbox_y0,"
                        + "  s.bbox_x1, s.bbox_y1, s.confidence"
                        + " FROM span s"
                        + " JOIN block sb ON sb.block_id = s.block_id"
                        + " JOIN page sp ON sp.page_id = sb.page_id"
                        + " JOIN page tp ON tp.document_version_id = ?"
                        + "  AND tp.page_index = sp.page_index"
                        + " JOIN block tb ON tb.page_id = tp.page_id"
                        + "  AND tb.block_index = sb.block_index"
                        + " WHERE sp.document_version_id = ?")) {
            ps.setLong(1, toVersionId);
            ps.setLong(2, fromVersionId);
            ps.executeUpdate();
        }
        // table grids + cells
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO table_grid (block_id, n_rows, n_cols, header_row_count)"
                        + " SELECT tb.block_id, tg.n_rows, tg.n_cols, tg.header_row_count"
                        + " FROM table_grid tg"
                        + " JOIN block sb ON sb.block_id = tg.block_id"
                        + " JOIN page sp ON sp.page_id = sb.page_id"
                        + " JOIN page tp ON tp.document_version_id = ?"
                        + "  AND tp.page_index = sp.page_index"
                        + " JOIN block tb ON tb.page_id = tp.page_id"
                        + "  AND tb.block_index = sb.block_index"
                        + " WHERE sp.document_version_id = ?")) {
            ps.setLong(1, toVersionId);
            ps.setLong(2, fromVersionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO table_cell (table_grid_id, row_index, col_index, text_raw,"
                        + " bbox_x0, bbox_y0, bbox_x1, bbox_y1, row_span, col_span, is_header)"
                        + " SELECT tnt.table_grid_id, tc.row_index, tc.col_index, tc.text_raw,"
                        + "  tc.bbox_x0, tc.bbox_y0, tc.bbox_x1, tc.bbox_y1, tc.row_span,"
                        + "  tc.col_span, tc.is_header"
                        + " FROM table_cell tc"
                        + " JOIN table_grid sgt ON sgt.table_grid_id = tc.table_grid_id"
                        + " JOIN block sb ON sb.block_id = sgt.block_id"
                        + " JOIN page sp ON sp.page_id = sb.page_id"
                        + " JOIN page tp ON tp.document_version_id = ?"
                        + "  AND tp.page_index = sp.page_index"
                        + " JOIN block tb ON tb.page_id = tp.page_id"
                        + "  AND tb.block_index = sb.block_index"
                        + " JOIN table_grid tnt ON tnt.block_id = tb.block_id"
                        + " WHERE sp.document_version_id = ?")) {
            ps.setLong(1, toVersionId);
            ps.setLong(2, fromVersionId);
            ps.executeUpdate();
        }
        return pages;
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("INSERT did not return a generated key");
            }
            return keys.getLong(1);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * One stored document version in a mandate.
     *
     * @param documentId logical document
     * @param documentVersionId version that carries the classification and the facts
     * @param docTypeCode pack code, {@code unknown}, or null before classification
     */
    public record HeldDocument(long documentId, long documentVersionId, String docTypeCode) {}

    public record DocumentVersionRow(
            long documentVersionId,
            long documentId,
            String versionLabel,
            String fileName,
            String fileHash,
            String fileExt,
            String fileRole,
            Integer pageCount,
            boolean ocrRequired,
            String ingestStatus,
            Long sourceFileId) {}
}
