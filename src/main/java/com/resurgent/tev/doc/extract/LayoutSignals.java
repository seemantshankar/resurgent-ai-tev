package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Gathers {@link ParseTierSelector.Signals} from the M1 layout graph so Parse tier
 * choice follows document content (scans, tables, multi-column, figures, density).
 */
public final class LayoutSignals {

    private LayoutSignals() {}

    /**
     * Collects signals for a document version from {@code workspace.db}.
     *
     * @param dbPath workspace database
     * @param documentVersionId version to inspect
     * @return layout signals (never null; zeroed when layout is missing)
     * @throws Exception on SQL failure
     */
    public static ParseTierSelector.Signals fromVersion(Path dbPath, long documentVersionId)
            throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return fromVersion(db.connection(), documentVersionId);
        }
    }

    /**
     * Collects signals using a caller-owned connection.
     *
     * @param connection workspace connection
     * @param documentVersionId version to inspect
     * @return layout signals
     * @throws Exception on SQL failure
     */
    public static ParseTierSelector.Signals fromVersion(Connection connection,
            long documentVersionId) throws Exception {
        DocumentRepository docs = new DocumentRepository(connection);
        DocumentRepository.DocumentVersionRow version = docs.findVersion(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown document_version " + documentVersionId));

        boolean ocrRequired = version.ocrRequired();
        int pageCount = version.pageCount() == null ? 0 : version.pageCount();
        String fileRole = version.fileRole();

        int tableCount = count(connection, """
                SELECT COUNT(*) FROM table_grid tg
                JOIN block b ON b.block_id = tg.block_id
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                """, documentVersionId);

        int complexTableCount = count(connection, """
                SELECT COUNT(*) FROM table_grid tg
                JOIN block b ON b.block_id = tg.block_id
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                  AND (COALESCE(tg.n_rows, 0) >= 8 OR COALESCE(tg.n_cols, 0) >= 6
                       OR COALESCE(tg.header_row_count, 0) >= 2)
                """, documentVersionId);

        int figureCount = count(connection, """
                SELECT COUNT(*) FROM block b
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                  AND b.block_kind IN ('figure', 'stamp', 'signature')
                """, documentVersionId);

        boolean multiColumn = looksMultiColumn(connection, documentVersionId);
        boolean denseText = looksDense(connection, documentVersionId, pageCount);

        return new ParseTierSelector.Signals(
                ocrRequired,
                pageCount,
                tableCount,
                complexTableCount,
                figureCount,
                multiColumn,
                denseText,
                fileRole,
                false);
    }

    /**
     * Multi-column heuristic: a page with ≥2 text blocks whose x-ranges barely overlap
     * and whose y-ranges do (side-by-side columns).
     */
    private static boolean looksMultiColumn(Connection connection, long documentVersionId)
            throws Exception {
        String sql = """
                SELECT b.bbox_x0, b.bbox_x1, b.bbox_y0, b.bbox_y1
                FROM block b
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                  AND b.block_kind IN ('paragraph','heading','list')
                  AND b.bbox_x0 IS NOT NULL AND b.bbox_x1 IS NOT NULL
                  AND b.bbox_y0 IS NOT NULL AND b.bbox_y1 IS NOT NULL
                ORDER BY p.page_index, b.bbox_y0
                LIMIT 40
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, documentVersionId);
            double[][] boxes = new double[40][];
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && n < 40) {
                    boxes[n++] = new double[] {
                            rs.getDouble(1), rs.getDouble(2),
                            rs.getDouble(3), rs.getDouble(4)};
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double[] a = boxes[i];
                    double[] b = boxes[j];
                    double xOverlap = Math.min(a[1], b[1]) - Math.max(a[0], b[0]);
                    double yOverlap = Math.min(a[3], b[3]) - Math.max(a[2], b[2]);
                    // side-by-side: little x overlap, some y overlap
                    if (xOverlap <= 8 && yOverlap > 12) {
                        double leftA = Math.min(a[0], b[0]);
                        double rightA = Math.max(a[1], b[1]);
                        if (rightA - leftA > 200) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** Dense text: many blocks per page or long text runs (financial reports). */
    private static boolean looksDense(Connection connection, long documentVersionId, int pageCount)
            throws Exception {
        int blocks = count(connection, """
                SELECT COUNT(*) FROM block b
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                """, documentVersionId);
        int chars = count(connection, """
                SELECT COALESCE(SUM(LENGTH(COALESCE(b.text_raw, ''))), 0) FROM block b
                JOIN page p ON p.page_id = b.page_id
                WHERE p.document_version_id = ?
                """, documentVersionId);
        int pages = Math.max(1, pageCount);
        return blocks / pages >= 12 || chars / pages >= 2500;
    }

    private static int count(Connection connection, String sql, long documentVersionId)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
