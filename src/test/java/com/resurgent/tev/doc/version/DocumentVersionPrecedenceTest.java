package com.resurgent.tev.doc.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M4-04: Document version precedence ladder and relation resolution (#166, ADR 0028 §1).
 */
class DocumentVersionPrecedenceTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocumentIngestService ingestService;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        ingestService = new DocumentIngestService();
    }

    @Test
    void laterUploadNeverOverridesEarlierStatedDocumentDate() throws Exception {
        // Version 1: dated 2026-06-15, ingested earlier
        Path pdf1 = writePdf("quotation_june15.pdf", List.of("Quotation", "Date: 15 June 2026", "Total: Rs. 10,00,000"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf1, MANDATE, "Vendor Quotation - Inverter", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res1.documentVersionId(), "2026-06-15");

        // Version 2: dated 2026-06-10 (older date!), uploaded later
        Path pdf2 = writePdf("quotation_june10.pdf", List.of("Quotation", "Date: 10 June 2026", "Total: Rs. 9,50,000"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf2, MANDATE, "Vendor Quotation - Inverter", "v2", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res2.documentVersionId(), "2026-06-10");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        // Tier 2: stated document date 2026-06-15 beats 2026-06-10 even though v2 was uploaded later
        long currentVersionId = getCurrentVersionId("Vendor Quotation - Inverter");
        assertThat(currentVersionId).isEqualTo(res1.documentVersionId());

        // Relation: v1 supersedes v2
        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(res1.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(res2.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("supersedes");
        });
    }

    @Test
    void newerStatedDateSupersedesOlderStatedDate() throws Exception {
        Path pdf1 = writePdf("quote_may.pdf", List.of("Quotation", "Date: 01 May 2026"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf1, MANDATE, "Turbine Quotation", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res1.documentVersionId(), "2026-05-01");

        Path pdf2 = writePdf("quote_june.pdf", List.of("Quotation", "Date: 01 June 2026"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf2, MANDATE, "Turbine Quotation", "v2", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res2.documentVersionId(), "2026-06-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        long currentVersionId = getCurrentVersionId("Turbine Quotation");
        assertThat(currentVersionId).isEqualTo(res2.documentVersionId());

        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(res2.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(res1.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("supersedes");
        });
    }

    @Test
    void selfDeclarationAmendmentNumberTakesPrecedenceOverStatedDate() throws Exception {
        // Base agreement dated 2026-06-01
        Path basePdf = writePdf("ppa_base.pdf", List.of("Power Purchase Agreement", "Date: 01 June 2026"));
        var baseRes = ingestService.ingest(new DocumentIngestService.IngestRequest(
                basePdf, MANDATE, "PPA - Solar", "base", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(baseRes.documentVersionId(), "2026-06-01");

        // Amendment No. 1 dated 2026-06-01 (same stated date)
        Path amendPdf = writePdf("ppa_amend1.pdf", List.of("Amendment No. 1 to PPA", "Date: 01 June 2026"));
        var amendRes = ingestService.ingest(new DocumentIngestService.IngestRequest(
                amendPdf, MANDATE, "PPA - Solar - Amendment 1", "Amendment No. 1", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(amendRes.documentVersionId(), "2026-06-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        // Amendment should link to base agreement with relation_kind = 'amends'
        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(amendRes.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(baseRes.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("amends");
        });
    }

    @Test
    void identicalDuplicateFileRecordedAsDuplicateOf() throws Exception {
        Path pdf = writePdf("same_report.pdf", List.of("DPR Report", "Exact identical content"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf, MANDATE, "DPR First Upload", "v1", "dpr", "dpr"), dbPath, blobs);

        // Uploading exact same file under a different name
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf, MANDATE, "DPR Second Upload", "v1", "dpr", "dpr"), dbPath, blobs);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.relationKind()).isEqualTo("duplicate_of");
            assertThat(List.of(rel.fromVersionId(), rel.toVersionId()))
                    .containsExactlyInAnyOrder(res1.documentVersionId(), res2.documentVersionId());
        });
    }

    @Test
    void missingDocumentDatesFallBackToUploadDate() throws Exception {
        Path pdf1 = writePdf("spec_first.pdf", List.of("Technical Spec", "No date mentioned"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf1, MANDATE, "Civil Spec", "v1", "technical_report", "technical_report"), dbPath, blobs);

        Path pdf2 = writePdf("spec_second.pdf", List.of("Technical Spec", "Updated content, no date"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf2, MANDATE, "Civil Spec", "v2", "technical_report", "technical_report"), dbPath, blobs);

        // Neither version has a stated document_date.
        // Ingested_at should break the tie: res2 uploaded after res1
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        long currentVersionId = getCurrentVersionId("Civil Spec");
        assertThat(currentVersionId).isEqualTo(res2.documentVersionId());

        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(res2.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(res1.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("supersedes");
        });
    }

    @Test
    void multipleAmendmentsHierarchyChain() throws Exception {
        Path basePdf = writePdf("loan_base.pdf", List.of("Facility Agreement", "Date: 01 Jan 2026"));
        var baseRes = ingestService.ingest(new DocumentIngestService.IngestRequest(
                basePdf, MANDATE, "Sanction Agreement", "base", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(baseRes.documentVersionId(), "2026-01-01");

        Path amend1Pdf = writePdf("loan_amend1.pdf", List.of("Amendment 1 to Facility Agreement", "Date: 01 Feb 2026"));
        var amend1Res = ingestService.ingest(new DocumentIngestService.IngestRequest(
                amend1Pdf, MANDATE, "Sanction Agreement - Amendment 1", "Amendment No. 1", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(amend1Res.documentVersionId(), "2026-02-01");

        Path amend2Pdf = writePdf("loan_amend2.pdf", List.of("Amendment 2 to Facility Agreement", "Date: 01 Mar 2026"));
        var amend2Res = ingestService.ingest(new DocumentIngestService.IngestRequest(
                amend2Pdf, MANDATE, "Sanction Agreement - Amendment 2", "Amendment No. 2", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(amend2Res.documentVersionId(), "2026-03-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        List<RelationRow> relations = getRelations();
        // Amendment 1 amends base
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(amend1Res.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(baseRes.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("amends");
        });
        // Amendment 2 amends base
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(amend2Res.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(baseRes.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("amends");
        });
    }

    @Test
    void revisedQuotationAcrossDocumentsSupersedesEarlierQuotation() throws Exception {
        Path pdf1 = writePdf("quote_main.pdf", List.of("Quotation", "Date: 12 April 2026"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf1, MANDATE, "Quotation - Sunbeam main scope", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res1.documentVersionId(), "2026-04-12");

        Path pdf2 = writePdf("quote_revised.pdf", List.of("Quotation", "Date: 20 May 2026"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdf2, MANDATE, "Quotation - Sunbeam revised scope", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res2.documentVersionId(), "2026-05-20");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
        }

        List<RelationRow> relations = getRelations();
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fromVersionId()).isEqualTo(res2.documentVersionId());
            assertThat(rel.toVersionId()).isEqualTo(res1.documentVersionId());
            assertThat(rel.relationKind()).isEqualTo("supersedes");
        });
    }

    // --- helpers ---

    private void setDocumentDate(long documentVersionId, String isoDate) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "UPDATE document_version SET document_date = ? WHERE document_version_id = ?")) {
            ps.setString(1, isoDate);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    private long getCurrentVersionId(String logicalName) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT current_version_id FROM document WHERE logical_name = ? AND mandate_id = ?")) {
            ps.setString(1, logicalName);
            ps.setLong(2, MANDATE);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    record RelationRow(long relationId, long fromVersionId, long toVersionId, String relationKind, String note) {}

    private List<RelationRow> getRelations() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT relation_id, from_document_version_id, to_document_version_id, relation_kind, note"
                             + " FROM document_relation ORDER BY relation_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<RelationRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new RelationRow(
                            rs.getLong("relation_id"),
                            rs.getLong("from_document_version_id"),
                            rs.getLong("to_document_version_id"),
                            rs.getString("relation_kind"),
                            rs.getString("note")));
                }
                return rows;
            }
        }
    }

    private Path writePdf(String fileName, List<String> lines) throws Exception {
        Path path = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
