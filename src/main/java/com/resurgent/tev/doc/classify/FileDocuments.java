package com.resurgent.tev.doc.classify;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Holds each work in a file as its own document (issue #183, ADR 0031).
 *
 * <p>A page that states a known document starts that document. A page that states
 * two works belongs to both, joining a work already open rather than copying it.
 * A later page that states the same document on its own is another document, so
 * two quotations are not merged. A page that only continues a work already
 * started — including after another document — stays with that work. A heading
 * that only names a type stays with the preceding document. An index and a blank
 * page belong to none. Returns empty when no page states a known document, and
 * writes nothing.
 */
final class FileDocuments {

    private static final Pattern AMENDMENT = Pattern.compile("(?i)\\bamendment\\b");

    private FileDocuments() {}

    /**
     * Records the works in this file. The file's own document becomes the first
     * work. Each later work is its own document, on the file pages that state it.
     *
     * @return the first work's classification, or empty when the file states none
     * @throws SQLException when a read or write fails
     */
    static Optional<ClassifyResult> separate(Connection connection, DocumentRepository docs,
            DocumentRepository.DocumentVersionRow version, long mandateId, String industryTag,
            List<DocTypePack> packs, DocTypeClassifier classifier) throws SQLException {
        List<StoredPage> pages = loadPages(connection, version.documentVersionId());
        List<Work> works = worksIn(pages, version, industryTag, packs, classifier);
        if (works.isEmpty()) {
            return Optional.empty();
        }
        removeSiblingWorks(connection, version.documentId(), version.documentVersionId());
        String logicalName = logicalName(connection, version.documentId());
        String rawMetadata = rawMetadata(connection, version.documentVersionId());
        boolean first = true;
        for (Work work : works) {
            if (first) {
                record(classifier, connection, version.documentVersionId(), version.fileRole(),
                        version.fileName(), industryTag, packs, work);
                docs.replaceMemberPages(version.documentId(), pageIds(work));
                docs.setCurrentVersion(version.documentId(), version.documentVersionId());
                first = false;
                continue;
            }
            insertWork(connection, docs, version, mandateId, industryTag, packs, classifier,
                    logicalName, rawMetadata, work);
        }
        return Optional.of(works.get(0).result);
    }

    private static List<Work> worksIn(List<StoredPage> pages,
            DocumentRepository.DocumentVersionRow version, String industryTag,
            List<DocTypePack> packs, DocTypeClassifier classifier) {
        List<Work> works = new ArrayList<>();
        List<StoredPage> waiting = new ArrayList<>();
        Work current = null;
        for (StoredPage page : pages) {
            if (page.blank()) {
                continue;
            }
            DocTypeClassifier.PageRead read = classifier.read(ClassifyRequest.scoreOnly(
                    version.fileRole(), version.fileName(), page.text(), industryTag, packs));
            page.contentCodes = read.contentCodes();
            boolean stated = read.result().status() == ClassifyResult.Status.CLASSIFIED
                    && read.result().docTypeCode() != null
                    && !"unknown".equalsIgnoreCase(read.result().docTypeCode());
            if (page.index() && !stated) {
                continue;
            }
            if (!page.headingOnly() && read.statedCodes().size() >= 2) {
                current = share(page, read, works, packs, waiting);
                continue;
            }
            if (stated && !page.headingOnly()) {
                Work work = new Work(read.result());
                claim(waiting, work);
                work.pages.add(page);
                works.add(work);
                current = work;
                continue;
            }
            if (page.contentCodes.size() == 1) {
                Work open = latest(works, page.contentCodes.get(0));
                if (open != null) {
                    open.pages.add(page);
                    current = open;
                    continue;
                }
                waiting.add(page);
                continue;
            }
            if (page.headingOnly() && current != null) {
                current.pages.add(page);
            }
        }
        return works;
    }

    /**
     * A page that states two works is a member of each. An open work of that type
     * keeps the page; a work that has not started is recorded from this page.
     */
    private static Work share(StoredPage page, DocTypeClassifier.PageRead read, List<Work> works,
            List<DocTypePack> packs, List<StoredPage> waiting) {
        Work latestOnPage = null;
        for (String code : read.statedCodes()) {
            Work open = latest(works, code);
            if (open == null) {
                open = new Work(resultFor(code, read, packs));
                claim(waiting, open);
                works.add(open);
            }
            if (!open.pages.contains(page)) {
                open.pages.add(page);
            }
            latestOnPage = open;
        }
        return latestOnPage;
    }

    private static ClassifyResult resultFor(String code, DocTypeClassifier.PageRead read,
            List<DocTypePack> packs) {
        ClassifyResult result = read.result();
        if (result.status() == ClassifyResult.Status.CLASSIFIED && code.equals(result.docTypeCode())) {
            return result;
        }
        DocTypePack pack = packFor(packs, code);
        String version = pack == null || pack.version() == null || pack.version().isBlank()
                ? "1.0.0" : pack.version();
        return new ClassifyResult(code, code, version, 1.0,
                List.of("contents state " + code),
                ClassifyResult.Status.CLASSIFIED, "deterministic");
    }

    /** Pages held back because their contents belong to a work that had not started. */
    private static void claim(List<StoredPage> waiting, Work work) {
        List<StoredPage> claimed = new ArrayList<>();
        waiting.removeIf(page -> {
            if (page.contentCodes.size() == 1 && work.type().equals(page.contentCodes.get(0))) {
                claimed.add(page);
                return true;
            }
            return false;
        });
        work.pages.addAll(claimed);
    }

    private static Work latest(List<Work> works, String type) {
        for (int i = works.size() - 1; i >= 0; i--) {
            if (type.equals(works.get(i).type())) {
                return works.get(i);
            }
        }
        return null;
    }

    private static void record(DocTypeClassifier classifier, Connection connection, long versionId,
            String fileRole, String fileName, String industryTag, List<DocTypePack> packs,
            Work work) throws SQLException {
        classifier.record(connection, new ClassifyRequest(
                versionId, fileRole, fileName, work.text(), industryTag, packs, true), work.result);
    }

    private static void insertWork(Connection connection, DocumentRepository docs,
            DocumentRepository.DocumentVersionRow file, long mandateId, String industryTag,
            List<DocTypePack> packs, DocTypeClassifier classifier, String fileName,
            String rawMetadata, Work work) throws SQLException {
        DocTypePack pack = packFor(packs, work.type());
        String family = pack == null || pack.family() == null || pack.family().isBlank()
                ? "other" : pack.family();
        boolean amendment = AMENDMENT.matcher(work.text()).find();
        String logicalName = amendment ? fileName + " Amendment" : fileName + " " + work.type();
        String label = amendment ? "Amendment" : "v1";
        String role = pack != null && pack.fileRole() != null && !pack.fileRole().isBlank()
                ? pack.fileRole() : file.fileRole();
        int firstPage = work.pages.stream().mapToInt(page -> page.pageIndex).min().orElse(1);
        long documentId = docs.insertDocument(mandateId, logicalName, family, null);
        long versionId = docs.insertDocumentVersion(
                documentId, label, file.fileName(), file.fileHash() + "#p" + firstPage,
                file.fileExt(), role, work.pages.size(), false, file.sourceFileId(), rawMetadata);
        docs.setCurrentVersion(documentId, versionId);
        record(classifier, connection, versionId, role, file.fileName(), industryTag, packs, work);
        docs.replaceMemberPages(documentId, pageIds(work));
    }

    private static DocTypePack packFor(List<DocTypePack> packs, String code) {
        for (DocTypePack pack : packs) {
            if (pack.code().equals(code)) {
                return pack;
            }
        }
        return null;
    }

    private static List<Long> pageIds(Work work) {
        List<Long> ids = new ArrayList<>();
        for (StoredPage page : work.pages) {
            ids.add(page.pageId);
        }
        return ids;
    }

    /**
     * Drops works previously recorded for this file so a forced classify does not
     * leave a second copy. The file's own document stays; it is the first work.
     */
    private static void removeSiblingWorks(Connection connection, long fileDocumentId,
            long versionId) throws SQLException {
        List<Long> siblings = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dp.document_id FROM document_page dp"
                        + " JOIN page p ON p.page_id = dp.page_id"
                        + " WHERE p.document_version_id = ? AND dp.document_id <> ?")) {
            ps.setLong(1, versionId);
            ps.setLong(2, fileDocumentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    siblings.add(rs.getLong(1));
                }
            }
        }
        for (Long sibling : siblings) {
            List<Long> versions = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT document_version_id FROM document_version WHERE document_id = ?")) {
                ps.setLong(1, sibling);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        versions.add(rs.getLong(1));
                    }
                }
            }
            for (Long extra : versions) {
                deleteQuiet(connection,
                        "DELETE FROM doc_classification WHERE document_version_id = ?", extra);
                deleteQuiet(connection,
                        "DELETE FROM document_relation WHERE from_document_version_id = ?"
                                + " OR to_document_version_id = ?", extra, extra);
            }
            deleteQuiet(connection, "DELETE FROM document_page WHERE document_id = ?", sibling);
            deleteQuiet(connection,
                    "UPDATE document SET current_version_id = NULL WHERE document_id = ?", sibling);
            deleteQuiet(connection, "DELETE FROM document_version WHERE document_id = ?", sibling);
            deleteQuiet(connection, "DELETE FROM document WHERE document_id = ?", sibling);
        }
    }

    private static void deleteQuiet(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static void deleteQuiet(Connection connection, String sql, long left, long right)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, left);
            ps.setLong(2, right);
            ps.executeUpdate();
        }
    }

    private static String logicalName(Connection connection, long documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT logical_name FROM document WHERE document_id = ?")) {
            ps.setLong(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "Document";
            }
        }
    }

    private static String rawMetadata(Connection connection, long versionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT raw_metadata FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<StoredPage> loadPages(Connection connection, long versionId)
            throws SQLException {
        LinkedHashMap<Long, StoredPage> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.page_id, p.page_index, b.block_index, b.block_kind,"
                        + " COALESCE(NULLIF(b.text_norm, ''), b.text_raw) AS block_text"
                        + " FROM page p"
                        + " LEFT JOIN block b ON b.page_id = p.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index,"
                        + " COALESCE(b.reading_order, b.block_index), b.block_index")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pageId = rs.getLong("page_id");
                    int pageIndex = rs.getInt("page_index");
                    StoredPage page = byId.computeIfAbsent(pageId,
                            id -> new StoredPage(pageId, pageIndex));
                    String kind = rs.getString("block_kind");
                    if (kind == null) {
                        continue;
                    }
                    String text = rs.getString("block_text");
                    boolean table = "table".equalsIgnoreCase(kind);
                    if ((text == null || text.isBlank()) && !table) {
                        continue;
                    }
                    page.blocks.add(new Block(rs.getInt("block_index"), kind,
                            text == null ? "" : text.trim()));
                }
            }
        }
        appendCells(connection, versionId, byId);
        return List.copyOf(byId.values());
    }

    /** Table cells are contents of the page, in row order, on the table's block. */
    private static void appendCells(Connection connection, long versionId,
            LinkedHashMap<Long, StoredPage> byId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT b.page_id, b.block_index, tc.text_raw"
                        + " FROM table_cell tc"
                        + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                        + " JOIN block b ON b.block_id = tg.block_id"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, b.block_index, tc.row_index, tc.col_index")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StoredPage page = byId.get(rs.getLong("page_id"));
                    if (page == null) {
                        continue;
                    }
                    int blockIndex = rs.getInt("block_index");
                    for (Block block : page.blocks) {
                        if (block.index == blockIndex) {
                            block.add(rs.getString("text_raw"));
                            break;
                        }
                    }
                }
            }
        }
    }

    private static final class Work {
        private final ClassifyResult result;
        private final List<StoredPage> pages = new ArrayList<>();

        private Work(ClassifyResult result) {
            this.result = result;
        }

        private String type() {
            return result.docTypeCode();
        }

        private String text() {
            StringBuilder text = new StringBuilder();
            for (StoredPage page : pages) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(page.text());
            }
            return text.toString();
        }
    }

    private static final class StoredPage {
        private final long pageId;
        private final int pageIndex;
        private final List<Block> blocks = new ArrayList<>();
        private List<String> contentCodes = List.of();

        private StoredPage(long pageId, int pageIndex) {
            this.pageId = pageId;
            this.pageIndex = pageIndex;
        }

        private boolean blank() {
            return blocks.isEmpty();
        }

        private boolean headingOnly() {
            return !blocks.isEmpty()
                    && blocks.stream().allMatch(block -> "heading".equalsIgnoreCase(block.kind()));
        }

        /** An index or a contents page states no work, even if a word on it is a field name. */
        private boolean index() {
            for (Block block : blocks) {
                String folded = block.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (folded.equals("index") || folded.equals("contents")
                        || folded.equals("table of contents")
                        || folded.startsWith("index:") || folded.startsWith("index ")
                        || folded.startsWith("contents:") || folded.startsWith("contents ")) {
                    return true;
                }
            }
            return false;
        }

        private String text() {
            StringBuilder text = new StringBuilder();
            for (Block block : blocks) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(block.text());
            }
            return text.toString();
        }
    }

    private static final class Block {
        private final int index;
        private final String kind;
        private final StringBuilder text;

        private Block(int index, String kind, String text) {
            this.index = index;
            this.kind = kind;
            this.text = new StringBuilder(text == null ? "" : text);
        }

        private String kind() {
            return kind;
        }

        private String text() {
            return text.toString();
        }

        private void add(String more) {
            if (more == null || more.isBlank()) {
                return;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(more.trim());
        }
    }
}
