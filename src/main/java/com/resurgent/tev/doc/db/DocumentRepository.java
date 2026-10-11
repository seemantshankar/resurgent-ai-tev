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

    public DocumentRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

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

    public void setCurrentVersion(long documentId, long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document SET current_version_id = ?, updated_at = ? WHERE document_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setString(2, Timestamps.now());
            ps.setLong(3, documentId);
            ps.executeUpdate();
        }
    }

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

    public long insertBlock(long pageId, int blockIndex, String blockKind,
            Double x0, Double y0, Double x1, Double y1, String textRaw, String textNorm,
            Integer readingOrder, String extractor) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO block (page_id, block_index, block_kind, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, text_raw, text_norm, reading_order,"
                        + " is_handwritten, extractor)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
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
            ps.setString(11, extractor);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

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

    /** True when this version already has a completed layout graph. */
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
     */
    public int cloneLayout(long fromVersionId, long toVersionId) throws SQLException {
        int pages = 0;
        // pages
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page (document_version_id, page_index, width_px, height_px, image_path)"
                        + " SELECT ?, page_index, width_px, height_px, image_path"
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
