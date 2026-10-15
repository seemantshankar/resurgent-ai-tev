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
import java.nio.file.Path;
import java.sql.Connection;
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
 * Golden M3-08 suite (issue #141): clause reading over synthetic born-digital PDFs.
 * Asserts persisted clauses through the mandate QA read API — never prompts or
 * internals. Clause text comes from the stored layout blocks; no re-parse.
 */
class MandateClausesTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack ppaPack;
    private DocTypePack netWorthPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    @Test
    void ppaClausesBindToPackTypesWithVerbatimAndPageByAliases() throws Exception {
        long versionId = ingestAndLayout(ppaFixture(), "PPA - Clauses", "agreement");
        classify(versionId, ppaPack);

        // Offline: aliases bind deterministically, no LLM is consulted.
        DocAssessRunner.run(MANDATE, dbPath, null);

        List<MandateQa.ClauseRow> clauses = qa().clauses();
        assertThat(clauses).extracting(MandateQa.ClauseRow::heading)
                .containsExactly("12. TERMINATION", "13. FORCE MAJEURE", "15. DEEMED GENERATION");

        MandateQa.ClauseRow termination = clauseByType(clauses, "termination");
        assertThat(termination.status()).isEqualTo("bound");
        assertThat(termination.novelty()).isEqualTo("known");
        assertThat(termination.typeLabel()).isEqualTo("Termination");
        assertThat(termination.parentFamily()).isEqualTo("termination");
        assertThat(termination.verbatim())
                .contains("ninety (90) days prior written notice")
                .contains("liquidated damages of Rs. 5,00,000");
        assertThat(termination.pageStart()).isEqualTo(1);
        assertThat(termination.pageEnd()).isEqualTo(1);
        assertThat(termination.extractionMethod()).isEqualTo("alias");
        assertThat(termination.documentVersionId()).isEqualTo(versionId);
        assertThat(termination.fileName()).isEqualTo("ppa_clauses.pdf");
        assertThat(termination.slots()).isEmpty();

        MandateQa.ClauseRow forceMajeure = clauseByType(clauses, "force_majeure");
        assertThat(forceMajeure.status()).isEqualTo("bound");
        assertThat(forceMajeure.verbatim()).contains("caused by an event of Force Majeure");
        assertThat(forceMajeure.pageStart()).isEqualTo(2);
        assertThat(forceMajeure.pageEnd()).isEqualTo(2);

        MandateQa.ClauseRow unmatched = clauses.stream()
                .filter(c -> c.typeCode() == null)
                .findFirst().orElseThrow();
        assertThat(unmatched.status()).isEqualTo("open");
        assertThat(unmatched.novelty()).isEqualTo("new");
        assertThat(unmatched.verbatim()).contains("deemed to have generated the scheduled energy");
        assertThat(unmatched.pageStart()).isEqualTo(2);
    }

    /** One model reply reads all three clauses: two bound, one proposed as a new type. */
    private static final String LLM_REPLY = """
            {"clauses": [
              {"index": 1, "type": "termination",
               "summary": "Procurer may terminate on 90 days notice; the Seller pays damages.",
               "importance": "high",
               "slots": [{"key": "notice_period", "value": "ninety (90) days"},
                         {"key": "penalty", "value": "Rs. 5,00,000"}]},
              {"index": 2, "type": "force_majeure",
               "summary": "Neither party is liable for failures caused by force majeure.",
               "importance": "medium", "slots": []},
              {"index": 3, "type": null,
               "proposed_type": {"code": "deemed_generation", "label": "Deemed Generation",
                                 "parent_family": "payment_security"},
               "summary": "Procurer pays for deemed generation when evacuation fails.",
               "importance": "high", "slots": []}
            ]}
            """;

    @Test
    void unmatchedClauseStoredOpenWithProposedTypeSummaryImportanceAndDurationSlot()
            throws Exception {
        long versionId = ingestAndLayout(ppaFixture(), "PPA - LLM", "agreement");
        classify(versionId, ppaPack);

        FakeDocLlmClient fake = new FakeDocLlmClient(LLM_REPLY);
        DocAssessRunner.run(MANDATE, dbPath, fake);

        // Exactly one clause call: the profile stage found the stated execution date.
        assertThat(fake.calls()).isEqualTo(1);

        List<MandateQa.ClauseRow> clauses = qa().clauses();
        MandateQa.ClauseRow termination = clauseByType(clauses, "termination");
        assertThat(termination.extractionMethod()).isEqualTo("llm_open");
        assertThat(termination.summary()).contains("terminate");
        assertThat(termination.importance()).isEqualTo("high");
        assertThat(termination.slots()).anySatisfy(slot -> {
            assertThat(slot.slotKey()).isEqualTo("notice_period");
            assertThat(slot.valueText()).contains("ninety (90) days");
            assertThat(slot.valueNumber()).isEqualTo(90.0);
            assertThat(slot.unit()).isEqualTo("days");
        });
        assertThat(termination.slots()).anySatisfy(slot -> {
            assertThat(slot.slotKey()).isEqualTo("penalty");
            assertThat(slot.valueNumber()).isEqualTo(500000.0);
            assertThat(slot.currency()).isEqualTo("INR");
        });

        assertThat(clauseByType(clauses, "force_majeure").importance()).isEqualTo("medium");

        MandateQa.ClauseRow unmatched = clauses.stream()
                .filter(c -> "open".equals(c.status()))
                .findFirst().orElseThrow();
        assertThat(unmatched.typeCode()).isEqualTo("deemed_generation");
        assertThat(unmatched.typeLabel()).isEqualTo("Deemed Generation");
        assertThat(unmatched.parentFamily()).isEqualTo("payment_security");
        assertThat(unmatched.novelty()).isEqualTo("new");
        assertThat(unmatched.summary()).contains("deemed generation");
        assertThat(unmatched.importance()).isEqualTo("high");
        assertThat(unmatched.verbatim()).contains("deemed to have generated the scheduled energy");
        assertThat(unmatched.pageStart()).isEqualTo(2);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type"
                    + " WHERE source = 'proposed' AND code = 'deemed_generation'"
                    + " AND status = 'open'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM llm_usage"
                    + " WHERE stage = 'assess_clauses' AND calls = 1")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat"
                    + " WHERE stage = 'assess_clauses' AND name = 'clauses_bound'"
                    + " AND value_num = 2")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat"
                    + " WHERE stage = 'assess_clauses' AND name = 'clauses_open'"
                    + " AND value_num = 1")).isEqualTo(1);
        }
    }

    // ---- fixture + helpers ----

    /** Two-page PPA: a bound termination and force-majeure clause, plus one unknown clause. */
    private Path ppaFixture() throws Exception {
        return writePdf("ppa_clauses.pdf", new String[][] {
                {
                    "This Power Purchase Agreement is executed on 12 March 2024 between the"
                            + " Seller and the Procurer.",
                    "12. TERMINATION",
                    "The Procurer may terminate this Agreement by giving ninety (90) days prior"
                            + " written notice to the Seller.",
                    "Upon termination the Seller shall pay liquidated damages of Rs. 5,00,000"
                            + " to the Procurer.",
                },
                {
                    "13. FORCE MAJEURE",
                    "Neither Party shall be liable for any failure to perform its obligations"
                            + " under this Agreement if such failure is caused by an event of"
                            + " Force Majeure.",
                    "15. DEEMED GENERATION",
                    "Where the Procurer is unable to evacuate energy, the Seller shall be"
                            + " deemed to have generated the scheduled energy and the Procurer"
                            + " shall pay the applicable tariff for such deemed generation.",
                }});
    }

    Path writePdf(String name, String[][] pages) throws Exception {
        Path pdf = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String[] lines : pages) {
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

    long ingestAndLayout(Path pdf, String logicalName, String fileRole) throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    void classify(long versionId, DocTypePack pack) throws Exception {
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

    MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    static MandateQa.ClauseRow clauseByType(List<MandateQa.ClauseRow> clauses, String typeCode) {
        return clauses.stream()
                .filter(c -> typeCode.equals(c.typeCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clause of type " + typeCode
                        + " in " + clauses.stream().map(MandateQa.ClauseRow::heading).toList()));
    }

    static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
