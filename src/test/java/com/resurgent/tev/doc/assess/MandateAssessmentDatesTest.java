package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
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
 * Golden M3-01 suite (issue #134): assessment profile stage over synthetic born-digital
 * PDFs. Asserts persisted document dates through the mandate QA read API — never
 * prompts or internals — and that re-running replaces rather than duplicates.
 */
class MandateAssessmentDatesTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
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
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    @Test
    void statedExecutionDateStoredWithDayPrecisionAndPageProvenance() throws Exception {
        long versionId = ingestAndLayout(datedPpa(), "PPA - Dated", "agreement");
        classify(versionId, ppaPack);

        DocAssessRunner.Result result = DocAssessRunner.run(
                MANDATE, dbPath, new FakeDocLlmClient("{\"date\": null}"));

        assertThat(result.stages()).extracting(AssessStage.StageResult::stage)
                .contains("assess_profile");
        assertThat(result.stages().get(0).stage()).isEqualTo("assess_profile");

        MandateQa.DocumentDateRow row = docRow(versionId);
        assertThat(row.dateKind()).isEqualTo("execution");
        assertThat(row.documentDate()).isEqualTo("2024-03-12");
        assertThat(row.datePrecision()).isEqualTo("day");
        assertThat(row.dateSource()).isEqualTo("stated");
        assertThat(row.statedText()).contains("12 March 2024");
        assertThat(row.pageIndex()).isEqualTo(1);
        assertThat(row.uploadDate()).isNotBlank();

        assertThat(qa().stages()).extracting(MandateQa.AssessmentRunRow::stage)
                .contains("assess_profile");
        assertThat(qa().stages().get(0).stage()).isEqualTo("assess_profile");
        assertThat(qa().stages().get(0).status()).isEqualTo("completed");
        assertThat(qa().stages().get(0).parseRunId()).isNotNull();
    }

    /**
     * #151: the document LLM was down for the whole stage. A clean {@code completed} here is
     * the silent data loss the ticket is about — the mandate looks fully assessed.
     */
    @Test
    void profileStageRecordsTheLlmBeingDownInsteadOfClaimingClean() throws Exception {
        long versionId = ingestAndLayout(
                writePdf("quotation_down.pdf",
                        "Quotation for Supply of Solar Modules",
                        "Supply of 10 MW solar modules at the indicated rates."),
                "Vendor Quote - Down", "quotation");
        classify(versionId, quotationPack);

        DocAssessRunner.Result result = DocAssessRunner.run(
                MANDATE, dbPath, FakeDocLlmClient.failing("request timed out"));

        AssessStage.StageResult profile = result.stages().get(0);
        assertThat(profile.stage()).isEqualTo("assess_profile");
        // Partial, not clean: the date itself came from the file metadata, so the stage
        // recorded something — but every model read it makes was lost.
        assertThat(profile.status()).isEqualTo("partial");
        assertThat(profile.llmFailures()).isPositive();
        assertThat(result.summaryLines().get(0))
                .startsWith("assess_profile: ")
                .endsWith("[partial, " + profile.llmFailures() + " LLM calls failed]");

        MandateQa.AssessmentRunRow row = qaStage("assess_profile");
        assertThat(row.status()).isEqualTo("partial");
        assertThat(row.llmFailures()).isEqualTo(profile.llmFailures());

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            try (var rs = c.createStatement().executeQuery(
                    "SELECT status, llm_failures FROM assessment_run"
                            + " WHERE mandate_id = 1 AND stage = 'assess_profile'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo(profile.status());
                assertThat(rs.getLong("llm_failures")).isEqualTo(profile.llmFailures());
            }
        }
    }

    /**
     * #151: the model was down for the whole stage and the document had no date to fall
     * back to — not stated in its text, and no file metadata either. Nothing was recorded
     * at all, which is the one case {@code failed} is reserved for.
     */
    @Test
    void profileStageRecordsFailedWhenNothingAtAllWasStored() throws Exception {
        long versionId = ingestAndLayout(
                writePdf("undated_down.pdf",
                        "Certificate of Net Worth",
                        "Net worth of the promoter is Rs. 10 crore as certified."),
                "Promoter NW - Down", "certificate");
        classify(versionId, netWorthPack);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var ps = db.connection().prepareStatement(
                    "UPDATE document_version SET raw_metadata = NULL"
                            + " WHERE document_version_id = ?")) {
                ps.setLong(1, versionId);
                ps.executeUpdate();
            }
        }

        DocAssessRunner.Result result = DocAssessRunner.run(
                MANDATE, dbPath, FakeDocLlmClient.failing("request timed out"));

        AssessStage.StageResult profile = result.stages().get(0);
        assertThat(profile.stage()).isEqualTo("assess_profile");
        assertThat(profile.status()).isEqualTo("failed");
        assertThat(profile.llmFailures()).isPositive();
        assertThat(qaStage("assess_profile").status()).isEqualTo("failed");
        assertThat(result.summaryLines().get(0))
                .endsWith("[failed, " + profile.llmFailures() + " LLM calls failed]");
    }

    @Test
    void llmFallbackKeepsMonthPrecisionAndNeverInventsDay() throws Exception {
        long versionId = ingestAndLayout(
                writePdf("quotation_month.pdf",
                        "Quotation for Supply of Solar Modules",
                        "Supply of 10 MW solar modules at the indicated rates.",
                        "The prices quoted above are firm through March 2025."),
                "Vendor Quote - Solar", "quotation");
        classify(versionId, quotationPack);

        // "firm through" is not a deterministic date anchor: the LLM assists.
        FakeDocLlmClient fake = new FakeDocLlmClient(
                "{\"date\": \"March 2025\", \"quote\": \"firm through March 2025\"}");
        DocAssessRunner.run(MANDATE, dbPath, fake);
        // The full pipeline shares this fake (industry drafts and the checklist's
        // not-applicable read also ask), so count the date reads only: exactly one.
        assertThat(fake.callsWithSystemContaining("You read ONE date")).isEqualTo(1);

        MandateQa.DocumentDateRow row = docRow(versionId);
        assertThat(row.dateKind()).isEqualTo("offer");
        assertThat(row.documentDate()).isEqualTo("2025-03");
        assertThat(row.datePrecision()).isEqualTo("month");
        assertThat(row.dateSource()).isEqualTo("stated");
        assertThat(row.pageIndex()).isEqualTo(1);
        assertThat(row.statedText()).contains("firm through March 2025");
    }

    @Test
    void missingStatedDateFallsBackToFileMetadataElseNone() throws Exception {
        Path pdf = writePdf("undated_certificate.pdf",
                "Certificate of Net Worth",
                "Net worth of the promoter is Rs. 10 crore as certified.");
        Files.setLastModifiedTime(pdf, FileTime.from(Instant.parse("2024-05-01T09:30:00Z")));
        long versionId = ingestAndLayout(pdf, "Promoter NW - No Date", "certificate");
        classify(versionId, netWorthPack);

        long noMetadataVersion;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long documentId = docs.insertDocument(MANDATE, "No Metadata Doc", "certificate", null);
            noMetadataVersion = docs.insertDocumentVersion(documentId, "v1", "scan.pdf",
                    "hash-no-metadata", ".pdf", "certificate", 1, false, null, null);
        }

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"date\": null}"));

        MandateQa.DocumentDateRow meta = docRow(versionId);
        assertThat(meta.dateKind()).isEqualTo("issue");
        assertThat(meta.dateSource()).isEqualTo("file_metadata");
        assertThat(meta.documentDate()).isEqualTo("2024-05-01");
        assertThat(meta.datePrecision()).isEqualTo("day");
        assertThat(meta.statedText()).isNull();

        MandateQa.DocumentDateRow none = docRow(noMetadataVersion);
        assertThat(none.dateKind()).isEqualTo("date");
        assertThat(none.dateSource()).isEqualTo("none");
        assertThat(none.documentDate()).isNull();
        assertThat(none.datePrecision()).isNull();
    }

    @Test
    void reassessmentReplacesNeverDuplicates() throws Exception {
        long versionId = ingestAndLayout(datedPpa(), "PPA - Dated", "agreement");
        classify(versionId, ppaPack);

        FakeDocLlmClient fake = new FakeDocLlmClient("{\"date\": null}");
        DocAssessRunner.run(MANDATE, dbPath, fake);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(),
                    "SELECT COUNT(*) FROM provenance_doc WHERE entity_type = 'document_date'"))
                    .isEqualTo(1);
            assertThat(count(db.connection(),
                    "SELECT COUNT(*) FROM assessment_run WHERE mandate_id = " + MANDATE
                            + " AND stage = 'assess_profile'"))
                    .isEqualTo(1);
        }
        assertThat(qa().documents()).hasSize(1);
        assertThat(docRow(versionId).documentDate()).isEqualTo("2024-03-12");
        assertThat(qa().stages()).extracting(MandateQa.AssessmentRunRow::stage)
                .contains("assess_profile");
    }

    @Test
    void offlineAssessmentStillFindsStatedDate() throws Exception {
        long versionId = ingestAndLayout(datedPpa(), "PPA - Offline", "agreement");
        classify(versionId, ppaPack);

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa.DocumentDateRow row = docRow(versionId);
        assertThat(row.documentDate()).isEqualTo("2024-03-12");
        assertThat(row.dateSource()).isEqualTo("stated");
    }

    @Test
    void llmUsageAndRunStatsRecordedPerStage() throws Exception {
        long versionId = ingestAndLayout(
                writePdf("quotation_stats.pdf",
                        "Quotation for Supply of Solar Modules",
                        "The prices quoted above are firm through March 2025."),
                "Vendor Quote - Stats", "quotation");
        classify(versionId, quotationPack);

        DocAssessRunner.run(MANDATE, dbPath, new FakeDocLlmClient("{\"date\": null}"));

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            try (var rs = c.createStatement().executeQuery(
                    "SELECT model_id, calls, failed_calls, prompt_tokens, completion_tokens, cost_unit"
                            + " FROM llm_usage WHERE stage = 'assess_profile'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("model_id")).isEqualTo("fake-model");
                assertThat(rs.getLong("calls")).isEqualTo(1);
                assertThat(rs.getLong("failed_calls")).isZero();
                assertThat(rs.getLong("prompt_tokens")).isEqualTo(100);
                assertThat(rs.getLong("completion_tokens")).isEqualTo(50);
                // a chat-model row says what it counts in (#152); only LlamaCloud bills credits
                assertThat(rs.getString("cost_unit")).isEqualTo("usd");
                assertThat(rs.next()).isFalse();
            }
            Map<String, Double> stats = new HashMap<>();
            try (var rs = c.createStatement().executeQuery(
                    "SELECT name, value_num FROM run_stat WHERE stage = 'assess_profile'")) {
                while (rs.next()) {
                    stats.put(rs.getString("name"), rs.getDouble("value_num"));
                }
            }
            assertThat(stats)
                    .containsEntry("documents_total", 1.0)
                    .containsEntry("dates_stated", 0.0)
                    .containsEntry("dates_file_metadata", 1.0)
                    .containsEntry("dates_none", 0.0)
                    .containsEntry("llm_calls", 1.0);
        }
    }

    private Path datedPpa() throws Exception {
        return writePdf("dated_ppa.pdf",
                "Power Purchase Agreement",
                "This Power Purchase Agreement is executed on 12 March 2024 by and",
                "between the Seller and the Buyer.");
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

    private MandateQa.AssessmentRunRow qaStage(String stage) throws Exception {
        return qa().stages().stream()
                .filter(r -> r.stage().equals(stage))
                .findFirst()
                .orElseThrow();
    }

    private MandateQa.DocumentDateRow docRow(long versionId) throws Exception {
        return qa().documents().stream()
                .filter(r -> r.documentVersionId() == versionId)
                .findFirst()
                .orElseThrow();
    }

    private static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
