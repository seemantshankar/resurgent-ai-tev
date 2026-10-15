package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;
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
 * Golden M3-06 suite (issue #139): passages from the stored parse markdown with their
 * per-chapter facts. Assertions go through the mandate QA read API — never prompts or
 * internals — and the same fixture route as M2: a born-digital PDF in a temp
 * {@code workspace.db}, the fake doc LLM client and the fixture extract client.
 */
class PassagesAndChapterFactsTest {

    private static final long MANDATE = 1L;

    /** Chapter 1 of the fixture DPR: project overview carrying the land and capacity data. */
    private static final String DPR_PAGE_1 = """
            # 1. Introduction and Project Site

            The project is a 10 MW solar photovoltaic power plant.

            Capacity: 10 MW

            Location: Village Jaisingpura, District Ahmednagar

            Land area: 4.5 acres

            Survey Numbers: 112/1, 113/2
            """;

    /** Chapter 2 of the fixture DPR: the plant and machinery list. */
    private static final String DPR_PAGE_2 = """
            # 2. Plant and Machinery

            The main plant and machinery comprises the following items.

            Module Mounting Structure - 400 sets

            Inverter 100 kW - 10 nos

            SCADA System - 1 nos
            """;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack dprPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        dprPack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");
    }

    @Test
    void twoChaptersYieldTwoPassagesWithPageRangesTopicAndSummary() throws Exception {
        long versionId = ingestDpr();
        seedStoredParse(versionId, DPR_PAGE_1, DPR_PAGE_2);
        classify(versionId);

        AssessStage.StageResult result = new AssessPassagesRunner().run(
                MANDATE, dbPath, null);

        assertThat(result.stage()).isEqualTo("assess_passages");
        List<MandateQa.PassageRow> passages = qa().passages();
        assertThat(passages).hasSize(2);

        MandateQa.PassageRow first = passages.get(0);
        assertThat(first.documentVersionId()).isEqualTo(versionId);
        assertThat(first.heading()).isEqualTo("1. Introduction and Project Site");
        assertThat(first.topic()).isEqualTo("project_overview");
        assertThat(first.pageFrom()).isEqualTo(1);
        assertThat(first.pageTo()).isEqualTo(1);
        assertThat(first.pageIndex()).isEqualTo(1);
        assertThat(first.verbatim()).contains("Land area: 4.5 acres");
        assertThat(first.summary()).isNotBlank();

        MandateQa.PassageRow second = passages.get(1);
        assertThat(second.heading()).isEqualTo("2. Plant and Machinery");
        assertThat(second.topic()).isEqualTo("plant_and_machinery");
        assertThat(second.pageFrom()).isEqualTo(2);
        assertThat(second.pageTo()).isEqualTo(2);
        assertThat(second.pageIndex()).isEqualTo(2);
        assertThat(second.verbatim()).contains("Module Mounting Structure - 400 sets");
        assertThat(second.summary()).isNotBlank();
    }

    // --- fixture helpers ---

    private long ingestDpr() throws Exception {
        Path pdf = writeDprPdf();
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "DPR - Bhagyanagar", "v1", "dpr", "dpr"),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private Path writeDprPdf() throws Exception {
        Path pdf = tempDir.resolve("golden_dpr.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPage(doc,
                    "Detailed Project Report",
                    "Bhagyanagar Solar Power Project",
                    "1. Introduction and Project Site",
                    "Capacity: 10 MW",
                    "Location: Village Jaisingpura, District Ahmednagar",
                    "Land area: 4.5 acres",
                    "Survey Numbers: 112/1, 113/2");
            addPage(doc,
                    "2. Plant and Machinery",
                    "Module Mounting Structure - 400 sets",
                    "Inverter 100 kW - 10 nos",
                    "SCADA System - 1 nos");
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void addPage(PDDocument doc, String... lines) throws Exception {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
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

    /** The stored parse output M2 leaves behind — passages are split from this, not the PDF. */
    private void seedStoredParse(long versionId, String... pages) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new CloudParsePageRepository(db.connection()).replacePages(
                    versionId, "parse-job-dpr", "agentic", List.of(pages), List.of(pages));
        }
    }

    private void classify(long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(dprPack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo("report.dpr");
        }
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }
}
