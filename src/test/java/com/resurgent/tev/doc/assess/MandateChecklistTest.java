package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.cli.DocChecklistCommand;
import com.resurgent.tev.doc.cli.DocQaCommand;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.DocLlmClient;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
 * Golden M3-10 suite (issue #143): the spine checklist engine over synthetic
 * born-digital PDFs. Checklist definitions are data; statuses are deterministic
 * (Provided with page evidence, Unclear when unreliable, Not provided with the
 * ask-the-client wording). Asserts persisted rows through the mandate QA read API.
 */
class MandateChecklistTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack netWorthPack;
    private DocTypePack quotationPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
    }

    // --- AC3: net-worth certificate for one of two promoters ------------------

    @Test
    void netWorthCertificateProvidedForOnePromoterNotProvidedForTheOther()
            throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");
        ingestNetWorth("promoter_nw_ramesh.pdf", "RAMESH KUMAR SHARMA");

        DocAssessRunner.Result assess = DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());
        assertThat(assess.summaryLines()).anySatisfy(line ->
                assertThat(line).startsWith("assess_checklist:"));

        MandateQa qa = qa();
        MandateQa.EntityRow ramesh = entity(qa, "RAMESH KUMAR SHARMA");
        MandateQa.EntityRow suresh = entity(qa, "Suresh Kumar Sharma");

        // Provided (page) for one promoter — with basis and page evidence.
        MandateQa.ChecklistResultRow forRamesh = checklistRow(
                qa, "promoter_net_worth_certificate", ramesh.entityId());
        assertThat(forRamesh.status()).isEqualTo("provided");
        assertThat(forRamesh.pageIndex()).isEqualTo(1);
        assertThat(forRamesh.documentType()).isEqualTo("identity.promoter_net_worth");
        assertThat(forRamesh.fileName()).isEqualTo("promoter_nw_ramesh.pdf");
        assertThat(forRamesh.basisKind()).isEqualTo("practice");
        assertThat(forRamesh.basisText()).contains("Net Worth Certificates");
        assertThat(forRamesh.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(forRamesh.level()).isEqualTo("document");
        assertThat(forRamesh.granularity()).isEqualTo("entity");

        // Not provided for the other promoter — with wording ready for an email.
        MandateQa.ChecklistResultRow forSuresh = checklistRow(
                qa, "promoter_net_worth_certificate", suresh.entityId());
        assertThat(forSuresh.status()).isEqualTo("not_provided");
        assertThat(forSuresh.pageIndex()).isNull();
        assertThat(forSuresh.clientRequest())
                .contains("Suresh Kumar Sharma")
                .contains("net worth certificate");
        assertThat(forSuresh.reason()).contains("no");

        // The net-worth figure data point follows the same promoter's certificate.
        assertThat(checklistRow(qa, "promoter_net_worth_value", ramesh.entityId()).status())
                .isEqualTo("provided");
        assertThat(checklistRow(qa, "promoter_net_worth_value", suresh.entityId()).status())
                .isEqualTo("not_provided");

        // Everything is reported with a reason — nothing is settled silently.
        assertThat(qa.checklist()).allSatisfy(row ->
                assertThat(row.reason()).isNotBlank());

        // Provided evidence is persisted as provenance with the reserved type.
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'checklist_result'")).isEqualTo(2);
        }
    }

    // --- AC4: present document missing a required data point ------------------

    @Test
    void presentQuotationMissingRequiredDataPointIsNotProvidedAtDataPointLevel()
            throws Exception {
        long versionId = ingestAndLayout(quotationPdf(), "Quotation - Vendor", "quotation");
        classify(versionId, quotationPack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.putFixture("quotation.vendor", quotationFixtureWithoutGrossTotal());
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, MANDATE, quotationPack, extractClient, FixtureExtractClient.ID);
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), quotationPack);

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        // The document itself is provided...
        MandateQa.ChecklistResultRow documentItem = row(qa, "cost_quotations", null);
        assertThat(documentItem.status()).isEqualTo("provided");
        assertThat(documentItem.pageIndex()).isEqualTo(1);

        // ...but the required data point inside it is Not provided at data-point level.
        MandateQa.ChecklistResultRow dataPoint = row(qa, "quotation_gross_total", null);
        assertThat(dataPoint.status()).isEqualTo("not_provided");
        assertThat(dataPoint.level()).isEqualTo("data_point");
        assertThat(dataPoint.fieldKey()).isEqualTo("gross_total");
        assertThat(dataPoint.documentType()).isEqualTo("quotation.vendor");
        assertThat(dataPoint.reason())
                .contains("present")
                .contains("gross total");
        assertThat(dataPoint.clientRequest()).contains("gross total");
    }

    // --- AC5: low-confidence evidence is Unclear, flagged, never Provided -----

    @Test
    void lowConfidenceEvidenceYieldsUnclearFlaggedNeverProvided() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");
        long netWorthVersion = ingestNetWorth("promoter_nw_lowconf.pdf", "RAMESH KUMAR SHARMA");

        // The scans were weak: every fact of the certificate is low-confidence.
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            try (var ps = c.prepareStatement(
                    "UPDATE fact SET confidence = 0.3 WHERE document_version_id = ?")) {
                ps.setLong(1, netWorthVersion);
                ps.executeUpdate();
            }
        }

        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        MandateQa qa = qa();
        MandateQa.EntityRow ramesh = entity(qa, "RAMESH KUMAR SHARMA");
        MandateQa.ChecklistResultRow forRamesh = checklistRow(
                qa, "promoter_net_worth_certificate", ramesh.entityId());
        assertThat(forRamesh.status()).isEqualTo("unclear");
        assertThat(forRamesh.status()).isNotEqualTo("provided");
        assertThat(forRamesh.reason()).contains("confidence");
        assertThat(forRamesh.reason()).contains("0.30");

        // Unclear is flagged, non-blockingly, in review_queue.
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_unclear'"
                    + " AND summary LIKE '%RAMESH KUMAR SHARMA%'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'")).isEqualTo(16);
        }

        // Counts by status are persisted: overall and per entity.
        assertThat(summaryCount(qa, null, "provided")).isZero();
        assertThat(summaryCount(qa, null, "unclear")).isEqualTo(2);
        assertThat(summaryCount(qa, null, "not_provided")).isEqualTo(16);
        assertThat(summaryCount(qa, ramesh.entityId(), "unclear")).isEqualTo(2);
        assertThat(summaryCount(qa, entity(qa, "Suresh Kumar Sharma").entityId(),
                "not_provided")).isEqualTo(2);
    }

    // --- AC6: the analyst's optional add/remove, with a required reason ---------

    @Test
    void analystOverridesRequireReasonAndApplyToTheNextAssessment() throws Exception {
        ingestNetWorth("promoter_nw_ramesh.pdf", "RAMESH KUMAR SHARMA");
        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(qa().checklist()).hasSize(16);
        assertThat(row(qa(), "ppa_document", null)).isNull();

        writeSolarChecklistStore();
        // Neither action is possible without a reason.
        Run noReason = run(new DocChecklistCommand(), "--mandate", "1", "--db", dbPath.toString(),
                "--add", "ppa_document");
        assertThat(noReason.exitCode()).isEqualTo(3);
        assertThat(noReason.err()).contains("reason");
        Run unknown = run(new DocChecklistCommand(), "--mandate", "1", "--db", dbPath.toString(),
                "--add", "no_such_item", "--reason", "just checking");
        assertThat(unknown.exitCode()).isEqualTo(3);
        assertThat(unknown.err()).contains("unknown item key");

        Run added = run(new DocChecklistCommand(), "--mandate", "1", "--db", dbPath.toString(),
                "--add", "ppa_document", "--reason", "Solar desk tracks PPAs for these mandates");
        assertThat(added.exitCode()).isZero();
        assertThat(added.out()).contains("recorded");

        // The override lands on the NEXT assessment, not the current state.
        assertThat(row(qa(), "ppa_document", null)).isNull();

        DocAssessRunner.run(MANDATE, dbPath, null);
        MandateQa.ChecklistResultRow addedRow = checklistRow(qa(), "ppa_document", null);
        assertThat(addedRow.status()).isEqualTo("not_provided");
        assertThat(addedRow.basisKind()).isEqualTo("regulation");
        assertThat(addedRow.basisText()).contains("Electricity Act");
        assertThat(addedRow.clientRequest()).contains("power purchase agreement");
        assertThat(qa().checklist()).hasSize(17);

        Run removed = run(new DocChecklistCommand(), "--mandate", "1", "--db", dbPath.toString(),
                "--remove", "promoter_net_worth_certificate",
                "--reason", "Borrower filed it through the group company instead");
        assertThat(removed.exitCode()).isZero();

        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(row(qa(), "promoter_net_worth_certificate", null)).isNull();
        assertThat(row(qa(), "promoter_net_worth_value", null)).isNotNull();
        assertThat(qa().checklist()).hasSize(16);

        // Both overrides are recorded with their reasons and listed by the CLI.
        Run list = run(new DocChecklistCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(list.exitCode()).isZero();
        assertThat(list.out())
                .contains("override add ppa_document")
                .contains("Solar desk tracks PPAs")
                .contains("override remove promoter_net_worth_certificate");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(), "SELECT COUNT(*) FROM checklist_override"))
                    .isEqualTo(2);
        }
    }

    // --- AC7: the missing document arrives — Provided on the next assessment ----

    @Test
    void addingTheMissingDocumentFlipsTheItemToProvidedOnReassessment() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");
        ingestNetWorth("promoter_nw_ramesh.pdf", "RAMESH KUMAR SHARMA");
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        MandateQa.EntityRow suresh = entity(qa(), "Suresh Kumar Sharma");
        assertThat(checklistRow(qa(), "promoter_net_worth_certificate", suresh.entityId()).status())
                .isEqualTo("not_provided");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(), "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'"
                    + " AND summary LIKE '%Suresh Kumar Sharma%'")).isEqualTo(2);
        }

        // The client sends the missing certificate; processing it re-runs the assessment.
        ingestNetWorth("promoter_nw_suresh.pdf", "Suresh Kumar Sharma");
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        MandateQa after = qa();
        MandateQa.EntityRow sureshAfter = entity(after, "Suresh Kumar Sharma");
        MandateQa.ChecklistResultRow cert = checklistRow(
                after, "promoter_net_worth_certificate", sureshAfter.entityId());
        assertThat(cert.status()).isEqualTo("provided");
        assertThat(cert.pageIndex()).isEqualTo(1);
        assertThat(cert.fileName()).isEqualTo("promoter_nw_suresh.pdf");
        assertThat(checklistRow(after, "promoter_net_worth_value", sureshAfter.entityId()).status())
                .isEqualTo("provided");

        // The ask disappeared, the counts flipped — and a re-run replaces, no duplicates.
        assertThat(summaryCount(after, null, "provided")).isEqualTo(4);
        assertThat(summaryCount(after, null, "not_provided")).isEqualTo(14);
        assertThat(summaryCount(after, sureshAfter.entityId(), "provided")).isEqualTo(2);
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());
        MandateQa again = qa();
        assertThat(again.checklist()).hasSize(18);
        assertThat(again.checklistSummary()).hasSize(15);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'"
                    + " AND summary LIKE '%Suresh Kumar Sharma%'")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'checklist_not_provided'")).isEqualTo(14);
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'checklist_result'")).isEqualTo(4);
            assertThat(count(c, "SELECT COUNT(*) FROM checklist_item")).isEqualTo(16);
            assertThat(count(c, "SELECT COUNT(*) FROM checklist_result")).isEqualTo(18);
        }
    }

    // --- QA output: the checklist shows in the text and JSON reads --------------

    @Test
    void qaCommandShowsChecklistAsksAndCounts() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");
        ingestNetWorth("promoter_nw_ramesh.pdf", "RAMESH KUMAR SHARMA");
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("checklist promoter_net_worth_certificate [provided]")
                .contains("ask the client:")
                .contains("Suresh Kumar Sharma's net worth certificate")
                .contains("checklist: 18 items (2 provided, 0 unclear, 16 not_provided)")
                .contains("checklist summary overall: 2 provided")
                .contains("checklist summary entity RAMESH KUMAR SHARMA: 2 provided");

        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        assertThat(json.exitCode()).isZero();
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("checklist")).hasSize(18);
        JsonNode sureshCert = checklistJson(
                root.get("checklist"), "promoter_net_worth_certificate", "Suresh Kumar Sharma");
        assertThat(sureshCert).isNotNull();
        assertThat(sureshCert.get("status").asText()).isEqualTo("not_provided");
        assertThat(sureshCert.get("client_request").asText()).contains("Suresh Kumar Sharma");
        JsonNode rameshCert = checklistJson(
                root.get("checklist"), "promoter_net_worth_certificate", "RAMESH KUMAR SHARMA");
        assertThat(rameshCert.get("status").asText()).isEqualTo("provided");
        assertThat(rameshCert.get("page_index").asInt()).isEqualTo(1);
        JsonNode provided = null;
        for (JsonNode summary : root.get("checklist_summary")) {
            if ("overall".equals(summary.get("scope_kind").asText())
                    && "provided".equals(summary.get("status").asText())) {
                provided = summary;
            }
        }
        assertThat(provided).isNotNull();
        assertThat(provided.get("item_count").asInt()).isEqualTo(2);
    }

    // --- fixtures -------------------------------------------------------------

    private long ingestNetWorth(String fileName, String promoter) throws Exception {
        long versionId = ingestAndLayout(netWorthPdf(fileName, promoter), "Promoter NW", "certificate");
        classify(versionId, netWorthPack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.putFixture("identity.promoter_net_worth", netWorthFixture(promoter));
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, MANDATE, netWorthPack, extractClient, FixtureExtractClient.ID);
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), netWorthPack);
        return versionId;
    }

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

    private Path netWorthPdf(String fileName, String promoter) throws Exception {
        return writePdf(fileName,
                List.of(
                        "Certificate of Net Worth",
                        "Name of the Member: " + promoter,
                        "As on 31 March 2026",
                        "Total Assets: Rs. 2,45,00,000",
                        "Total Liabilities: Rs. 85,00,000",
                        "Net Worth of " + promoter + ": Rs. 1,60,00,000",
                        "Date: 5 February 2026"));
    }

    private static JsonNode netWorthFixture(String promoter) throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "identity.promoter_net_worth",
                  "fields": {
                    "subject_name": {"value": "%s",
                      "quote": "Name of the Member: %s", "page": 1},
                    "as_of_date": {"value": "31/03/2026",
                      "quote": "As on 31 March 2026", "page": 1},
                    "assets_total": {"value": "Rs. 2,45,00,000",
                      "quote": "Total Assets: Rs. 2,45,00,000", "page": 1},
                    "liabilities_total": {"value": "Rs. 85,00,000",
                      "quote": "Total Liabilities: Rs. 85,00,000", "page": 1},
                    "net_worth": {"value": "Rs. 1,60,00,000",
                      "quote": "Net Worth of %s: Rs. 1,60,00,000", "page": 1}
                  }
                }""".formatted(promoter, promoter, promoter));
    }

    private Path quotationPdf() throws Exception {
        return writePdf("vendor_quotation.pdf",
                List.of(
                        "Vendor Quotation / Price Bid",
                        "From: Sunbeam Solar Systems Pvt Ltd",
                        "Quotation for 10 MW solar power plant equipment",
                        "Date: 12 April 2026",
                        "Total: Rs. 48,20,00,000"));
    }

    /** The present quotation carries no quoted gross total value in this scan. */
    private static JsonNode quotationFixtureWithoutGrossTotal() throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "quotation.vendor",
                  "fields": {
                    "vendor_name": {"value": "Sunbeam Solar Systems Pvt Ltd",
                      "quote": "From: Sunbeam Solar Systems Pvt Ltd", "page": 1},
                    "quote_date": {"value": "12/04/2026",
                      "quote": "Date: 12 April 2026", "page": 1}
                  }
                }""");
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

    // --- helpers --------------------------------------------------------------

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
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static MandateQa.EntityRow entity(MandateQa qa, String name) {
        return qa.entities().stream()
                .filter(e -> name.equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entity " + name + " in "
                        + qa.entities().stream().map(MandateQa.EntityRow::name).toList()));
    }

    static MandateQa.ChecklistResultRow checklistRow(MandateQa qa, String itemKey, Long entityId) {
        MandateQa.ChecklistResultRow found = row(qa, itemKey, entityId);
        if (found == null) {
            throw new AssertionError("no checklist row " + itemKey + " for entity " + entityId);
        }
        return found;
    }

    static MandateQa.ChecklistResultRow row(MandateQa qa, String itemKey, Long entityId) {
        return qa.checklist().stream()
                .filter(r -> itemKey.equals(r.itemKey()))
                .filter(r -> entityId == null
                        ? r.entityId() == null
                        : entityId.equals(r.entityId()))
                .findFirst()
                .orElse(null);
    }

    static int summaryCount(MandateQa qa, Long entityId, String status) {
        return qa.checklistSummary().stream()
                .filter(s -> "overall".equals(s.scopeKind()) == (entityId == null))
                .filter(s -> entityId == null || entityId.equals(s.entityId()))
                .filter(s -> status.equals(s.status()))
                .mapToInt(MandateQa.ChecklistSummaryRow::itemCount)
                .findFirst()
                .orElse(-1);
    }

    static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private record Run(int exitCode, String out, String err) {}

    /** Executes one CLI command through picocli, capturing stdout/stderr. */
    private Run run(Callable<Integer> command, String... args) {
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        Integer exit = cmd.execute(args);
        return new Run(exit == null ? -1 : exit, out.toString(), err.toString());
    }

    /** Publishes an industry checklist beside the workspace, like the pack store. */
    private void writeSolarChecklistStore() throws Exception {
        Path dir = tempDir.resolve("checklists");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("in.solar.yaml"), """
                code: in.solar
                version: 1.0.0
                scope: industry
                country: IN
                industries:
                  - solar
                items:
                  - key: ppa_document
                    label: Power purchase agreement
                    level: document
                    granularity: mandate
                    document_type: agreement.ppa
                    mandatory: true
                    basis:
                      kind: regulation
                      text: "Electricity Act, 2003, s.86 — the tariff of a generating project is settled by a PPA"
                    request: "Please share the executed power purchase agreement (PPA)."
                """);
    }

    private static JsonNode checklistJson(JsonNode rows, String itemKey, String entityName) {
        for (JsonNode row : rows) {
            if (itemKey.equals(row.get("item_key").asText())
                    && entityName.equals(row.get("entity_name").asText())) {
                return row;
            }
        }
        return null;
    }

    /**
     * Deterministic fake over the doc LLM seam: replies per document, recognised
     * by a marker in the sent text — the entity scan needs the mandate letter and
     * the MOA; the checklist itself asks the model nothing.
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
