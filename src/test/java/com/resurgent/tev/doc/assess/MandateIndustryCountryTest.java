package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocQaCommand;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
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
import picocli.CommandLine;

/**
 * Golden M3-05 suite (issue #138): the profile stage records the mandate's industries and
 * country over synthetic born-digital PDFs and the M2 golden fixtures. Asserts persisted
 * rows through the mandate QA read API — never prompts or internals — following the M2
 * pattern: temp {@code workspace.db}, fake doc LLM client, fixture extract client.
 */
class MandateIndustryCountryTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack ppaPack;
    private DocTypePack quotationPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
    }

    @Test
    void solarFixtureSetRecordsSolarIndustryAndIndiaWithPages() throws Exception {
        long ppa = ingestAndLayout(solarPpaPdf(), "PPA - Bhadla", "agreement");
        classify(ppa, ppaPack);
        long quote = ingestAndLayout(fixture("golden_quotation.pdf"),
                "Vendor Quote - Solar", "quotation");
        classify(quote, quotationPack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.loadSidecar(fixture("golden_quotation.expected.json"));
        new ExtractService().extract(dbPath, quote, MANDATE, quotationPack,
                extractClient, "fixture-extract");

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"date\": null}"));

        MandateQa qa = qa();
        assertThat(qa.industries()).hasSize(1);
        MandateQa.IndustryRow solar = qa.industries().get(0);
        assertThat(solar.industry()).isEqualTo("solar power");
        assertThat(solar.isNewIndustry()).isFalse();
        assertThat(solar.reason()).isNotBlank();
        assertThat(solar.pageIndex()).isEqualTo(1);
        assertThat(solar.verbatim()).contains("Solar Power Project");

        assertThat(qa.country()).isNotNull();
        assertThat(qa.country().country()).isEqualTo("India");
        assertThat(qa.country().reason()).isNotBlank();
        assertThat(qa.country().pageIndex()).isEqualTo(1);
        assertThat(qa.country().verbatim()).contains("India");
    }

    @Test
    void hotelFixtureSetRecordsHotelIndustryNeverSolar() throws Exception {
        long quote = ingestAndLayout(fixture("golden_hotel_quotation.pdf"),
                "Vendor Quote - Hotel", "quotation");
        classify(quote, quotationPack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.loadSidecar(fixture("golden_hotel_quotation.expected.json"));
        new ExtractService().extract(dbPath, quote, MANDATE, quotationPack,
                extractClient, "fixture-extract");

        // The quotation pack is industry-generic: the industry is the one model read.
        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient(
                "{\"industry\": \"hotel\", \"quote\": \"Hotel Amenities India Ltd\"}"));

        MandateQa qa = qa();
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactly("hotel")
                .allSatisfy(name -> assertThat(name).doesNotContain("solar"));
        MandateQa.IndustryRow hotel = qa.industries().get(0);
        assertThat(hotel.reason()).isNotBlank();
        assertThat(hotel.pageIndex()).isEqualTo(1);
        assertThat(qa.country()).isNotNull();
        assertThat(qa.country().country()).isEqualTo("India");
        assertThat(qa.country().verbatim()).contains("India");
    }

    @Test
    void unseenIndustryIsRecordedAndMarkedNew() throws Exception {
        long versionId = ingestAndLayout(writePdf("green_hydrogen.pdf",
                "Green Hydrogen Plant - Kandla",
                "This note covers the 20 MW green hydrogen project of the promoter.",
                "Report Date: 5 April 2025",
                "The electrolyser is installed at Kandla, Gujarat, India."),
                "Project Note - Green Hydrogen", "dpr");
        // Left unclassified: no installed pack knows this industry.

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient(
                "{\"industry\": \"green hydrogen\", \"quote\": \"20 MW green hydrogen project\"}"));

        MandateQa qa = qa();
        assertThat(versionId).isPositive();
        assertThat(qa.industries()).hasSize(1);
        MandateQa.IndustryRow row = qa.industries().get(0);
        assertThat(row.industry()).isEqualTo("green hydrogen");
        assertThat(row.isNewIndustry()).isTrue();
        assertThat(row.reason()).isNotBlank();
        assertThat(row.pageIndex()).isEqualTo(1);
        assertThat(row.verbatim()).contains("green hydrogen");
        assertThat(qa.country()).isNotNull();
        assertThat(qa.country().country()).isEqualTo("India");
    }

    @Test
    void hybridProjectYieldsTwoIndustries() throws Exception {
        long versionId = ingestAndLayout(writePdf("hybrid_ppa.pdf",
                "Power Purchase Agreement - Hybrid",
                "The Seller shall supply power from its hybrid wind and solar project",
                "located at Barmer, Rajasthan, India."),
                "PPA - Hybrid", "agreement");
        classify(versionId, ppaPack);

        // Both industries are named in the text: the offline path reads them.
        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactlyInAnyOrder("solar power", "wind power");
        assertThat(qa.industries()).allSatisfy(industry -> {
            assertThat(industry.isNewIndustry()).isFalse();
            assertThat(industry.reason()).isNotBlank();
            assertThat(industry.pageIndex()).isEqualTo(1);
            assertThat(industry.verbatim()).isNotBlank();
        });
        assertThat(qa.country()).isNotNull();
        assertThat(qa.country().country()).isEqualTo("India");
    }

    @Test
    void reassessmentReplacesIndustriesAndCountry() throws Exception {
        long versionId = ingestAndLayout(solarPpaPdf(), "PPA - Bhadla", "agreement");
        classify(versionId, ppaPack);

        FakeDocLlmClient fake = new FakeDocLlmClient("{\"date\": null}");
        DocAssessRunner.run(MANDATE, dbPath, fake);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM mandate_industry_record"))
                    .isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM mandate_country_record"))
                    .isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'mandate_industry'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'mandate_country'")).isEqualTo(1);
        }
        assertThat(qa().industries()).hasSize(1);
        assertThat(qa().country().country()).isEqualTo("India");
    }

    @Test
    void qaCommandShowsIndustriesAndCountryInTextAndJson() throws Exception {
        long versionId = ingestAndLayout(solarPpaPdf(), "PPA - Bhadla", "agreement");
        classify(versionId, ppaPack);
        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"date\": null}"));

        Run text = qaRun(false);
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("solar power")
                .contains("industries: 1")
                .contains("India");

        Run json = qaRun(true);
        assertThat(json.exitCode()).isZero();
        JsonNode root = new ObjectMapper().readTree(json.out());
        JsonNode industry = root.get("industries").get(0);
        assertThat(industry.get("industry").asText()).isEqualTo("solar power");
        assertThat(industry.get("is_new_industry").asBoolean()).isFalse();
        assertThat(industry.get("reason").asText()).isNotBlank();
        assertThat(industry.get("page_index").asInt()).isEqualTo(1);
        assertThat(root.get("country").get("country").asText()).isEqualTo("India");
        assertThat(root.get("country").get("page_index").asInt()).isEqualTo(1);
    }

    private record Run(int exitCode, String out) {}

    private Run qaRun(boolean json) {
        CommandLine cmd = new CommandLine(new DocQaCommand());
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(out));
        List<String> args = new ArrayList<>(
                List.of("--mandate", Long.toString(MANDATE), "--db", dbPath.toString()));
        if (json) {
            args.add("--json");
        }
        Integer exit = cmd.execute(args.toArray(String[]::new));
        return new Run(exit == null ? -1 : exit, out.toString());
    }

    private Path solarPpaPdf() throws Exception {
        return writePdf("solar_ppa.pdf",
                "Power Purchase Agreement",
                "This Power Purchase Agreement is executed on 12 March 2024 by and",
                "between the Seller and the Buyer.",
                "The Seller shall supply power from the 50 MW Solar Power Project",
                "at Bhadla, Rajasthan, India.");
    }

    private Path writePdf(String name, String... lines) throws Exception {
        Path pdf = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
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
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private long ingestAndLayout(Path pdf, String logicalName, String fileRole) throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private void classify(long versionId, DocTypePack pack) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static Path fixture(String name) {
        Path p = Path.of("src/test/resources/docfixtures", name);
        if (Files.exists(p)) {
            return p;
        }
        return Path.of("docfixtures", name);
    }

    private static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
