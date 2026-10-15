package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocQaCommand;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
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

        // Exactly one clause call: the profile stage found the stated execution date,
        // and the industry/country fallbacks in that stage also ask — so count the
        // clause step only (M3-05 #138 scopes call counts this way).
        assertThat(fake.callsWithSystemContaining("You read contract clauses")).isEqualTo(1);

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

    @Test
    void packWithoutClauseDeclarationProducesNoClausesAndNoClauseCalls() throws Exception {
        // The promoter net-worth certificate's pack declares no clauses: nothing is
        // segmented, stored, or asked of the model for this document type.
        long versionId = ingestAndLayout(
                writePdf("promoter_net_worth.pdf", new String[][] {{
                    "Certificate of Net Worth",
                    "Net worth of the promoter is Rs. 10 crore as certified.",
                }}),
                "Promoter NW", "certificate");
        classify(versionId, netWorthPack);

        FakeDocLlmClient fake = new FakeDocLlmClient("{\"date\": null, \"clauses\": []}");
        DocAssessRunner.run(MANDATE, dbPath, fake);

        assertThat(qa().clauses()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM clause")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'clause'")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM llm_usage"
                    + " WHERE stage = 'assess_clauses'")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat"
                    + " WHERE stage = 'assess_clauses' AND name = 'clauses_total'"
                    + " AND value_num = 0")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat"
                    + " WHERE stage = 'assess_clauses' AND name = 'llm_calls'"
                    + " AND value_num = 0")).isEqualTo(1);
        }

        // In isolation: the clause stage asks the model nothing at all for this pack.
        FakeDocLlmClient direct = new FakeDocLlmClient("{\"date\": null, \"clauses\": []}");
        AssessStage.StageResult result = new AssessClausesRunner().run(MANDATE, dbPath, direct);
        assertThat(direct.calls()).isZero();
        assertThat(result.stage()).isEqualTo("assess_clauses");
        assertThat(result.detail()).isEqualTo("0 clauses (0 bound, 0 open) from 0 of 1 documents");
    }

    @Test
    void reassessmentReplacesClausesNeverDuplicates() throws Exception {
        long versionId = ingestAndLayout(ppaFixture(), "PPA - Repeat", "agreement");
        classify(versionId, ppaPack);

        DocAssessRunner.run(MANDATE, dbPath, null);
        DocAssessRunner.run(MANDATE, dbPath, null);

        List<MandateQa.ClauseRow> clauses = qa().clauses();
        assertThat(clauses).hasSize(3);
        assertThat(clauses).extracting(MandateQa.ClauseRow::heading)
                .containsExactly("12. TERMINATION", "13. FORCE MAJEURE", "15. DEEMED GENERATION");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM clause")).isEqualTo(3);
            assertThat(count(c, "SELECT COUNT(*) FROM provenance_doc"
                    + " WHERE entity_type = 'clause'")).isEqualTo(3);
            assertThat(count(c, "SELECT COUNT(*) FROM assessment_run"
                    + " WHERE mandate_id = " + MANDATE + " AND stage = 'assess_clauses'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void qaCliListsClausesAsTextAndJson() throws Exception {
        long versionId = ingestAndLayout(ppaFixture(), "PPA - CLI", "agreement");
        classify(versionId, ppaPack);

        // Explicitly offline: clientOrNull() would use the developer's live key, and
        // the default suite never needs the network.
        DocAssessRunner.Result assess = DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(assess.summaryLines()).contains(
                "assess_clauses: 3 clauses (2 bound, 1 open) from 1 of 1 documents");

        Run text = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("clause 12. TERMINATION p1 [bound, known] | Termination"
                        + " | ppa_clauses.pdf")
                .contains("clause 15. DEEMED GENERATION p2 [open, new] | (untyped)"
                        + " | ppa_clauses.pdf")
                .contains("ninety (90) days prior written notice")
                .contains("clauses: 3");

        Run jsonRun = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString(), "--json");
        assertThat(jsonRun.exitCode()).isZero();
        JsonNode clauses = new ObjectMapper().readTree(jsonRun.out()).get("clauses");
        assertThat(clauses).hasSize(3);
        JsonNode termination = null;
        JsonNode open = null;
        for (JsonNode clause : clauses) {
            if ("termination".equals(clause.get("type_code").asText())) {
                termination = clause;
            }
            if ("open".equals(clause.get("status").asText())) {
                open = clause;
            }
        }
        assertThat(termination).isNotNull();
        assertThat(termination.get("heading").asText()).isEqualTo("12. TERMINATION");
        assertThat(termination.get("page_start").asInt()).isEqualTo(1);
        assertThat(termination.get("verbatim").asText())
                .contains("liquidated damages of Rs. 5,00,000");
        assertThat(termination.get("extraction_method").asText()).isEqualTo("alias");
        assertThat(open).isNotNull();
        assertThat(open.get("type_code").isNull()).isTrue();
        assertThat(open.get("novelty").asText()).isEqualTo("new");
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

    private record Run(int exitCode, String out, String err) {}

    /** Executes one CLI command through picocli, capturing stdout/stderr. */
    private Run run(Callable<Integer> command, String... args) {
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        Integer exit = cmd.execute(new ArrayList<>(List.of(args)).toArray(String[]::new));
        return new Run(exit == null ? -1 : exit, out.toString(), err.toString());
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
