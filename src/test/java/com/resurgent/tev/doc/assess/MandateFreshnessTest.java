package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocAssessCommand;
import com.resurgent.tev.doc.cli.DocSnapshotCommand;
import com.resurgent.tev.doc.cli.DocStaleCommand;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactSnapshot;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
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
 * Golden integration test suite for Milestone 4 (spec #162, issue #169, ADR 0026, ADR 0028):
 * - Multi-version document ingestion lifecycle (V1 quote -> snapshot -> V2 revised quote).
 * - Per-fact supersession with older facts flipped to is_current = 0 and linked to superseded_by_fact_id.
 * - Conflict scan settles versions-differ as Tier 1 rule settlement (version_precedence_v1).
 * - AssessFreshnessRunner executes as stage 'assess_freshness' in assessment_run.
 * - Stale invalidation pass immediately flags fact snapshots referencing superseded facts as is_stale = 1.
 * - stale_reason explicitly names the superseded fact ID and its superseding fact ID.
 * - CLI commands (tev-doc snapshot, tev-doc stale, tev-doc assess) function cleanly in human-readable and JSON modes.
 */
class MandateFreshnessTest {

    private static final long MANDATE = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack quotationPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
        }
        blobs = new BlobStore(tempDir.resolve("blobs"));
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
    }

    @Test
    void endToEndMultiVersionLifecycleSnapshotCaptureAndFreshnessEvaluation() throws Exception {
        // Step 1: Ingest V1 quotation (dated 2024-01-10, Rs. 1,00,00,000)
        Path pdfV1 = quotationPdf("quote_v1.pdf", "QT-100", "Jaisingh Pura", "2024-01-10", "Rs. 1,00,00,000");
        long v1VersionId = ingest(pdfV1, "Vendor Quotation", "v1", "quotation", quotationPack,
                quotationFixture("Jaisingh Pura", "2024-01-10", "Rs. 1,00,00,000"));

        // Initial assessment run
        Run assessV1 = run(new DocAssessCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(assessV1.exitCode()).isZero();
        assertThat(assessV1.out()).contains("assess_freshness: 0 snapshots evaluated (0 stale, 0 fresh)");

        // Verify V1 facts are current
        List<FactRow> v1Facts = queryFacts();
        assertThat(v1Facts).isNotEmpty();
        assertThat(v1Facts).allMatch(FactRow::isCurrent);
        FactRow grossTotalV1 = v1Facts.stream()
                .filter(f -> "gross_total".equals(f.fieldKey()))
                .findFirst()
                .orElseThrow();
        assertThat(grossTotalV1.valueText()).isEqualTo("Rs. 1,00,00,000");

        // Step 2: Capture snapshots via CLI
        // Snapshot 1: human readable mode linking all current facts
        Run snap1 = run(new DocSnapshotCommand(),
                "--mandate", Long.toString(MANDATE),
                "--db", dbPath.toString(),
                "--type", "tev_summary",
                "--key", "capex_schedule");
        assertThat(snap1.exitCode()).isZero();
        assertThat(snap1.out()).contains("Captured snapshot").contains("tev_summary/capex_schedule");

        // Snapshot 2: JSON mode linking specifically gross_total fact
        Run snap2 = run(new DocSnapshotCommand(),
                "--mandate", Long.toString(MANDATE),
                "--db", dbPath.toString(),
                "--type", "cma_extract",
                "--key", "table_1",
                "--facts", Long.toString(grossTotalV1.factId()),
                "--json");
        assertThat(snap2.exitCode()).isZero();
        JsonNode snap2Json = MAPPER.readTree(snap2.out());
        assertThat(snap2Json.get("output_type").asText()).isEqualTo("cma_extract");
        assertThat(snap2Json.get("output_key").asText()).isEqualTo("table_1");
        assertThat(snap2Json.get("is_stale").asBoolean()).isFalse();
        assertThat(snap2Json.get("facts_count").asInt()).isEqualTo(1);

        // Verify stale check via CLI returns empty
        Run staleBefore = run(new DocStaleCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(staleBefore.exitCode()).isZero();
        assertThat(staleBefore.out()).contains("No stale snapshots found");

        Run staleBeforeJson = run(new DocStaleCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString(), "--json");
        assertThat(staleBeforeJson.exitCode()).isZero();
        JsonNode staleListBefore = MAPPER.readTree(staleBeforeJson.out());
        assertThat(staleListBefore.isArray()).isTrue();
        assertThat(staleListBefore.size()).isZero();

        // Step 3: Ingest V2 quotation (dated 2024-02-15, revised total Rs. 1,20,00,000)
        Path pdfV2 = quotationPdf("quote_v2.pdf", "QT-100-REV", "Jaisingh Pura", "2024-02-15", "Rs. 1,20,00,000");
        long v2VersionId = ingest(pdfV2, "Vendor Quotation", "v2", "quotation", quotationPack,
                quotationFixture("Jaisingh Pura", "2024-02-15", "Rs. 1,20,00,000"));

        // Step 4: Run assess pipeline (DocAssessCommand / DocAssessRunner)
        Run assessV2 = run(new DocAssessCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(assessV2.exitCode()).isZero();
        assertThat(assessV2.out()).contains("assess_freshness: 2 snapshots evaluated (2 stale, 0 fresh)");

        // Step 5: Verify fact supersession state
        List<FactRow> allFacts = com.resurgent.tev.doc.db.FactQueryRunner.query(
                dbPath, FactQuery.forMandate(MANDATE).includingHistory());
        FactRow supersededFact = allFacts.stream()
                .filter(f -> f.factId() == grossTotalV1.factId())
                .findFirst()
                .orElseThrow();
        assertThat(supersededFact.isCurrent()).isFalse();
        assertThat(supersededFact.supersededByFactId()).isNotNull();

        FactRow supersedingFact = allFacts.stream()
                .filter(f -> f.factId() == supersededFact.supersededByFactId())
                .findFirst()
                .orElseThrow();
        assertThat(supersedingFact.isCurrent()).isTrue();
        assertThat(supersedingFact.valueText()).isEqualTo("Rs. 1,20,00,000");

        // Step 6: Verify assessment_run and run_stat for assess_freshness
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection conn = db.connection();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT status, llm_failures, metrics_json FROM assessment_run WHERE mandate_id = "
                                 + MANDATE + " AND stage = 'assess_freshness'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("completed");
                assertThat(rs.getLong("llm_failures")).isEqualTo(0L);

                JsonNode metrics = MAPPER.readTree(rs.getString("metrics_json"));
                assertThat(metrics.get("snapshots_total").asInt()).isEqualTo(2);
                assertThat(metrics.get("snapshots_stale").asInt()).isEqualTo(2);
                assertThat(metrics.get("snapshots_fresh").asInt()).isEqualTo(0);
                assertThat(metrics.get("snapshots_invalidated").asInt()).isEqualTo(2);
            }

            // Verify run_stat records
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT name, value_num FROM run_stat WHERE stage = 'assess_freshness'")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                assertThat(count).isGreaterThanOrEqualTo(3);
            }
        }

        // Step 7: Verify snapshots are marked is_stale = 1 with descriptive stale_reason
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactSnapshotRepository snapshots = new FactSnapshotRepository(db.connection());
            List<FactSnapshot> staleSnapshots = snapshots.findStaleSnapshots(MANDATE);
            assertThat(staleSnapshots).hasSize(2);
            for (FactSnapshot snap : staleSnapshots) {
                assertThat(snap.isStale()).isTrue();
                assertThat(snap.staleAt()).isNotBlank();
                // stale_reason explicitly names the superseded fact ID and its superseding fact ID
                assertThat(snap.staleReason())
                        .contains("fact #" + grossTotalV1.factId())
                        .contains("was superseded by fact #" + supersedingFact.factId());
            }
        }

        // Step 8: Verify CLI stale command in human-readable and JSON modes
        Run staleAfter = run(new DocStaleCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(staleAfter.exitCode()).isZero();
        assertThat(staleAfter.out()).contains("Snapshot #")
                .contains("stale since")
                .contains("fact #" + grossTotalV1.factId())
                .contains("was superseded by fact #" + supersedingFact.factId())
                .contains("(2 stale snapshots)");

        Run staleAfterJson = run(new DocStaleCommand(), "--mandate", Long.toString(MANDATE), "--db", dbPath.toString(), "--json");
        assertThat(staleAfterJson.exitCode()).isZero();
        JsonNode staleListAfter = MAPPER.readTree(staleAfterJson.out());
        assertThat(staleListAfter.isArray()).isTrue();
        assertThat(staleListAfter.size()).isEqualTo(2);
        JsonNode snapNode = staleListAfter.get(0);
        assertThat(snapNode.get("is_stale").asBoolean()).isTrue();
        assertThat(snapNode.get("stale_reason").asText())
                .contains("fact #" + grossTotalV1.factId())
                .contains("was superseded by fact #" + supersedingFact.factId());
    }

    // --- Helpers --------------------------------------------------------------

    private List<FactRow> queryFacts() throws Exception {
        return com.resurgent.tev.doc.db.FactQueryRunner.query(
                dbPath, FactQuery.forMandate(MANDATE));
    }

    private Path quotationPdf(String fileName, String reference, String projectName,
            String dateText, String total) throws Exception {
        Path pdf = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 720;
                List<String> lines = List.of(
                        "Vendor Quotation",
                        "Quotation Reference: " + reference,
                        "Vendor: Sunbeam Solar Systems Pvt Ltd",
                        "Project: " + projectName,
                        "Date: " + dateText,
                        "Total: " + total);
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
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId).orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    record Run(int exitCode, String out, String err) {}

    static Run run(Callable<Integer> command, String... args) {
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }
}
