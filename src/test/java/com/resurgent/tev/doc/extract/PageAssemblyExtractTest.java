package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hybrid and digital documents are extracted from assembled pages and table
 * grids. An explicit LlamaExtract id does not upload the original when the
 * cloud job does not already cover every page.
 */
class PageAssemblyExtractTest {

    private static final String PACK = """
            code: demo.page_assembly
            version: 1.0.0
            family: report
            file_role: report
            fields:
              - key: seller_name
                path: commercial > Offtake > Seller
                path_root: commercial
                value_kind: text
                required: false
                prompt_hint: Seller name
                synonyms: ["Seller"]
            tables:
              - name: plant_and_machinery
                path: technical > Plant and machinery > Item
                path_root: technical
                key_column: description
                fields:
                  - key: description
                    value_kind: text
                  - key: qty
                    value_kind: quantity
                  - key: amount
                    value_kind: money
              - name: headline_figures
                path: economic > Audited Financials > Headline Figure
                path_root: economic
                period_key: financial_year
                fields:
                  - key: financial_year
                    value_kind: text
                  - key: revenue
                    value_kind: money
            clauses:
              open_extraction: false
              types:
                - key: termination
                  label: Termination
                  aliases: ["Termination"]
            passages:
              - topic: project_overview
                heading_terms: ["project overview"]
                fields: [seller_name]
            """;

    @TempDir
    Path tempDir;

    @Test
    void pageAssemblyReadsFieldsSchedulesClausesAndPassages() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        long versionId = stage(dbPath);
        DocTypePack pack = PackLoader.load(
                new ByteArrayInputStream(PACK.getBytes(StandardCharsets.UTF_8)), "test");

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, versionId, 1L, pack, new DocLlmExtractClient(), "test");

        assertThat(outcome.status()).isEqualTo("success");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            assertThat(value(connection, "seller_name")).isEqualTo("North Wind Energy");
            assertThat(lineKey(connection, "plant_and_machinery.qty")).isEqualTo("Inverter");
            assertThat(value(connection, "plant_and_machinery.qty")).isEqualTo("2");
            assertThat(period(connection, "revenue")).isEqualTo("FY2024-25");
            assertThat(count(connection, "SELECT COUNT(*) FROM clause WHERE heading LIKE '%TERMINATION%'"))
                    .isEqualTo(1);
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM passage WHERE topic = 'project_overview'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void explicitLlamaExtractDoesNotUploadAHybridOriginal() throws Exception {
        Path dbPath = tempDir.resolve("llama.db");
        long versionId = stage(dbPath);

        DocExtractRunner.Result result = DocExtractRunner.run(
                1L, versionId, dbPath, new BlobStore(tempDir.resolve("blobs")),
                "agreement.ppa", "llama-extract", null, null, true, false);

        assertThat(result.extractorId()).isEqualTo(DocLlmExtractClient.ID);
        assertThat(result.factsWritten()).isGreaterThan(0);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(),
                    "SELECT COUNT(*) FROM clause WHERE heading LIKE '%TERMINATION%'"))
                    .isEqualTo(1);
        }
    }

    private long stage(Path dbPath) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository docs = new DocumentRepository(connection);
            WorkspaceRepository repo = new WorkspaceRepository(connection);
            long sourceId = repo.insertSourceFile(1L, "hybrid.pdf", "hash-hybrid", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(1L, "Hybrid", "report", null);
            long versionId = docs.insertDocumentVersion(documentId, "v1", "hybrid.pdf",
                    "hash-hybrid", ".pdf", "report", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            text(docs, pageId, 0, "Seller: North Wind Energy");
            text(docs, pageId, 1, "12. TERMINATION");
            text(docs, pageId, 2, "The procurer may terminate this agreement on notice.");
            text(docs, pageId, 3, "Project Overview");
            text(docs, pageId, 4, "The plant is a solar project.");
            long plant = docs.insertBlock(pageId, 5, "table", null, null, null, null,
                    "plant", "plant", 5, "test");
            long plantGrid = docs.insertTableGrid(plant, 2, 3, 1);
            cell(docs, plantGrid, 0, 0, "Description", true);
            cell(docs, plantGrid, 0, 1, "Qty", true);
            cell(docs, plantGrid, 0, 2, "Amount", true);
            cell(docs, plantGrid, 1, 0, "Inverter", false);
            cell(docs, plantGrid, 1, 1, "2", false);
            cell(docs, plantGrid, 1, 2, "100", false);
            long years = docs.insertBlock(pageId, 6, "table", null, null, null, null,
                    "years", "years", 6, "test");
            long yearGrid = docs.insertTableGrid(years, 2, 2, 1);
            cell(docs, yearGrid, 0, 0, "Financial Year", true);
            cell(docs, yearGrid, 0, 1, "Revenue", true);
            cell(docs, yearGrid, 1, 0, "FY2024-25", false);
            cell(docs, yearGrid, 1, 1, "10", false);
            return versionId;
        }
    }

    private static void text(DocumentRepository docs, long pageId, int index, String line)
            throws Exception {
        docs.insertBlock(pageId, index, "paragraph", null, null, null, null,
                line, line, index, "test");
    }

    private static void cell(DocumentRepository docs, long grid, int row, int col, String text,
            boolean header) throws Exception {
        docs.insertTableCell(grid, row, col, text, null, null, null, null, header);
    }

    private static String value(Connection connection, String key) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value_text FROM fact WHERE field_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(key).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String lineKey(Connection connection, String key) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT line_key FROM fact WHERE field_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(key).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String period(Connection connection, String key) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.source_text FROM fact_evidence e"
                        + " JOIN fact f ON f.fact_id = e.fact_id"
                        + " WHERE f.field_key = ? AND e.role = 'period'")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("period for " + key).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
