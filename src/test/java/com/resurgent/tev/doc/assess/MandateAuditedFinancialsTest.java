package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.compare.Comparisons;
import com.resurgent.tev.doc.compare.Verdict;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.MandateQa;
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
 * Golden M3-07 suite (issue #140): audited financials are recorded by entity —
 * presence (entity, financial years, auditor) and the four headline figures
 * (revenue, PAT, net worth, total borrowings) per financial year, each cited and
 * tagged with its entity and its financial year as its period. The seed pack rides
 * M2's pack-driven classify/extract/bind with no new stage; assertions read
 * persisted rows through the fact/QA read APIs, never prompts or internals.
 */
class MandateAuditedFinancialsTest {

    private static final long MANDATE = 1L;

    private static final String PACK_CODE = "financials.audited_summary";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The four headline figures the pack records per financial year. */
    private static final List<String> HEADLINE_FIGURES =
            List.of("revenue", "pat", "net_worth", "total_borrowings");

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack pack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        pack = PackLoader.loadClasspath("docpacks/financials.audited_summary.yaml");
    }

    @Test
    void auditedSummaryYieldsEntityTwoYearsAuditorAndHeadlineFiguresPerYearWithProvenance()
            throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        long auditedVersion = ingestAndLayout(auditedSummaryPdf(),
                "Audited Financials - ABC", "audited_financials");

        // M2's pack-driven classify picks the seed pack up among all seed candidates.
        ClassifyResult classified = classify(auditedVersion);
        assertThat(classified.docTypeCode()).isEqualTo(PACK_CODE);

        ExtractService.ExtractOutcome extract = extractAudited(auditedVersion);
        assertThat(extract.status()).isEqualTo("success");
        // presence (entity, two financial years, auditor) + four figures x two years
        assertThat(extract.factsTotal()).isEqualTo(11);
        new PackPathBinder().bind(dbPath, extract.parseRunId(), pack);

        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        MandateQa.EntityRow borrower = qa().entities().stream()
                .filter(e -> "borrower".equals(e.role()))
                .findFirst()
                .orElseThrow();
        assertThat(borrower.name()).isEqualTo("ABC Pvt Ltd");

        List<FactRow> facts = facts();

        // presence: the entity, both financial years, the auditor
        assertThat(fact(facts, "subject_name").valueText()).isEqualTo("ABC Pvt Ltd");
        FactRow years = fact(facts, "financial_years");
        assertThat(years.valueText()).isEqualTo("FY2023-24 and FY2024-25");
        assertThat(pageOf(years)).isEqualTo(1);
        FactRow auditor = fact(facts, "auditor_name");
        assertThat(auditor.valueText()).isEqualTo("M/s Verma & Associates");
        assertThat(quoteOf(auditor)).isEqualTo("Auditor: M/s Verma & Associates");

        // four headline figures per year, each cited with page provenance, each
        // tagged with its entity and its financial year as its period
        for (String key : HEADLINE_FIGURES) {
            List<FactRow> readings = factsOf(facts, key);
            assertThat(readings).hasSize(2);
            assertThat(readings).extracting(MandateAuditedFinancialsTest::periodOf)
                    .containsExactlyInAnyOrder("FY2023-24", "FY2024-25");
            assertThat(readings).allSatisfy(f -> {
                assertThat(f.entityId()).isEqualTo(borrower.entityId());
                assertThat(f.entityReason()).isNotBlank();
                assertThat(pageOf(f)).isEqualTo(2);
                assertThat(f.ontologyPath()).startsWith("economic > Audited Financials");
                assertThat(f.currency()).isEqualTo("INR");
                assertThat(f.scale()).isEqualTo("crore");
            });
        }

        // independent literals: the stated figures normalise to absolute rupees
        FactRow netWorth24 = reading(facts, "net_worth", "FY2023-24");
        assertThat(netWorth24.valueText()).isEqualTo("Rs 18.5 crore");
        assertThat(netWorth24.absoluteAmount()).isEqualTo("185000000");
        assertThat(quoteOf(netWorth24)).isEqualTo("Net Worth 18.5");
        FactRow revenue25 = reading(facts, "revenue", "FY2024-25");
        assertThat(revenue25.valueText()).isEqualTo("Rs 48.3 crore");
        assertThat(revenue25.absoluteAmount()).isEqualTo("483000000");
        assertThat(quoteOf(revenue25)).isEqualTo("Revenue 48.3");
        FactRow borrowings24 = reading(facts, "total_borrowings", "FY2023-24");
        assertThat(borrowings24.absoluteAmount()).isEqualTo("260000000");
        FactRow pat24 = reading(facts, "pat", "FY2023-24");
        assertThat(pat24.absoluteAmount()).isEqualTo("41000000");
    }

    @Test
    void twoYearsNetWorthAreDifferentReadingsNotAConflict() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        long auditedVersion = ingestAndLayout(auditedSummaryPdf(),
                "Audited Financials - ABC", "audited_financials");
        ExtractService.ExtractOutcome extract = extractAudited(auditedVersion);
        new PackPathBinder().bind(dbPath, extract.parseRunId(), pack);
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        List<FactRow> facts = facts();
        FactRow fy2024 = reading(facts, "net_worth", "FY2023-24");
        FactRow fy2025 = reading(facts, "net_worth", "FY2024-25");
        assertThat(fy2024.factId()).isNotEqualTo(fy2025.factId());
        assertThat(periodOf(fy2024)).isNotEqualTo(periodOf(fy2025));

        // ADR 0026 §1 via #135: two periods are different readings, never a conflict.
        assertThat(Comparisons.date(periodOf(fy2024), periodOf(fy2025)).verdict())
                .isEqualTo(Verdict.DIFFERENT_READINGS);
        // The figures themselves do differ — only the distinct periods keep them
        // out of conflict; a same-period pair would be a plain disagreement.
        assertThat(Comparisons.money(fy2024.valueText(), fy2025.valueText()).verdict())
                .isEqualTo(Verdict.DISAGREE);
    }

    @Test
    void reExtractReplacesFiguresAndReassessKeepsTagsAndPeriods() throws Exception {
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        long auditedVersion = ingestAndLayout(auditedSummaryPdf(),
                "Audited Financials - ABC", "audited_financials");
        ExtractService.ExtractOutcome first = extractAudited(auditedVersion);
        new PackPathBinder().bind(dbPath, first.parseRunId(), pack);
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());
        List<FactRow> before = facts();

        // Same extract identity: the re-run replaces the fact set, never duplicates.
        ExtractService.ExtractOutcome again = extractAudited(auditedVersion);
        assertThat(again.parseRunId()).isEqualTo(first.parseRunId());
        new PackPathBinder().bind(dbPath, again.parseRunId(), pack);
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());

        List<FactRow> after = facts();
        assertThat(after).hasSameSizeAs(before);
        MandateQa.EntityRow borrower = qa().entities().stream()
                .filter(e -> "borrower".equals(e.role()))
                .findFirst()
                .orElseThrow();
        for (String key : HEADLINE_FIGURES) {
            List<FactRow> readings = factsOf(after, key);
            assertThat(readings).hasSize(2);
            assertThat(readings).extracting(MandateAuditedFinancialsTest::periodOf)
                    .containsExactlyInAnyOrder("FY2023-24", "FY2024-25");
            assertThat(readings).allSatisfy(
                    f -> assertThat(f.entityId()).isEqualTo(borrower.entityId()));
        }
    }

    // --- pipeline helpers ---

    private ExtractService.ExtractOutcome extractAudited(long documentVersionId) throws Exception {
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.putFixture(PACK_CODE, auditedFixture());
        return new ExtractService().extract(dbPath, documentVersionId, MANDATE, pack,
                extractClient, FixtureExtractClient.ID);
    }

    private ClassifyResult classify(long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyRequest request = new ClassifyRequest(versionId, version.fileRole(),
                    version.fileName(), page1, "general", PackLoader.loadSeedPacks(), true);
            return new DocTypeClassifier().classify(request, db.connection());
        }
    }

    private long ingestAndLayout(Path pdf, String logicalName, String fileRole)
            throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, null),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection()).query(FactQuery.forMandate(MANDATE));
        }
    }

    private static List<FactRow> factsOf(List<FactRow> facts, String fieldKey) {
        return facts.stream().filter(f -> fieldKey.equals(f.fieldKey())).toList();
    }

    private static FactRow fact(List<FactRow> facts, String fieldKey) {
        return factsOf(facts, fieldKey).stream().findFirst().orElseThrow();
    }

    private static FactRow reading(List<FactRow> facts, String fieldKey, String period) {
        return factsOf(facts, fieldKey).stream()
                .filter(f -> period.equals(periodOf(f)))
                .findFirst()
                .orElseThrow();
    }

    /** The fact's period — the statement it was read for (role {@code period}). */
    private static String periodOf(FactRow fact) {
        return fact.evidence().stream()
                .filter(e -> "period".equals(e.role()))
                .map(FactRow.EvidenceRow::verbatim)
                .findFirst()
                .orElse(null);
    }

    /** The fact's verbatim quote. */
    private static String quoteOf(FactRow fact) {
        return fact.evidence().stream()
                .filter(e -> "verbatim_quote".equals(e.role()))
                .map(FactRow.EvidenceRow::verbatim)
                .findFirst()
                .orElse(null);
    }

    /** The provenance page of the fact's verbatim quote. */
    private static Integer pageOf(FactRow fact) {
        return fact.evidence().stream()
                .filter(e -> "verbatim_quote".equals(e.role()))
                .map(FactRow.EvidenceRow::pageIndex)
                .findFirst()
                .orElse(null);
    }

    // --- fixtures ---

    private Path mandateLetterPdf() throws Exception {
        return writePdf("mandate_letter.pdf",
                List.of(
                        "Mandate Letter",
                        "To the Board of Directors, ABC Pvt Ltd (the Borrower)",
                        "Sub: Proposed financing of a 10 MW solar power project.",
                        "Date: 1 January 2025"));
    }

    private Path auditedSummaryPdf() throws Exception {
        return writePdf("audited_summary.pdf",
                List.of(
                        "Audited Financial Summary",
                        "ABC Pvt Ltd",
                        "Financial Years: FY2023-24 and FY2024-25",
                        "Auditor: M/s Verma & Associates"),
                List.of(
                        "Headline Figures (Rs crore)",
                        "FY2023-24",
                        "Revenue 40.2",
                        "PAT 4.1",
                        "Net Worth 18.5",
                        "Total Borrowings 26.0",
                        "FY2024-25",
                        "Revenue 48.3",
                        "PAT 5.1",
                        "Net Worth 22.4",
                        "Total Borrowings 28.0"));
    }

    /** Fixture-extract payload: presence fields plus one row per financial year. */
    private static JsonNode auditedFixture() throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "financials.audited_summary",
                  "fields": {
                    "subject_name": {"value": "ABC Pvt Ltd",
                      "quote": "ABC Pvt Ltd", "page": 1},
                    "financial_years": {"value": "FY2023-24 and FY2024-25",
                      "quote": "Financial Years: FY2023-24 and FY2024-25", "page": 1},
                    "auditor_name": {"value": "M/s Verma & Associates",
                      "quote": "Auditor: M/s Verma & Associates", "page": 1}
                  },
                  "tables": {
                    "headline_figures": [
                      {"financial_year": "FY2023-24",
                       "revenue": {"value": "Rs 40.2 crore",
                         "quote": "Revenue 40.2", "page": 2},
                       "pat": {"value": "Rs 4.1 crore",
                         "quote": "PAT 4.1", "page": 2},
                       "net_worth": {"value": "Rs 18.5 crore",
                         "quote": "Net Worth 18.5", "page": 2},
                       "total_borrowings": {"value": "Rs 26.0 crore",
                         "quote": "Total Borrowings 26.0", "page": 2}},
                      {"financial_year": "FY2024-25",
                       "revenue": {"value": "Rs 48.3 crore",
                         "quote": "Revenue 48.3", "page": 2},
                       "pat": {"value": "Rs 5.1 crore",
                         "quote": "PAT 5.1", "page": 2},
                       "net_worth": {"value": "Rs 22.4 crore",
                         "quote": "Net Worth 22.4", "page": 2},
                       "total_borrowings": {"value": "Rs 28.0 crore",
                         "quote": "Total Borrowings 28.0", "page": 2}}
                    ]
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

    /**
     * Deterministic fake over the doc LLM seam: the mandate letter identifies the
     * borrower; every other document yields no entities.
     */
    private static final class DocumentReplyLlm implements DocLlmClient {

        private static final Map<String, String> REPLIES = Map.of(
                "Mandate Letter", """
                        {"entities": [
                          {"name": "ABC Pvt Ltd", "role": "borrower",
                           "quote": "ABC Pvt Ltd (the Borrower)",
                           "reason": "The mandate letter names it as the borrower"}]}""");

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
