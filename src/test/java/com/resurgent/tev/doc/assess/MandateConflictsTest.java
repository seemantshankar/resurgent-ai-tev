package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocQaCommand;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
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
 * Golden M3-12 suite (issue #145, ADR 0026): conflicts between documents over the same
 * data point — precedence rule, AI judgement, open (checklist Conflicting) and
 * versions-differ for differently dated revisions — over synthetic born-digital PDFs
 * in a temp {@code workspace.db}, asserted through the mandate QA read API only.
 */
class MandateConflictsTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack dprPack;
    private DocTypePack ppaPack;
    private DocTypePack quotationPack;
    private DocTypePack netWorthPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        dprPack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    // --- AC2: DPR tariff vs PPA tariff settles by a precedence rule -----------

    @Test
    void dprTariffVsPpaTariffSettlesByPrecedenceRule() throws Exception {
        ingest(dprPdf("Rs. 3.80/kWh"), "DPR - Jaisingh Pura", "v1", "dpr", dprPack,
                dprFixture("Rs. 3.80/kWh"));
        ingest(ppaPdf("Rs. 4.15/kWh"), "PPA - Jaisingh Pura", "v1", "agreement", ppaPack,
                ppaFixture("Rs. 4.15/kWh"));

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("doc-doc");
        assertThat(conflict.dataPoint()).isEqualTo("tariff");
        assertThat(conflict.settlement()).isEqualTo("rule");
        assertThat(conflict.ruleId()).isEqualTo("agreement-over-narrative");
        assertThat(conflict.rulesVersion()).isEqualTo("1.0.0");
        assertThat(conflict.chosenSide()).isIn("left", "right");
        // The governing agreement wins; the losing DPR value is kept, not overwritten.
        assertThat(conflict.chosenValue()).isEqualTo("Rs. 4.15/kWh");
        assertThat(conflict.rejectedValue()).isEqualTo("Rs. 3.80/kWh");
        assertThat(conflict.reasoning()).isNotBlank();
        assertThat(conflict.left().value()).isNotBlank();
        assertThat(conflict.right().value()).isNotBlank();
        assertThat(conflict.left().documentVersionId()).isNotNull();
        assertThat(conflict.right().documentVersionId()).isNotNull();
        assertThat(conflict.siteName()).isEqualTo("Jaisingh Pura");
        // Different document types are never "versions differ", whatever the dates.
        assertThat(qa.versionsDiffer()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM conflict")).isEqualTo(1);
            // A rule-settled conflict raises no review flag.
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category IN ('conflicting', 'settled_by_ai')")).isZero();
        }

        // qa shows the conflict and its settlement in text and JSON.
        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict tariff")
                .contains("agreement-over-narrative")
                .contains("Rs. 4.15/kWh")
                .contains("Rs. 3.80/kWh");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).hasSize(1);
        assertThat(root.get("conflicts").get(0).get("settlement").asText()).isEqualTo("rule");
        assertThat(root.get("conflicts").get(0).get("rule_id").asText())
                .isEqualTo("agreement-over-narrative");
        assertThat(root.get("versions_differ")).isEmpty();
    }

    // --- AC3: a disagreement no rule covers, settled by AI and flagged --------

    @Test
    void uncoveredQuotationDisagreementIsSettledByAiWithReasoningAndFlagged()
            throws Exception {
        ingest(quotationPdf("quotation_qt1.pdf", "QT-1", "12 April 2026", "Rs. 48,20,00,000"),
                "Quotation - Sunbeam main scope", "v1", "quotation", quotationPack,
                quotationFixture("12 April 2026", "Rs. 48,20,00,000"));
        ingest(quotationPdf("quotation_qt2.pdf", "QT-2", "12 April 2026", "Rs. 52,60,00,000"),
                "Quotation - Sunbeam revised scope", "v1", "quotation", quotationPack,
                quotationFixture("12 April 2026", "Rs. 52,60,00,000"));

        // No precedence rule covers quotation vs quotation: the fake model is confident
        // and settles the disagreement, citing the pages.
        String aiReply = "{\"settle\": true, \"chosen_side\": \"left\", \"reasoning\":"
                + " \"the left quotation on page 1 itemises the full scope while the"
                + " right page 1 covers a narrower scope, so the left value governs\"}";
        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm(aiReply));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("doc-doc");
        assertThat(conflict.dataPoint()).isEqualTo("gross_total");
        assertThat(conflict.settlement()).isEqualTo("ai");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.rulesVersion()).isNull();
        assertThat(conflict.chosenSide()).isEqualTo("left");
        assertThat(conflict.chosenValue()).isEqualTo("Rs. 48,20,00,000");
        assertThat(conflict.rejectedValue()).isEqualTo("Rs. 52,60,00,000");
        assertThat(conflict.reasoning()).contains("page");
        assertThat(qa.versionsDiffer()).isEmpty();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            // Both values stay on the fact table — the losing value is not overwritten.
            assertThat(count(c, "SELECT COUNT(*) FROM conflict")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'gross_total'"))
                    .isEqualTo(2);
            // Settled by AI is flagged (visible in qa) but requires no action.
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'settled_by_ai'")).isEqualTo(1);
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT status, summary FROM review_queue"
                            + " WHERE category = 'settled_by_ai'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Resolved");
                assertThat(rs.getString("summary")).contains("settled by AI");
            }
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'conflicting'")).isZero();
        }

        // An AI-settled data point is not Conflicting: the checklist stays clean.
        assertThat(checklistRow(qa, "quotation_gross_total").status()).isEqualTo("provided");

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict gross_total")
                .contains("settled by AI")
                .contains("Rs. 48,20,00,000");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).hasSize(1);
        assertThat(root.get("conflicts").get(0).get("settlement").asText()).isEqualTo("ai");
        assertThat(root.get("conflicts").get(0).get("chosen_value").asText())
                .isEqualTo("Rs. 48,20,00,000");
    }

    // --- AC4: the model declines — the item stays Conflicting and is flagged ---

    @Test
    void declinedSettlementLeavesTheItemConflictingAndFlagged() throws Exception {
        ingest(quotationPdf("quotation_qt1.pdf", "QT-1", "12 April 2026", "Rs. 48,20,00,000"),
                "Quotation - Sunbeam main scope", "v1", "quotation", quotationPack,
                quotationFixture("12 April 2026", "Rs. 48,20,00,000"));
        ingest(quotationPdf("quotation_qt2.pdf", "QT-2", "12 April 2026", "Rs. 52,60,00,000"),
                "Quotation - Sunbeam alternate scope", "v1", "quotation", quotationPack,
                quotationFixture("12 April 2026", "Rs. 52,60,00,000"));

        // No rule covers quotation vs quotation, and the fake model declines to pick.
        String declined = "{\"settle\": false, \"reasoning\":"
                + " \"both quotations are credible for their scopes, I cannot tell\"}";
        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm(declined));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.dataPoint()).isEqualTo("gross_total");
        assertThat(conflict.settlement()).isEqualTo("open");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.rulesVersion()).isNull();
        assertThat(conflict.chosenSide()).isNull();
        assertThat(conflict.chosenValue()).isNull();
        assertThat(conflict.rejectedValue()).isNull();
        assertThat(conflict.reasoning()).contains("no precedence rule");
        assertThat(qa.versionsDiffer()).isEmpty();

        // Tier 3: the checklist item shows Conflicting and the open conflict is flagged.
        MandateQa.ChecklistResultRow item = checklistRow(qa, "quotation_gross_total");
        assertThat(item.status()).isEqualTo("conflicting");
        assertThat(item.reason())
                .contains("Rs. 48,20,00,000")
                .contains("Rs. 52,60,00,000");
        assertThat(qa.checklistSummary().stream()
                .filter(summary -> "overall".equals(summary.scopeKind())
                        && "conflicting".equals(summary.status()))
                .findFirst().orElseThrow().itemCount()).isEqualTo(1);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            // Nothing is overwritten: both states stay on the fact table.
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'gross_total'"))
                    .isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'conflicting'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'settled_by_ai'")).isZero();
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT status, summary FROM review_queue"
                            + " WHERE category = 'conflicting'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Pending");
                assertThat(rs.getString("summary")).contains("Conflict on gross_total");
            }
        }

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict gross_total")
                .contains("open: no settlement, flagged conflicting")
                .contains("checklist quotation_gross_total");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).hasSize(1);
        assertThat(root.get("conflicts").get(0).get("settlement").asText()).isEqualTo("open");
        assertThat(root.get("conflicts").get(0).get("chosen_value").isNull()).isTrue();
        assertThat(jsonChecklistRow(root, "quotation_gross_total").get("status").asText())
                .isEqualTo("conflicting");
    }

    // --- AC5: a later-dated revision is versions differ, newest first ----------

    @Test
    void revisedQuotationWithLaterDateIsVersionsDifferNewestFirst() throws Exception {
        long olderVersion = ingest(
                quotationPdf("quotation_qt1.pdf", "QT-1", "12 April 2026", "Rs. 48,20,00,000"),
                "Quotation - Sunbeam main scope", "v1", "quotation", quotationPack,
                quotationFixture("12 April 2026", "Rs. 48,20,00,000"));
        long newerVersion = ingest(
                quotationPdf("quotation_qt5.pdf", "QT-5", "20 May 2026", "Rs. 52,60,00,000"),
                "Quotation - Sunbeam revised scope", "v1", "quotation", quotationPack,
                quotationFixture("20 May 2026", "Rs. 52,60,00,000"));

        // A confident settlement reply is available but must never be needed: two
        // differently dated quotations of one type are a revision, not a conflict.
        String confident = "{\"settle\": true, \"chosen_side\": \"left\", \"reasoning\":"
                + " \"the newer quotation supersedes the earlier one\"}";
        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm(confident));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).hasSize(1);
        MandateQa.ConflictRow row = qa.versionsDiffer().get(0);
        assertThat(row.kind()).isEqualTo("versions-differ");
        assertThat(row.dataPoint()).isEqualTo("gross_total");
        assertThat(row.settlement()).isNull();
        assertThat(row.chosenSide()).isNull();
        // Newest first: the later document date is the left side; the older
        // statement is kept on the right, not overwritten.
        assertThat(row.left().fileName()).isEqualTo("quotation_qt5.pdf");
        assertThat(row.left().value()).isEqualTo("Rs. 52,60,00,000");
        assertThat(row.right().fileName()).isEqualTo("quotation_qt1.pdf");
        assertThat(row.right().value()).isEqualTo("Rs. 48,20,00,000");
        assertThat(row.reasoning()).contains("M4");

        // The document dates (#134) that make it a revision are persisted.
        assertThat(documentDate(qa, newerVersion)).isEqualTo("2026-05-20");
        assertThat(documentDate(qa, olderVersion)).isEqualTo("2026-04-12");

        // A revision disturbs nothing: no Conflicting item, no settlement flag.
        assertThat(checklistRow(qa, "quotation_gross_total").status()).isEqualTo("provided");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category IN ('conflicting', 'settled_by_ai')")).isZero();
        }

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("versions differ gross_total")
                .contains("versions differ: 1");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).isEmpty();
        assertThat(root.get("versions_differ")).hasSize(1);
        assertThat(root.get("versions_differ").get(0).get("kind").asText())
                .isEqualTo("versions-differ");
    }

    // --- AC6: tolerance, periods and sites keep non-conflicts apart ------------

    @Test
    void valuesWithinOnePercentToleranceCreateNoConflict() throws Exception {
        // 3.80 vs 3.83 is 0.79% apart — inside the ~1% tolerance: agreement, and the
        // precedence rule the pair would otherwise hit is never even consulted.
        ingest(dprPdf("Rs. 3.80/kWh"), "DPR - Jaisingh Pura", "v1", "dpr", dprPack,
                dprFixture("Rs. 3.80/kWh"));
        ingest(ppaPdf("Rs. 3.83/kWh"), "PPA - Jaisingh Pura", "v1", "agreement", ppaPack,
                ppaFixture("Rs. 3.83/kWh"));

        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm("{}"));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            // Both statements were extracted — the pair was compared and agreed.
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'tariff'"))
                    .isEqualTo(2);
        }
    }

    @Test
    void differentAsOnDatesCreateNoConflict() throws Exception {
        // The same data point on two different as-on dates is two readings, never a
        // conflict — the values themselves differ by 20%.
        ingest(netWorthPdf("networth_2024.pdf", "31 March 2024", "Rs. 10 crore"),
                "Net Worth - 2024", "v1", "certificate", netWorthPack,
                netWorthFixture("31 March 2024", "Rs. 10 crore"));
        ingest(netWorthPdf("networth_2025.pdf", "31 March 2025", "Rs. 12 crore"),
                "Net Worth - 2025", "v1", "certificate", netWorthPack,
                netWorthFixture("31 March 2025", "Rs. 12 crore"));

        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm("{}"));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'net_worth'"))
                    .isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'as_of_date'"))
                    .isEqualTo(2);
        }
    }

    @Test
    void differentSitesAreNeverCompared() throws Exception {
        // The same data point for two different project sites: never compared, even
        // though the quotations share a document type and a date — no row at all.
        ingest(quotationPdf("quotation_jaisingh.pdf", "QT-7", "Jaisingh Pura", "12 April 2026",
                        "Rs. 48,20,00,000"),
                "Quotation - Jaisingh Pura", "v1", "quotation", quotationPack,
                quotationFixture("Jaisingh Pura", "12 April 2026", "Rs. 48,20,00,000"));
        ingest(quotationPdf("quotation_bhagyanagar.pdf", "QT-8", "Bhagyanagar", "12 April 2026",
                        "Rs. 52,60,00,000"),
                "Quotation - Bhagyanagar", "v1", "quotation", quotationPack,
                quotationFixture("Bhagyanagar", "12 April 2026", "Rs. 52,60,00,000"));

        DocAssessRunner.run(MANDATE, dbPath, new ConflictLlm("{}"));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).isEmpty();
        // Both sites are known and the two statements carry their own site.
        assertThat(qa.sites()).extracting(MandateQa.SiteRow::siteName)
                .contains("Jaisingh Pura", "Bhagyanagar");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'gross_total'"))
                    .isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(DISTINCT site_id) FROM fact"
                    + " WHERE field_key = 'gross_total'")).isEqualTo(2);
        }
    }

    // --- fixtures -------------------------------------------------------------

    private Path dprPdf(String tariff) throws Exception {
        return writePdf("dpr_jaisingh_pura.pdf", List.of(
                "Detailed Project Report",
                "Land location: Village Jaisingh Pura",
                "Indicative tariff: " + tariff,
                "Report Date: 10 June 2026"));
    }

    private static JsonNode dprFixture(String tariff) throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "report.dpr",
                  "fields": {
                    "land_location": {"value": "Jaisingh Pura",
                      "quote": "Land location: Village Jaisingh Pura", "page": 1},
                    "tariff": {"value": "%s",
                      "quote": "Indicative tariff: %s", "page": 1}
                  }
                }""".formatted(tariff, tariff));
    }

    private Path ppaPdf(String tariff) throws Exception {
        return writePdf("ppa_jaisingh_pura.pdf", List.of(
                "Power Purchase Agreement",
                "Seller: Sunbeam Energy Pvt Ltd",
                "Project: Jaisingh Pura",
                "Tariff: " + tariff,
                "Executed on 20 May 2026"));
    }

    private static JsonNode ppaFixture(String tariff) throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "agreement.ppa",
                  "fields": {
                    "seller_name": {"value": "Sunbeam Energy Pvt Ltd",
                      "quote": "Seller: Sunbeam Energy Pvt Ltd", "page": 1},
                    "tariff": {"value": "%s",
                      "quote": "Tariff: %s", "page": 1}
                  }
                }""".formatted(tariff, tariff));
    }

    private Path netWorthPdf(String fileName, String asOn, String netWorth) throws Exception {
        return writePdf(fileName, List.of(
                "Certificate of Net Worth",
                "Promoter: RAMESH KUMAR SHARMA",
                "Net worth as on " + asOn + ": " + netWorth));
    }

    private static JsonNode netWorthFixture(String asOn, String netWorth) throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "identity.promoter_net_worth",
                  "fields": {
                    "subject_name": {"value": "RAMESH KUMAR SHARMA",
                      "quote": "Promoter: RAMESH KUMAR SHARMA", "page": 1},
                    "as_of_date": {"value": "%s",
                      "quote": "Net worth as on %s", "page": 1},
                    "net_worth": {"value": "%s",
                      "quote": "Net worth as on %s: %s", "page": 1}
                  }
                }""".formatted(asOn, asOn, netWorth, asOn, netWorth));
    }

    private Path quotationPdf(String fileName, String reference, String dateText, String total)
            throws Exception {
        return quotationPdf(fileName, reference, "Jaisingh Pura", dateText, total);
    }

    private Path quotationPdf(String fileName, String reference, String projectName,
            String dateText, String total) throws Exception {
        return writePdf(fileName, List.of(
                "Vendor Quotation",
                "Quotation Reference: " + reference,
                "Vendor: Sunbeam Solar Systems Pvt Ltd",
                "Project: " + projectName,
                "Date: " + dateText,
                "Total: " + total));
    }

    private static JsonNode quotationFixture(String dateText, String total) throws Exception {
        return quotationFixture("Jaisingh Pura", dateText, total);
    }

    private static JsonNode quotationFixture(String projectName, String dateText, String total)
            throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "quotation.vendor",
                  "fields": {
                    "vendor_name": {"value": "Sunbeam Solar Systems Pvt Ltd",
                      "quote": "Vendor: Sunbeam Solar Systems Pvt Ltd", "page": 1},
                    "quote_date": {"value": "%s",
                      "quote": "Date: %s", "page": 1},
                    "project_name": {"value": "%s",
                      "quote": "Project: %s", "page": 1},
                    "gross_total": {"value": "%s",
                      "quote": "Total: %s", "page": 1}
                  }
                }""".formatted(dateText, dateText, projectName, projectName, total, total));
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

    /** Ingests one PDF, lays it out, classifies it and extracts the fixture facts. */
    private long ingest(Path pdf, String logicalName, String versionLabel, String fileRole,
            DocTypePack pack, JsonNode fixture) throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, versionLabel, fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        classify(ingested.documentVersionId(), pack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.putFixture(pack.code(), fixture);
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, ingested.documentVersionId(), MANDATE, pack, extractClient,
                FixtureExtractClient.ID);
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
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

    /** The one mandate-granularity checklist row for an item key. */
    private static MandateQa.ChecklistResultRow checklistRow(MandateQa qa, String itemKey) {
        return qa.checklist().stream()
                .filter(row -> itemKey.equals(row.itemKey()) && row.entityId() == null
                        && row.siteId() == null)
                .findFirst()
                .orElse(null);
    }

    /** The persisted document date of one version (#134). */
    private static String documentDate(MandateQa qa, long documentVersionId) {
        return qa.documents().stream()
                .filter(row -> row.documentVersionId() == documentVersionId)
                .findFirst()
                .orElseThrow()
                .documentDate();
    }

    /** The JSON checklist row for an item key. */
    private static JsonNode jsonChecklistRow(JsonNode root, String itemKey) {
        for (JsonNode row : root.get("checklist")) {
            if (itemKey.equals(row.get("item_key").asText())) {
                return row;
            }
        }
        throw new AssertionError("no JSON checklist row for " + itemKey);
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

    /** Deterministic fake over the doc LLM seam, driven by markers in the sent text. */
    private static final class ConflictLlm implements DocLlmClient {

        private final String settlementReply;

        private ConflictLlm(String settlementReply) {
            this.settlementReply = settlementReply;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            if (user.contains("Quotation Reference: QT-5")) {
                return reply("{\"date\": \"20 May 2026\", \"quote\": \"Date: 20 May 2026\"}");
            }
            if (user.contains("Quotation Reference: QT-")) {
                return reply("{\"date\": \"12 April 2026\", \"quote\": \"Date: 12 April 2026\"}");
            }
            if (user.contains("gross_total")) {
                return reply(settlementReply);
            }
            return reply("{}");
        }

        private static Completion reply(String content) {
            return new Completion(content, "fake-model", 100, 50, 0.002, 12);
        }
    }
}
