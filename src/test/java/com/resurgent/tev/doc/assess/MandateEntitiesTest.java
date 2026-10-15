package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden M3-03 suite (issue #136): the profile stage identifies the mandate's
 * entities from MOA/AOA and the mandate letter. Asserts persisted rows through
 * the mandate QA read API — never prompts or internals — with a deterministic
 * document-aware fake over the doc LLM seam.
 */
class MandateEntitiesTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack netWorthPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    @Test
    void mandateLetterAndMoaYieldOneBorrowerAndTwoPromoters() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");

        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        MandateQa qa = qa();
        assertThat(qa.entities()).hasSize(3);

        MandateQa.EntityRow borrower = entityWithRole(qa, "borrower");
        assertThat(borrower.name()).isEqualTo("ABC Pvt Ltd");
        assertThat(borrower.reason()).isNotBlank();
        assertThat(borrower.pageIndexes()).contains(1);
        // "ABC Pvt Ltd" and "ABC Private Limited" across documents are ONE entity
        // carrying both aliases, each with its source page.
        assertThat(borrower.aliases()).extracting(MandateQa.AliasRow::alias)
                .containsExactly("ABC Pvt Ltd", "ABC Private Limited");
        assertThat(borrower.aliases()).allSatisfy(
                alias -> assertThat(alias.pageIndex()).isNotNull());

        List<MandateQa.EntityRow> promoters = qa.entities().stream()
                .filter(e -> "promoter".equals(e.role()))
                .toList();
        assertThat(promoters).hasSize(2);
        assertThat(promoters).extracting(MandateQa.EntityRow::name)
                .containsExactly("RAMESH KUMAR SHARMA", "Suresh Kumar Sharma");
        assertThat(promoters).allSatisfy(promoter -> {
            assertThat(promoter.reason()).isNotBlank();
            assertThat(promoter.pageIndexes()).contains(2);
        });

        // The fixture states every date: the profile stage's date pass stays
        // deterministic and no date call blurs the entity pass.
        assertThat(qa.documents()).allSatisfy(
                doc -> assertThat(doc.dateSource()).isEqualTo("stated"));
    }

    // --- fixtures ---

    private Path mandateLetterPdf() throws Exception {
        return writePdf("mandate_letter.pdf",
                List.of(
                        "Mandate Letter",
                        "To the Board of Directors, ABC Pvt Ltd (the Borrower)",
                        "Sub: Proposed financing of a 10 MW solar power project.",
                        "Date: 1 January 2025"),
                List.of(
                        "The promoters of the Borrower are:",
                        "RAMESH KUMAR SHARMA",
                        "Suresh Kumar Sharma"));
    }

    private Path moaExtractPdf() throws Exception {
        return writePdf("moa_extract.pdf",
                List.of(
                        "Memorandum of Association",
                        "ABC Private Limited is a company incorporated under the Companies Act, 2013",
                        "Date of incorporation: 15 May 2019"));
    }

    @SafeVarargs
    private Path writePdf(String name, List<String>... pages) throws Exception {
        Path pdf = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 720;
                    for (String line : lines) {
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(72, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 18;
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    // --- helpers ---

    private long ingestAndLayout(Path pdf, String logicalName, String fileRole)
            throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static MandateQa.EntityRow entityWithRole(MandateQa qa, String role) {
        return qa.entities().stream()
                .filter(e -> role.equals(e.role()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Deterministic fake over the doc LLM seam: replies per document, recognised
     * by a marker in the sent text, so call order never couples the fixture.
     */
    private static final class DocumentReplyLlm implements DocLlmClient {

        private static final Map<String, String> REPLIES = Map.of(
                "Mandate Letter", """
                        {"entities": [
                          {"name": "ABC Pvt Ltd", "role": "borrower",
                           "quote": "ABC Pvt Ltd (the Borrower)",
                           "reason": "The mandate letter names it as the borrower"},
                          {"name": "RAMESH KUMAR SHARMA", "role": "promoter",
                           "quote": "RAMESH KUMAR SHARMA",
                           "reason": "Listed as a promoter of the borrower"},
                          {"name": "Suresh Kumar Sharma", "role": "promoter",
                           "quote": "Suresh Kumar Sharma",
                           "reason": "Listed as a promoter of the borrower"}]}""",
                "Memorandum of Association", """
                        {"entities": [
                          {"name": "ABC Private Limited", "role": "borrower",
                           "quote": "ABC Private Limited is a company incorporated",
                           "reason": "The MOA names the incorporated company"}]}""");

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            for (Map.Entry<String, String> reply : REPLIES.entrySet()) {
                if (user.contains(reply.getKey())) {
                    return new Completion(reply.getValue(), "fake-model", 100, 50, 0.002, 12);
                }
            }
            return new Completion("{\"entities\": []}", "fake-model", 100, 50, 0.002, 12);
        }
    }
}
