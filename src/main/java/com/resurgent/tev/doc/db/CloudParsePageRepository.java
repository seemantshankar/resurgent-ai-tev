package com.resurgent.tev.doc.db;

import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

public final class CloudParsePageRepository {

    private final Connection connection;

    public CloudParsePageRepository(Connection connection) {
        this.connection = connection;
    }

    public void replacePages(long documentVersionId, String parseJobId, String parseTier,
            List<String> markdownPages, List<String> textPages) throws SQLException {
        if (parseJobId == null || parseJobId.isBlank()) {
            throw new IllegalArgumentException("parseJobId is required");
        }
        List<String> markdown = markdownPages == null ? List.of() : markdownPages;
        List<String> text = textPages == null ? List.of() : textPages;
        int pageCount = Math.max(markdown.size(), text.size());
        if (pageCount == 0) {
            throw new IllegalArgumentException("parse job contains no pages");
        }

        Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        try {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM cloud_parse_page WHERE document_version_id = ?")) {
                delete.setLong(1, documentVersionId);
                delete.executeUpdate();
            }

            String parsedAt = Timestamps.now();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cloud_parse_page (document_version_id, page_index, parse_job_id,"
                            + " parse_tier, page_markdown, page_text, parsed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < pageCount; i++) {
                    insert.setLong(1, documentVersionId);
                    insert.setInt(2, i + 1);
                    insert.setString(3, parseJobId);
                    insert.setString(4, parseTier);
                    insert.setString(5, pageAt(markdown, i));
                    insert.setString(6, pageAt(text, i));
                    insert.setString(7, parsedAt);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            if (savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException e) {
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            throw e;
        }
    }

    public String loadPageText(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_markdown, page_text FROM cloud_parse_page"
                        + " WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                return preferredText(rs.getString(1), rs.getString(2));
            }
        }
    }

    public List<String> loadTextPages(long documentVersionId) throws SQLException {
        List<String> pages = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_markdown, page_text FROM cloud_parse_page"
                        + " WHERE document_version_id = ? ORDER BY page_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = preferredText(rs.getString(1), rs.getString(2));
                    if (text.isBlank()) {
                        text = rs.getString(2) == null ? "" : rs.getString(2);
                    }
                    if (!text.isBlank()) {
                        pages.add(text);
                    }
                }
            }
        }
        return List.copyOf(pages);
    }

    private static String pageAt(List<String> pages, int index) {
        return index < pages.size() && pages.get(index) != null ? pages.get(index) : "";
    }

    private static String preferredText(String markdown, String text) {
        if (markdown != null && !markdown.isBlank()) {
            return markdown;
        }
        return text == null ? "" : text;
    }
}
