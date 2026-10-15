package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
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
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
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
 * Golden M3-04 suite (issue #137): the profile stage identifies the mandate's sites
 * from its documents. Fixture PDFs in a temp {@code workspace.db}, the fake doc LLM
 * client and the fixture extract client; persisted rows are asserted through the
 * mandate QA read API and {@code FactQuery} — never prompts or internals.
 */
class MandateSitesTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack quotationPack;
    private DocTypePack ppaPack;
    private DocTypePack netWorthPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    // ------------------------------------------------------------------ fixture glue

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

    /** Ingest → layout → classify → extract one quotation PDF; returns the version id. */
    private long quotation(String fileName, String logicalName, String projectName,
            String projectLine) throws Exception {
        long versionId = ingestAndLayout(writePdf(fileName,
                "Quotation for Supply of Solar Modules",
                "Vendor: Sri Sai Electricals Pvt Ltd",
                projectLine,
                "Validity: 12/03/2027",
                "Grand Total: Rs. 62,45,000"), logicalName, "quotation");
        classify(versionId, quotationPack);
        ObjectNode fixture = fixture("quotation.vendor");
        ObjectNode fields = fixture.putObject("fields");
        addField(fields, "vendor_name", "Sri Sai Electricals Pvt Ltd",
                "Vendor: Sri Sai Electricals Pvt Ltd");
        addField(fields, "project_name", projectName, projectLine);
        addField(fields, "gross_total", "Rs. 62,45,000", "Grand Total: Rs. 62,45,000");
        extract(versionId, quotationPack, fixture);
        return versionId;
    }

    /** Ingest → layout → classify → extract one PPA PDF; returns the version id. */
    private long ppa(String fileName, String logicalName, String projectLine) throws Exception {
        long versionId = ingestAndLayout(writePdf(fileName,
                "Power Purchase Agreement",
                "This Power Purchase Agreement is executed on 12 March 2024 by and",
                "between the Seller and the Buyer.",
                projectLine,
                "Tariff: Rs. 4.50 per kWh",
                "Contract Capacity: 5.00 MW"), logicalName, "agreement");
        classify(versionId, ppaPack);
        ObjectNode fixture = fixture("agreement.ppa");
        ObjectNode fields = fixture.putObject("fields");
        addField(fields, "seller_name", "Bhagyanagar Solar Power Pvt Ltd",
                "Seller: Bhagyanagar Solar Power Pvt Ltd");
        addField(fields, "tariff", "Rs. 4.50 per kWh", "Tariff: Rs. 4.50 per kWh");
        addField(fields, "capacity_mw", "5.00 MW", "Contract Capacity: 5.00 MW");
        extract(versionId, ppaPack, fixture);
        return versionId;
    }

    /** Ingest → layout → classify → extract one net-worth certificate PDF. */
    private long netWorthCertificate(String fileName, String logicalName) throws Exception {
        long versionId = ingestAndLayout(writePdf(fileName,
                "Certificate of Net Worth",
                "This certificate is issued on 5 May 2025.",
                "Subject: Ramesh Sharma",
                "PAN: AAAPZ1234C",
                "Net Worth: Rs. 10 crore"), logicalName, "certificate");
        classify(versionId, netWorthPack);
        ObjectNode fixture = fixture("identity.promoter_net_worth");
        ObjectNode fields = fixture.putObject("fields");
        addField(fields, "subject_name", "Ramesh Sharma", "Subject: Ramesh Sharma");
        addField(fields, "net_worth", "Rs. 10 crore", "Net Worth: Rs. 10 crore");
        addField(fields, "borrower_pan", "AAAPZ1234C", "PAN: AAAPZ1234C");
        extract(versionId, netWorthPack, fixture);
        return versionId;
    }

    private static ObjectNode fixture(String docTypeCode) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("doc_type_code", docTypeCode);
        return root;
    }

    private static void addField(ObjectNode fields, String key, String value, String quote) {
        ObjectNode field = fields.putObject(key);
        field.put("value", value);
        field.put("quote", quote);
        field.put("page", 1);
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
            var result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    private void extract(long versionId, DocTypePack pack, JsonNode fixture) throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture(pack.code(), fixture);
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, versionId, MANDATE, pack, client, FixtureExtractClient.ID);
        assertThat(outcome.status()).isEqualTo("success");
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static MandateQa.SiteRow siteNamed(MandateQa qa, String name) {
        return qa.sites().stream()
                .filter(s -> name.equals(s.siteName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("site not found: " + name));
    }

    private List<FactRow> factsFor(long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new com.resurgent.tev.doc.db.FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withVersion(versionId));
        }
    }

    private static FactRow factByKey(List<FactRow> rows, String fieldKey) {
        return rows.stream()
                .filter(r -> fieldKey.equals(r.fieldKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fact not found: " + fieldKey));
    }

    // ---------------------------------------------------------------------- tests

    @Test
    void twoSiteMandateYieldsExactlyTwoSitesWithVariantSpellingMergedAndReasoned()
            throws Exception {
        long quoteA = quotation("quotation_a.pdf", "Quote A - Jaisingpura", "Jaisingpura",
                "Project: Jaisingpura Solar Plant");
        long ppaA = ppa("ppa_a.pdf", "PPA A - Jaisingh Pura",
                "Project: Jaisingh Pura Solar Project");
        long quoteB = quotation("quotation_b.pdf", "Quote B - Karawal Nagar", "Karawal Nagar",
                "Project: Karawal Nagar Solar Park");
        long ppaB = ppa("ppa_b.pdf", "PPA B - Karawal Nagar",
                "Project: Karawal Nagar Solar Park");

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"same\": false}"));

        MandateQa qa = qa();
        assertThat(qa.sites()).hasSize(2);

        MandateQa.SiteRow siteA = siteNamed(qa, "Jaisingpura");
        assertThat(siteA.aliases())
                .extracting(MandateQa.SiteAliasRow::aliasText)
                .containsExactlyInAnyOrder("Jaisingpura", "Jaisingh Pura");
        MandateQa.SiteAliasRow variant = siteA.aliases().stream()
                .filter(a -> "Jaisingh Pura".equals(a.aliasText()))
                .findFirst().orElseThrow();
        assertThat(variant.documentVersionId()).isEqualTo(ppaA);
        assertThat(variant.matchScore()).isGreaterThanOrEqualTo(0.8).isLessThan(1.0);
        assertThat(variant.matchReason()).startsWith("loose score");
        assertThat(siteA.documents())
                .extracting(MandateQa.SiteDocumentRow::fileName)
                .containsExactlyInAnyOrder("quotation_a.pdf", "ppa_a.pdf");
        assertThat(siteA.documents())
                .extracting(MandateQa.SiteDocumentRow::documentVersionId)
                .containsExactlyInAnyOrder(quoteA, ppaA);

        MandateQa.SiteRow siteB = siteNamed(qa, "Karawal Nagar");
        assertThat(siteB.aliases()).hasSize(2);
        assertThat(siteB.documents())
                .extracting(MandateQa.SiteDocumentRow::fileName)
                .containsExactlyInAnyOrder("quotation_b.pdf", "ppa_b.pdf");
        assertThat(siteB.documents())
                .extracting(MandateQa.SiteDocumentRow::documentVersionId)
                .containsExactlyInAnyOrder(quoteB, ppaB);
    }

    @Test
    void siteFactsAreTaggedWithTheirSiteAndMandateWideFactsCarryNone() throws Exception {
        quotation("quotation_c.pdf", "Quote C - Bhagyanagar", "Bhagyanagar",
                "Project: Bhagyanagar Solar Plant");
        long ppaC = ppa("ppa_c.pdf", "PPA C - Bhagyanagar",
                "Project: Bhagyanagar Solar Plant");
        long cert = netWorthCertificate("net_worth_c.pdf", "Promoter NW - PAN");

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"same\": false}"));

        MandateQa qa = qa();
        assertThat(qa.sites()).hasSize(1);
        long siteId = siteNamed(qa, "Bhagyanagar").siteId();

        // The PPA's tariff is about the site: tagged with it through the read API.
        FactRow tariff = factByKey(factsFor(ppaC), "tariff");
        assertThat(tariff.siteId()).isEqualTo(siteId);
        assertThat(tariff.siteName()).isEqualTo("Bhagyanagar");

        // A mandate-wide fact (the borrower's PAN) is about no site.
        FactRow pan = factByKey(factsFor(cert), "borrower_pan");
        assertThat(pan.siteId()).isNull();
        assertThat(pan.siteName()).isNull();
        FactRow netWorth = factByKey(factsFor(cert), "net_worth");
        assertThat(netWorth.siteId()).isNull();
    }
}
