package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.cli.DocClassifyCommand;
import com.resurgent.tev.doc.cli.DocExtractCommand;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.DocExtractRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.BBox;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Zero-touch classify and extract (issue #177). Synthetic documents only.
 * The fixture OCR client and the offline domain catalog do the work; nothing
 * here reads {@code fixtures/} or calls the network.
 */
class ZeroTouchPipelineTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    @Test
    void noAutoInduceLeavesAnUnknownDocumentUnknown() throws Exception {
        Workspace ws = workspace();
        long version = ingestPdf(ws, "menu.pdf", "Cafeteria Menu", List.of(
                "Cafeteria Menu",
                "Tuesday special is lentil soup and rice."));
        CommandLine classify = new CommandLine(new DocClassifyCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        classify.setOut(new PrintWriter(out));
        classify.setErr(new PrintWriter(err));
        int exit = classify.execute(
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", ws.db().toString(),
                "--no-auto-induce");
        assertThat(exit).isZero();
        assertThat(out.toString()).contains("unknown");
        assertThat(classified(ws, version)).isEqualTo("unknown");

        CommandLine extract = new CommandLine(new DocExtractCommand());
        StringWriter extractErr = new StringWriter();
        extract.setOut(new PrintWriter(new StringWriter()));
        extract.setErr(new PrintWriter(extractErr));
        int extractExit = extract.execute(
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", ws.db().toString(),
                "--extractor", "fixture",
                "--no-parse",
                "--no-auto-induce");
        assertThat(extractExit).isEqualTo(3);
        assertThat(extractErr.toString()).contains("--pack is required");
    }

    @Test
    void creditRatingReportIsInducedExtractedBoundAndAssessed() throws Exception {
        assertType(CREDIT, "CARE", "AA+", "A1+", "Stable", "DSCR above 1.20", "Working Capital");
    }

    @Test
    void twoCreditReportsShareOneInducedPack() throws Exception {
        assertPair(CREDIT, CREDIT_COPY, "CARE", "AA");
    }

    @Test
    void debtTermSheetIsInducedExtractedBoundAndAssessed() throws Exception {
        assertType(DEBT, "Term Loan", "250 bps", "12 years", "18 months", "quarterly",
                "first charge", "DSCR above 1.25", "50000000");
    }

    @Test
    void twoDebtTermSheetsShareOneInducedPack() throws Exception {
        assertPair(DEBT, DEBT_COPY, "Term Loan", "400 bps");
    }

    @Test
    void bankStatementIsInducedExtractedBoundAndAssessed() throws Exception {
        assertType(BANK, "1250000", "Closing Balance", "Inflow");
    }

    @Test
    void twoBankStatementsShareOneInducedPack() throws Exception {
        assertPair(BANK, BANK_COPY, "1250000", "990000");
    }

    @Test
    void directorProfileIsInducedExtractedBoundAndAssessed() throws Exception {
        assertType(RESUME, "B.E. Electrical", "18 years", "2004", "Plant Head");
    }

    @Test
    void twoDirectorProfilesShareOneInducedPack() throws Exception {
        assertPair(RESUME, RESUME_COPY, "B.E. Electrical", "M.Tech");
    }

    @Test
    void vendorProposalIsInducedExtractedBoundAndAssessed() throws Exception {
        assertType(VENDOR, "30 percent advance", "2026-12-31", "Inverter", "45000", "90000");
    }

    @Test
    void twoVendorProposalsShareOneInducedPack() throws Exception {
        assertPair(VENDOR, VENDOR_COPY, "Inverter", "Transformer");
    }

    @Test
    void cibilScreenshotIsInducedExtractedBoundAndAssessed() throws Exception {
        Workspace ws = workspace();
        long version = ingestCibil(ws, "cibil-a.png", "742");
        finish(ws, List.of(version));
        String dump = dump(facts(ws));
        assertThat(dump).contains("742").contains("90").contains("SMA-2").contains("Cash Credit");
        assertThat(classified(ws, version)).startsWith("induced.");
        assertRouted(ws, version);
        MandateQa qa = qa(ws);
        assertThat(qa.inductionDecisions()).isNotEmpty();
        assertThat(qa.passages()).anySatisfy(row ->
                assertThat(row.topic()).contains("delinquency"));
    }

    @Test
    void twoCibilScreenshotsShareOneInducedPack() throws Exception {
        Workspace ws = workspace();
        long first = ingestCibil(ws, "cibil-a.png", "742");
        long second = ingestCibil(ws, "cibil-b.png", "710");
        finish(ws, List.of(first, second));
        assertThat(classified(ws, first)).isEqualTo(classified(ws, second));
        assertThat(classified(ws, first)).startsWith("induced.");
        String dump = dump(facts(ws));
        assertThat(dump).contains("742").contains("710");
    }

    @Test
    void distinctUnknownTypesInOneMandateGetOnePackEach() throws Exception {
        Workspace ws = workspace();
        long bank = ingestPdf(ws, "bank.pdf", BANK.logicalName(), BANK.lines());
        long credit = ingestPdf(ws, "credit.pdf", CREDIT.logicalName(), CREDIT.lines());
        long resume = ingestPdf(ws, "resume.pdf", RESUME.logicalName(), RESUME.lines());
        long cibil = ingestCibil(ws, "cibil.png", "742");
        finish(ws, List.of(bank, credit, resume, cibil));
        List<String> codes = List.of(
                classified(ws, bank), classified(ws, credit),
                classified(ws, resume), classified(ws, cibil));
        assertThat(codes).allSatisfy(code -> assertThat(code).startsWith("induced."));
        assertThat(codes.stream().distinct().count()).isEqualTo(4);
        String dump = dump(facts(ws));
        assertThat(dump).contains("1250000").contains("CARE").contains("B.E. Electrical").contains("742");
    }

    private void assertType(DocText doc, String... expected) throws Exception {
        Workspace ws = workspace();
        long version = ingestPdf(ws, doc.fileName(), doc.logicalName(), doc.lines());
        finish(ws, List.of(version));
        assertThat(classified(ws, version)).startsWith("induced.");
        String dump = dump(facts(ws));
        for (String value : expected) {
            assertThat(dump).as(dump).contains(value);
        }
        assertThat(facts(ws)).allSatisfy(fact ->
                assertThat(fact.nomenclatureStatus()).isIn("bound", "unbound"));
        assertRouted(ws, version);
        MandateQa qa = qa(ws);
        assertThat(qa.inductionDecisions()).isNotEmpty();
        if (doc.passageTopic() != null) {
            assertThat(qa.passages()).as(qa.passages().toString())
                    .anySatisfy(row -> assertThat(row.topic()).isEqualTo(doc.passageTopic()));
        }
        if (doc.expectsCovenant()) {
            assertThat(qa.clauses()).as(qa.clauses().toString()).isNotEmpty();
        }
    }

    private void assertPair(DocText first, DocText second, String left, String right)
            throws Exception {
        Workspace ws = workspace();
        long a = ingestPdf(ws, first.fileName(), first.logicalName(), first.lines());
        long b = ingestPdf(ws, second.fileName(), second.logicalName(), second.lines());
        finish(ws, List.of(a, b));
        assertThat(classified(ws, a)).startsWith("induced.");
        assertThat(classified(ws, b)).isEqualTo(classified(ws, a));
        String dump = dump(facts(ws));
        assertThat(dump).contains(left).contains(right);
    }

    private void finish(Workspace ws, List<Long> versions) throws Exception {
        for (long version : versions) {
            DocExtractRunner.run(MANDATE, version, ws.db(), ws.blobs(), null, "fixture",
                    null, null, true, true);
        }
        DocAssessRunner.run(MANDATE, ws.db());
    }

    private Workspace workspace() throws Exception {
        Path db = tempDir.resolve("ws-" + System.nanoTime() + ".db");
        try (WorkspaceDatabase opened = WorkspaceDatabase.open(db)) {
            new MandatePolicyRepository(opened.connection())
                    .upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }
        return new Workspace(db, BlobStore.besideDatabase(db));
    }

    private long ingestPdf(Workspace ws, String fileName, String logicalName, List<String> lines)
            throws Exception {
        Path pdf = tempDir.resolve(fileName);
        writePdf(pdf, lines);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", "other", "other"),
                ws.db(), ws.blobs());
        new DocumentLayoutService().layout(ingested.documentVersionId(), ws.db(), ws.blobs());
        return ingested.documentVersionId();
    }

    private long ingestCibil(Workspace ws, String fileName, String score) throws Exception {
        Path png = tempDir.resolve(fileName);
        BufferedImage image = new BufferedImage(640, 200 + fileName.length(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(fileName.charAt(6), 1, 2, 1);
        graphics.dispose();
        ImageIO.write(image, "PNG", png.toFile());
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, MANDATE, "CIBIL Score", "v1", "other", "other"),
                ws.db(), ws.blobs());
        LocalOcrClient ocr = (ignored, width, height) -> new LocalOcrClient.OcrPage(List.of(
                word("CIBIL Score", 10, 10), word(score, 220, 10), word("bureau", 400, 10),
                word("CMR Rank", 10, 40), word("4", 220, 40), word("rank", 400, 40),
                word("Delinquency", 10, 70), word("noted", 220, 70), word("here", 400, 70),
                word("account_type", 10, 100), word("dpd", 220, 100), word("sma", 400, 100),
                word("Cash Credit", 10, 130), word("90", 220, 130), word("SMA-2", 400, 130)));
        new DocumentLayoutService(ocr).layout(ingested.documentVersionId(), ws.db(), ws.blobs());
        return ingested.documentVersionId();
    }

    private static LocalOcrClient.OcrWord word(String text, double x, double y) {
        return new LocalOcrClient.OcrWord(text, new BBox(x, y, x + 80, y + 18), 0.95);
    }

    private static void writePdf(Path pdf, List<String> lines) throws Exception {
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
    }

    private static String classified(Workspace ws, long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(ws.db())) {
            return new DocumentRepository(db.connection())
                    .findClassificationCode(versionId)
                    .orElse("missing");
        }
    }

    private static List<FactRow> facts(Workspace ws) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(ws.db())) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).includingHistory());
        }
    }

    private static String dump(List<FactRow> facts) {
        return facts.stream()
                .map(fact -> fact.fieldKey() + "=" + fact.valueText()
                        + " date=" + fact.valueDate()
                        + " line=" + fact.lineKey()
                        + " status=" + fact.nomenclatureStatus())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static void assertRouted(Workspace ws, long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(ws.db())) {
            try (var rs = db.connection().createStatement().executeQuery(
                    "SELECT routing_outcome FROM page WHERE document_version_id = " + versionId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotBlank();
            }
        }
    }

    private static MandateQa qa(Workspace ws) throws Exception {
        return MandateQaQueryRunner.query(ws.db(), MandateQaQuery.forMandate(MANDATE));
    }

    private record Workspace(Path db, BlobStore blobs) {}

    private record DocText(String fileName, String logicalName, List<String> lines,
            String passageTopic, boolean expectsCovenant) {}

    private static final DocText CREDIT = new DocText(
            "credit-a.pdf", "Credit Rating Report", List.of(
            "Credit Rating Report",
            "Rating Agency: CARE",
            "Credit Rating: AA+",
            "Long Term Rating: AA+",
            "Short Term Rating: A1+",
            "Rating Outlook: Stable",
            "Rating Rationale",
            "The rating reflects a stable operating profile and adequate liquidity.",
            "FINANCIAL COVENANT",
            "The borrower shall maintain DSCR above 1.20.",
            "Financial Covenant: DSCR above 1.20",
            "Facility | Borrower | Site | Tenor | Pricing",
            "Working Capital | Borrower | Plant | 5 years | 250 bps"),
            "rating_rationale", true);

    private static final DocText CREDIT_COPY = new DocText(
            "credit-b.pdf", "Credit Rating Report", List.of(
            "Credit Rating Report",
            "Rating Agency: CARE",
            "Credit Rating: AA",
            "Long Term Rating: AA",
            "Short Term Rating: A1",
            "Rating Outlook: Stable",
            "Rating Rationale",
            "The rating reflects a stable operating profile and adequate liquidity.",
            "FINANCIAL COVENANT",
            "The borrower shall maintain DSCR above 1.20.",
            "Financial Covenant: DSCR above 1.20",
            "Facility | Borrower | Site | Tenor | Pricing",
            "Working Capital | Borrower | Plant | 5 years | 250 bps"),
            "rating_rationale", true);

    private static final DocText DEBT = new DocText(
            "debt-a.pdf", "Debt Structuring Term Sheet", List.of(
            "Debt Structuring Term Sheet",
            "Facility Tranche: Term Loan",
            "Facility Pricing: 250 bps spread",
            "Facility Tenor: 12 years",
            "Moratorium: 18 months",
            "Repayment Schedule: quarterly",
            "Facility Security: first charge on land",
            "FINANCIAL COVENANT",
            "The borrower shall maintain DSCR above 1.25.",
            "Financial Covenant: DSCR above 1.25",
            "Tranche | Limit | Pricing | Tenor | Moratorium | Repayment",
            "Term Loan | 50000000 | 250 bps | 12 years | 18 months | quarterly"),
            null, true);

    private static final DocText DEBT_COPY = new DocText(
            "debt-b.pdf", "Debt Structuring Term Sheet", List.of(
            "Debt Structuring Term Sheet",
            "Facility Tranche: Term Loan",
            "Facility Pricing: 400 bps spread",
            "Facility Tenor: 12 years",
            "Moratorium: 18 months",
            "Repayment Schedule: quarterly",
            "Facility Security: first charge on land",
            "FINANCIAL COVENANT",
            "The borrower shall maintain DSCR above 1.25.",
            "Financial Covenant: DSCR above 1.25",
            "Tranche | Limit | Pricing | Tenor | Moratorium | Repayment",
            "Term Loan | 50000000 | 400 bps | 12 years | 18 months | quarterly"),
            null, true);

    private static final DocText BANK = new DocText(
            "bank-a.pdf", "Bank Statement", List.of(
            "Bank Statement",
            "Average Bank Balance: Rs. 1250000",
            "Summary",
            "Closing balance stays positive through the year.",
            "Metric | Month | Value",
            "Closing Balance | Jan | 1250000",
            "Inflow | Jan | 400000"),
            "account_conduct", false);

    private static final DocText BANK_COPY = new DocText(
            "bank-b.pdf", "Bank Statement", List.of(
            "Bank Statement",
            "Average Bank Balance: Rs. 990000",
            "Summary",
            "Closing balance stays positive through the year.",
            "Metric | Month | Value",
            "Closing Balance | Feb | 990000",
            "Inflow | Feb | 250000"),
            "account_conduct", false);

    private static final DocText RESUME = new DocText(
            "resume-a.pdf", "Director Profile", List.of(
            "Director Profile",
            "Educational Qualification: B.E. Electrical",
            "Promoter Experience: 18 years",
            "Experience",
            "Led plant operations across two sites.",
            "Qualification | Year",
            "B.E. Electrical | 2004",
            "Plant Head | 2012"),
            "career", false);

    private static final DocText RESUME_COPY = new DocText(
            "resume-b.pdf", "Director Profile", List.of(
            "Director Profile",
            "Educational Qualification: M.Tech",
            "Promoter Experience: 22 years",
            "Experience",
            "Led plant operations across two sites.",
            "Qualification | Year",
            "M.Tech | 2001",
            "Plant Head | 2008"),
            "career", false);

    private static final DocText VENDOR = new DocText(
            "vendor-a.pdf", "Vendor Proposal", List.of(
            "Vendor Proposal",
            "Payment Terms: 30 percent advance",
            "Validity: 2026-12-31",
            "Description | Qty | Rate | Amount",
            "Inverter | 2 | 45000 | 90000"),
            null, false);

    private static final DocText VENDOR_COPY = new DocText(
            "vendor-b.pdf", "Vendor Proposal", List.of(
            "Vendor Proposal",
            "Payment Terms: 20 percent advance",
            "Validity: 2026-11-30",
            "Description | Qty | Rate | Amount",
            "Transformer | 1 | 120000 | 120000"),
            null, false);
}
