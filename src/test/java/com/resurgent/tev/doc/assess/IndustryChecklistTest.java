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
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Golden M3-11 suite (issue #144): industry checklists keyed by industry and country,
 * multi-industry mandates and the AI-decided Not applicable status. Fixture PDFs in a
 * temp {@code workspace.db}, the fake doc LLM client and the fixture extract client;
 * persisted rows are asserted through the mandate QA read API — never prompts or
 * internals.
 */
class IndustryChecklistTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack quotationPack;
    private DocTypePack ppaPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
    }

    // --- AC3: a brand-new industry gets a starter checklist --------------------

    @Test
    void newIndustryGetsAStarterChecklistLabelledAndEveryItemWithABasis() throws Exception {
        ingestAndLayout(greenHydrogenNotePdf(), "Project Note - Green Hydrogen", "dpr");

        ChecklistFake fake = new ChecklistFake()
                .industry("{\"industry\": \"green hydrogen\","
                        + " \"quote\": \"20 MW green hydrogen project\"}")
                .draft("green hydrogen", GREEN_HYDROGEN_DRAFT);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactly("green hydrogen");
        assertThat(qa.industries().get(0).isNewIndustry()).isTrue();

        // The starter list: the country spine plus the drafted green-hydrogen items.
        MandateQa.ChecklistResultRow offtake =
                checklistRow(qa, "green_hydrogen_offtake_agreement", null);
        assertThat(offtake.status()).isEqualTo("not_provided");
        assertThat(offtake.label()).isEqualTo("Green hydrogen offtake agreement");
        assertThat(offtake.basisKind()).isEqualTo("regulation");
        assertThat(offtake.basisText()).contains("National Green Hydrogen Mission");
        assertThat(offtake.note()).isEqualTo("new industry: first mandate");
        assertThat(offtake.clientRequest()).contains("offtake agreement");
        assertThat(checklistRow(qa, "electrolyser_quotations", null).level())
                .isEqualTo("document");
        assertThat(row(qa, "pan_card", null)).isNotNull();

        // Items without a basis are dropped — never asked for.
        assertThat(row(qa, "invented_item_without_basis", null)).isNull();
        assertThat(qa.checklist()).hasSize(18);
        assertThat(qa.checklist()).allSatisfy(row ->
                assertThat(row.basisText()).isNotBlank());

        // The starter definition is versioned data beside the workspace: a re-run
        // reuses it instead of asking the model again, and replaces without duplicates.
        DocAssessRunner.run(MANDATE, dbPath, fake);
        assertThat(fake.draftCalls()).isEqualTo(1);
        assertThat(qa().checklist()).hasSize(18);
    }

    // --- AC1: industry checklists keyed by industry and country ----------------

    @Test
    void solarIndiaFixtureExpectsPpaAndPvsystPerSiteAndNeverHotelItems() throws Exception {
        // Another mandate already drafted a hotel-India checklist: never asked here.
        writeStoreChecklist("in.hotel.yaml", HOTEL_STORE_YAML);
        quotation("quotation_solar.pdf", "Quote - Jaisingpura", "Jaisingpura",
                "Project: Jaisingpura Solar Plant");

        ChecklistFake fake = new ChecklistFake().draft("solar power", SOLAR_DRAFT);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        assertThat(qa.country().country()).isEqualTo("India");
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactly("solar power");
        assertThat(qa.industries().get(0).isNewIndustry()).isFalse();

        // Per site: the PPA and the PVsyst report are expected for "Jaisingpura".
        MandateQa.ChecklistResultRow ppa = checklistRow(qa, "solar_ppa", null);
        assertThat(ppa.status()).isEqualTo("not_provided");
        assertThat(ppa.siteName()).isEqualTo("Jaisingpura");
        assertThat(ppa.documentType()).isEqualTo("agreement.ppa");
        assertThat(ppa.clientRequest()).contains("site Jaisingpura");
        assertThat(ppa.note()).isNull();
        MandateQa.ChecklistResultRow pvsyst = checklistRow(qa, "pvsyst_report", null);
        assertThat(pvsyst.siteName()).isEqualTo("Jaisingpura");
        assertThat(pvsyst.level()).isEqualTo("document");
        assertThat(qa.checklist().stream().filter(r -> "solar_ppa".equals(r.itemKey())))
                .hasSize(1);

        // Items outside the mandate's industries are never asked: no hotel licence.
        assertThat(qa.checklist()).noneMatch(r -> r.itemKey().contains("hotel")
                || r.label().toLowerCase().contains("licence"));
    }

    @Test
    void hotelIndiaFixtureExpectsHotelLicenceAndNeverAPpa() throws Exception {
        // A solar-India checklist exists beside the workspace: never asked here.
        writeStoreChecklist("in.solar_power.yaml", SOLAR_STORE_YAML);
        ingestAndLayout(hotelNotePdf(), "Hotel Project Note - Udaipur", "dpr");

        ChecklistFake fake = new ChecklistFake()
                .industry("{\"industry\": \"hotel\", \"quote\": \"120-key hotel project\"}")
                .draft("hotel", HOTEL_DRAFT);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactly("hotel");
        assertThat(qa.industries().get(0).isNewIndustry()).isTrue();

        MandateQa.ChecklistResultRow licence = checklistRow(qa, "hotel_licence", null);
        assertThat(licence.status()).isEqualTo("not_provided");
        assertThat(licence.note()).isEqualTo("new industry: first mandate");
        assertThat(licence.basisText()).contains("licence");

        // Never a PPA: the solar-India definition stays out of scope.
        assertThat(qa.checklist()).noneMatch(r ->
                r.itemKey().contains("ppa") || "agreement.ppa".equals(r.documentType()));
    }

    // --- AC2: multi-industry mandate = spine + each industry, no duplicates ----

    @Test
    void hybridMandateGetsSpinePlusBothIndustriesWithoutDuplicates() throws Exception {
        ingestAndLayout(hybridNotePdf(), "Project Note - Solar and Wind", "dpr");

        ChecklistFake fake = new ChecklistFake()
                .draft("solar power", SOLAR_DRAFT)
                .draft("wind power", WIND_DRAFT);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        assertThat(qa.industries()).extracting(MandateQa.IndustryRow::industry)
                .containsExactly("solar power", "wind power");

        // Spine plus each of the two industries.
        assertThat(row(qa, "pan_card", null)).isNotNull();
        assertThat(row(qa, "solar_ppa", null)).isNotNull();
        assertThat(row(qa, "wind_turbine_supply_agreement", null)).isNotNull();

        // An item both industries draft is asked once — no duplicates anywhere.
        assertThat(qa.checklist().stream()
                .filter(r -> "shared_land_document".equals(r.itemKey())))
                .hasSize(1);
        assertThat(qa.checklist())
                .extracting(r -> r.itemKey() + "|" + r.entityId() + "|" + r.siteId())
                .doesNotHaveDuplicates();
    }

    // --- AC4: one PPA for two sites — Provided once, Not provided for the other --

    @Test
    void twoSiteSolarMandateWithOnePpaIsProvidedForOneSiteOnly() throws Exception {
        quotation("quotation_a.pdf", "Quote A - Jaisingpura", "Jaisingpura",
                "Project: Jaisingpura Solar Plant");
        quotation("quotation_b.pdf", "Quote B - Karawal Nagar", "Karawal Nagar",
                "Project: Karawal Nagar Solar Park");
        ppa("ppa_a.pdf", "PPA A - Jaisingpura", "Project: Jaisingpura Solar Plant");

        ChecklistFake fake = new ChecklistFake().draft("solar power", SOLAR_DRAFT);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        assertThat(qa.sites()).hasSize(2);
        MandateQa.SiteRow siteA = siteNamed(qa, "Jaisingpura");
        MandateQa.SiteRow siteB = siteNamed(qa, "Karawal Nagar");

        MandateQa.ChecklistResultRow forA = rowForSite(qa, "solar_ppa", siteA.siteId());
        assertThat(forA.status()).isEqualTo("provided");
        assertThat(forA.pageIndex()).isEqualTo(1);
        assertThat(forA.fileName()).isEqualTo("ppa_a.pdf");
        MandateQa.ChecklistResultRow forB = rowForSite(qa, "solar_ppa", siteB.siteId());
        assertThat(forB.status()).isEqualTo("not_provided");
        assertThat(forB.clientRequest()).contains("site Karawal Nagar");
        assertThat(rowForSite(qa, "pvsyst_report", siteA.siteId()).status())
                .isEqualTo("not_provided");
        assertThat(rowForSite(qa, "pvsyst_report", siteB.siteId()).status())
                .isEqualTo("not_provided");
        assertThat(qa.checklist().stream().filter(r -> "solar_ppa".equals(r.itemKey())))
                .hasSize(2);

        // The summary carries the per-site breakdown.
        assertThat(siteSummaryCount(qa, siteA.siteId(), "provided")).isEqualTo(1);
        assertThat(siteSummaryCount(qa, siteA.siteId(), "not_provided")).isEqualTo(1);
        assertThat(siteSummaryCount(qa, siteB.siteId(), "provided")).isZero();
        assertThat(siteSummaryCount(qa, siteB.siteId(), "not_provided")).isEqualTo(2);
    }

    // --- AC5: the documents show an item does not apply — Not applicable ------

    @Test
    void itemTheDocumentsShowDoesNotApplyIsNotApplicableWithReasonPageAndNoFlag()
            throws Exception {
        ingestAndLayout(greenHydrogenNotePdf(), "Project Note - Green Hydrogen", "dpr");

        ChecklistFake fake = new ChecklistFake()
                .industry("{\"industry\": \"green hydrogen\","
                        + " \"quote\": \"20 MW green hydrogen project\"}")
                .draft("green hydrogen", GREEN_HYDROGEN_DRAFT)
                .verdict("Green hydrogen offtake agreement",
                        "{\"not_applicable\": [{\"key\": \"green_hydrogen_offtake_agreement\","
                        + " \"reason\": \"the note states the plant is for captive consumption,"
                        + " so no offtake agreement arises\","
                        + " \"quote\": \"The plant is for captive consumption;"
                        + " no power is sold.\"}]}");
        DocAssessRunner.run(MANDATE, dbPath, fake);

        MandateQa qa = qa();
        MandateQa.ChecklistResultRow offtake =
                checklistRow(qa, "green_hydrogen_offtake_agreement", null);
        assertThat(offtake.status()).isEqualTo("not_applicable");
        assertThat(offtake.reason()).contains("captive consumption");
        assertThat(offtake.pageIndex()).isEqualTo(1);
        assertThat(offtake.fileName()).isEqualTo("green_hydrogen_note.pdf");
        assertThat(offtake.clientRequest()).isNull();

        // The rest of the checklist is untouched: still asked for, still noted.
        assertThat(checklistRow(qa, "electrolyser_quotations", null).status())
                .isEqualTo("not_provided");
        assertThat(summaryCount(qa, "not_applicable")).isEqualTo(1);
        assertThat(summaryCount(qa, "not_provided"))
                .isEqualTo((int) qa.checklist().stream()
                        .filter(r -> "not_provided".equals(r.status())).count());

        // Not flagged for review: only the remaining not_provided items are.
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'"))
                    .isEqualTo(summaryCount(qa, "not_provided"));
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'"
                    + " AND summary LIKE '%offtake%'")).isZero();
        }
    }

    // --- fixtures -------------------------------------------------------------

    private Path greenHydrogenNotePdf() throws Exception {
        return writePdf("green_hydrogen_note.pdf",
                "Green Hydrogen Plant - Kandla",
                "This note covers the 20 MW green hydrogen project of the promoter.",
                "The plant is for captive consumption; no power is sold.",
                "The electrolyser is installed at Kandla, Gujarat, India.",
                "Report Date: 5 April 2025");
    }

    private Path hybridNotePdf() throws Exception {
        return writePdf("solar_wind_note.pdf",
                "Co-located Plant - Gujarat",
                "This note covers the co-located solar and wind power project of the promoter.",
                "The plant is installed at Jaisingpura, Gujarat, India.",
                "Report Date: 5 April 2025");
    }

    private Path hotelNotePdf() throws Exception {
        return writePdf("hotel_project_note.pdf",
                "Hotel Project - Udaipur",
                "This note covers the 120-key hotel project of the promoter.",
                "The site is at Udaipur, Rajasthan, India.",
                "Report Date: 5 April 2025");
    }

    /** Ingest → layout → classify → extract one quotation PDF (a site-naming fact). */
    private long quotation(String fileName, String logicalName, String projectName,
            String projectLine) throws Exception {
        long versionId = ingestAndLayout(writePdf(fileName,
                "Quotation for Supply of Solar Modules",
                "Vendor: Sri Sai Electricals Pvt Ltd",
                projectLine,
                "Registered office: Jaipur, India",
                "Grand Total: Rs. 62,45,000"), logicalName, "quotation");
        classify(versionId, quotationPack);
        ObjectNode fixture = fixture("quotation.vendor");
        ObjectNode fields = fixture.putObject("fields");
        addField(fields, "vendor_name", "Sri Sai Electricals Pvt Ltd",
                "Vendor: Sri Sai Electricals Pvt Ltd");
        addField(fields, "project_name", projectName, projectLine);
        extract(versionId, quotationPack, fixture);
        return versionId;
    }

    /** Ingest → layout → classify → extract one PPA PDF; returns the version id. */
    private long ppa(String fileName, String logicalName, String projectLine)
            throws Exception {
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
        extract(versionId, ppaPack, fixture);
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

    private void writeStoreChecklist(String fileName, String yaml) throws Exception {
        Path dir = tempDir.resolve("checklists");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve(fileName), yaml);
    }

    /** A hotel-India definition another mandate had already drafted. */
    private static final String HOTEL_STORE_YAML = """
            code: in.hotel
            version: 1.0.0
            scope: industry
            country: IN
            industries: [hotel]
            items:
              - key: hotel_licence
                label: Hotel licence
                level: document
                granularity: mandate
                document_type: licence.hotel
                basis:
                  kind: regulation
                  text: State hotel licensing rules — a hotel must hold its licence
                request: Please share the hotel licence.
            """;

    /** A solar-India definition another mandate had already drafted. */
    private static final String SOLAR_STORE_YAML = """
            code: in.solar_power
            version: 1.0.0
            scope: industry
            country: IN
            industries: [solar_power]
            items:
              - key: solar_ppa
                label: Power Purchase Agreement (PPA)
                level: document
                granularity: site
                document_type: agreement.ppa
                basis:
                  kind: regulation
                  text: Electricity Act 2003 — a solar generator sells power under a PPA per site
                request: Please share the signed PPA for {site}.
            """;

    /** Solar-India first draft: a per-site PPA and PVsyst report, both with a basis. */
    private static final String SOLAR_DRAFT = """
            {"items": [
              {"key": "solar_ppa",
               "label": "Power Purchase Agreement (PPA)",
               "level": "document", "granularity": "site",
               "document_type": "agreement.ppa", "mandatory": true,
               "basis": {"kind": "regulation",
                 "text": "Electricity Act 2003 and state solar policy — each commissioned solar site sells under a PPA"},
               "request": "Please share the signed PPA for {site}."},
              {"key": "pvsyst_report",
               "label": "PVsyst performance report",
               "level": "document", "granularity": "site",
               "document_type": "report.pvsyst", "mandatory": true,
               "basis": {"kind": "practice",
                 "text": "Lender practice for solar mandates — the PVsyst yield report supports each site's generation case"},
               "request": "Please share the PVsyst report for {site}."},
              {"key": "shared_land_document",
               "label": "Land documents",
               "level": "document", "granularity": "mandate",
               "document_type": "land.documents", "mandatory": true,
               "basis": {"kind": "practice",
                 "text": "Lender practice — land title documents back every renewable project"},
               "request": "Please share the land title documents."}
            ]}""";

    /** Wind-India first draft; shares one item key with the solar draft. */
    private static final String WIND_DRAFT = """
            {"items": [
              {"key": "wind_turbine_supply_agreement",
               "label": "Wind turbine supply agreement",
               "level": "document", "granularity": "mandate",
               "document_type": "agreement.wind_turbine", "mandatory": true,
               "basis": {"kind": "practice",
                 "text": "Lender practice for wind mandates — turbine supply terms underpin the execution case"},
               "request": "Please share the wind turbine supply agreement."},
              {"key": "shared_land_document",
               "label": "Land documents",
               "level": "document", "granularity": "mandate",
               "document_type": "land.documents", "mandatory": true,
               "basis": {"kind": "practice",
                 "text": "Lender practice — land title documents back every renewable project"},
               "request": "Please share the land title documents."}
            ]}""";

    /** Hotel-India first draft for a brand-new industry. */
    private static final String HOTEL_DRAFT = """
            {"items": [
              {"key": "hotel_licence",
               "label": "Hotel licence",
               "level": "document", "granularity": "mandate",
               "document_type": "licence.hotel", "mandatory": true,
               "basis": {"kind": "regulation",
                 "text": "State hotel licensing rules under the Sarais' Act — a hotel must hold its licence to operate"},
               "request": "Please share the hotel licence."}
            ]}""";

    /** A first draft with one basis-less item: the loader's rule must drop it. */
    private static final String GREEN_HYDROGEN_DRAFT = """
            {"items": [
              {"key": "green_hydrogen_offtake_agreement",
               "label": "Green hydrogen offtake agreement",
               "level": "document", "granularity": "mandate",
               "document_type": "agreement.offtake", "mandatory": true,
               "basis": {"kind": "regulation",
                 "text": "National Green Hydrogen Mission (MNRE, 2023) — offtake arrangements underpin the electrolyser business case"},
               "request": "Please share the green hydrogen offtake agreement(s)."},
              {"key": "electrolyser_quotations",
               "label": "Electrolyser supply quotations",
               "level": "document", "granularity": "mandate",
               "document_type": "quotation.vendor", "mandatory": true,
               "basis": {"kind": "practice",
                 "text": "BRD §3.1 quotations requirement applies to the electrolyser, the core equipment of a green hydrogen plant"},
               "request": "Please share the electrolyser supply quotations."},
              {"key": "invented_item_without_basis",
               "label": "Some invented item",
               "level": "document", "granularity": "mandate",
               "document_type": "letter.misc", "mandatory": false,
               "basis": {"kind": "", "text": ""},
               "request": "Please share it."}
            ]}""";

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

    private Path writePdf(String name, String... lines) throws Exception {
        return writePdf(name, List.of(lines));
    }

    private long ingestAndLayout(Path pdf, String logicalName, String fileRole)
            throws Exception {
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

    static MandateQa.ChecklistResultRow checklistRow(MandateQa qa, String itemKey,
            Long entityId) {
        MandateQa.ChecklistResultRow found = row(qa, itemKey, entityId);
        if (found == null) {
            throw new AssertionError("no checklist row " + itemKey);
        }
        return found;
    }

    static MandateQa.ChecklistResultRow row(MandateQa qa, String itemKey, Long entityId) {
        return qa.checklist().stream()
                .filter(r -> itemKey.equals(r.itemKey()))
                .filter(r -> entityId == null ? r.entityId() == null
                        : entityId.equals(r.entityId()))
                .findFirst()
                .orElse(null);
    }

    static MandateQa.ChecklistResultRow rowForSite(MandateQa qa, String itemKey, long siteId) {
        return qa.checklist().stream()
                .filter(r -> itemKey.equals(r.itemKey()))
                .filter(r -> r.siteId() != null && r.siteId() == siteId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no checklist row " + itemKey
                        + " for site " + siteId));
    }

    static MandateQa.SiteRow siteNamed(MandateQa qa, String name) {
        return qa.sites().stream()
                .filter(s -> name.equals(s.siteName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("site not found: " + name));
    }

    static int siteSummaryCount(MandateQa qa, long siteId, String status) {
        return qa.checklistSummary().stream()
                .filter(r -> "site".equals(r.scopeKind()))
                .filter(r -> r.siteId() != null && r.siteId() == siteId)
                .filter(r -> status.equals(r.status()))
                .mapToInt(MandateQa.ChecklistSummaryRow::itemCount)
                .sum();
    }

    static int summaryCount(MandateQa qa, String status) {
        return qa.checklistSummary().stream()
                .filter(r -> "overall".equals(r.scopeKind()))
                .filter(r -> status.equals(r.status()))
                .mapToInt(MandateQa.ChecklistSummaryRow::itemCount)
                .sum();
    }

    private static int count(java.sql.Connection connection, String sql) throws Exception {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql);
                java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Deterministic fake over the doc LLM seam for the checklist tests: answers the
     * industry read, the industry-checklist draft (counted, per industry), the
     * Not-applicable decision (per item label) and stays silent elsewhere.
     */
    static final class ChecklistFake implements DocLlmClient {

        private final List<String> industryReplies = new ArrayList<>();
        private final Map<String, String> drafts = new LinkedHashMap<>();
        private final Map<String, String> verdicts = new LinkedHashMap<>();
        private int industryCalls;
        private int draftCalls;

        ChecklistFake industry(String reply) {
            industryReplies.add(reply);
            return this;
        }

        ChecklistFake draft(String industryMarker, String reply) {
            drafts.put(industryMarker, reply);
            return this;
        }

        ChecklistFake verdict(String itemLabelMarker, String reply) {
            verdicts.put(itemLabelMarker, reply);
            return this;
        }

        int draftCalls() {
            return draftCalls;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            if (system.contains("You read ONE industry")) {
                int index = Math.min(industryCalls, industryReplies.size() - 1);
                industryCalls++;
                String reply = industryReplies.isEmpty()
                        ? "{\"industry\": null, \"quote\": null}" : industryReplies.get(index);
                return reply(reply);
            }
            if (system.contains("You draft the industry checklist")) {
                draftCalls++;
                for (Map.Entry<String, String> draft : drafts.entrySet()) {
                    if (system.contains("\"" + draft.getKey() + "\" industry")) {
                        return reply(draft.getValue());
                    }
                }
                return reply("{\"items\": []}");
            }
            if (system.contains("does not apply to this project")) {
                for (Map.Entry<String, String> verdict : verdicts.entrySet()) {
                    if (user.contains(verdict.getKey())) {
                        return reply(verdict.getValue());
                    }
                }
                return reply("{\"not_applicable\": []}");
            }
            return reply("{\"entities\": []}");
        }

        private static Completion reply(String content) {
            return new Completion(content, "fake-model", 100, 50, 0.002, 12);
        }
    }
}
