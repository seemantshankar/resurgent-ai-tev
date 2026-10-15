package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocQaCommand;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
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
 * Golden M3-13 suite (issue #146, ADR 0026): contradictions inside ONE document —
 * facts from different passages (chapter 2 states the project cost, chapter 7's cost
 * table restates it) form an {@code intra-doc} conflict settled through the same
 * three tiers, with both sections and pages recorded; the same figure repeated
 * across chapters is no conflict. Fixture PDFs in a temp {@code workspace.db}, the
 * fake doc LLM client and the fixture extract client; assertions go through the
 * mandate QA read API (plus the persisted rows it reads), never prompts or internals.
 */
class MandateIntraDocumentTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The page-2 cost line of the fixture DPR (chapter 2, prose section). */
    private static final String CHAPTER_TWO_COST = "Project cost: Rs. 48 crore";

    /** The page-7 cost line of the fixture DPR (chapter 7, detailed cost table). */
    private static final String CHAPTER_SEVEN_COST = "Estimated project cost: Rs. 52.6 crore";

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack dprPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        dprPack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");
    }

    // --- AC1: chapter 2 vs chapter 7 conflict, both pages and passages recorded ----

    @Test
    void chaptersStatingDifferentProjectCostsFormAnIntraDocumentConflictWithBothPages()
            throws Exception {
        long versionId = ingestDpr(CHAPTER_TWO_COST, CHAPTER_SEVEN_COST);
        stageCostFixture(CHAPTER_TWO_COST, "Rs. 48 crore", CHAPTER_SEVEN_COST, "Rs. 52.6 crore");

        // No precedence rule can cover two sections of one report type, and the fake
        // model declines: the conflict is recorded and left open.
        String declined = "{\"settle\": false, \"reasoning\":"
                + " \"both chapters are credible, I cannot tell which figure governs\"}";
        DocAssessRunner.Result result = DocAssessRunner.run(MANDATE, dbPath, new CostLlm(declined));
        assertThat(result.stages()).extracting(AssessStage.StageResult::stage)
                .containsExactly("assess_profile", "assess_clauses", "assess_passages",
                        "assess_conflicts", "assess_checklist");

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("intra-doc");
        assertThat(conflict.dataPoint()).isEqualTo("project_cost");
        // Both sides are the same document version — a contradiction inside it.
        assertThat(conflict.left().documentVersionId()).isEqualTo(versionId);
        assertThat(conflict.right().documentVersionId()).isEqualTo(versionId);
        // AC1: both pages recorded, each side carrying its own chapter passage.
        assertThat(conflict.left().pageIndex()).isEqualTo(2);
        assertThat(conflict.left().value()).isEqualTo("Rs. 48 crore");
        assertThat(conflict.left().passageHeading()).isEqualTo("2. Project Cost");
        assertThat(conflict.right().pageIndex()).isEqualTo(7);
        assertThat(conflict.right().value()).isEqualTo("Rs. 52.6 crore");
        assertThat(conflict.right().passageHeading()).isEqualTo("7. Project Cost Summary");
        assertThat(conflict.left().fileName()).isEqualTo("dpr_bhagyanagar.pdf");
        assertThat(conflict.right().fileName()).isEqualTo("dpr_bhagyanagar.pdf");
        // Tier 3: no rule, model declined — open and flagged, no value chosen.
        assertThat(conflict.settlement()).isEqualTo("open");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.chosenSide()).isNull();
        assertThat(conflict.chosenValue()).isNull();
        assertThat(conflict.rejectedValue()).isNull();
        assertThat(conflict.reasoning()).contains("no precedence rule");
        assertThat(qa.versionsDiffer()).isEmpty();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM conflict WHERE kind = 'intra-doc'"))
                    .isEqualTo(1);
            // The row cites both sides' facts, each in its own passage of the one version.
            assertThat(count(c, "SELECT COUNT(*) FROM conflict c"
                    + " JOIN fact lf ON lf.fact_id = c.left_fact_id"
                    + " JOIN fact rf ON rf.fact_id = c.right_fact_id"
                    + " WHERE c.kind = 'intra-doc'"
                    + " AND lf.document_version_id = c.left_document_version_id"
                    + " AND rf.document_version_id = c.right_document_version_id"
                    + " AND lf.passage_id IS NOT NULL AND rf.passage_id IS NOT NULL"
                    + " AND lf.passage_id != rf.passage_id")).isEqualTo(1);
            // Both stated values stay on the fact table — nothing is overwritten.
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'project_cost'"))
                    .isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'conflicting'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'settled_by_ai'")).isZero();
        }

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict project_cost [intra-doc]")
                .contains("\"2. Project Cost\"")
                .contains("\"7. Project Cost Summary\"")
                .contains("Rs. 48 crore")
                .contains("Rs. 52.6 crore");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).hasSize(1);
        JsonNode row = root.get("conflicts").get(0);
        assertThat(row.get("kind").asText()).isEqualTo("intra-doc");
        assertThat(row.get("left").get("page_index").asInt()).isEqualTo(2);
        assertThat(row.get("left").get("passage_heading").asText()).isEqualTo("2. Project Cost");
        assertThat(row.get("right").get("page_index").asInt()).isEqualTo(7);
        assertThat(row.get("right").get("passage_heading").asText())
                .isEqualTo("7. Project Cost Summary");
        assertThat(root.get("versions_differ")).isEmpty();
    }

    // --- AC2: the AI tier settles it, picking the detailed cost table -------------

    @Test
    void aiSettlesTheIntraDocumentConflictChoosingTheDetailedCostTableAndFlagsIt()
            throws Exception {
        long versionId = ingestDpr(CHAPTER_TWO_COST, CHAPTER_SEVEN_COST);
        stageCostFixture(CHAPTER_TWO_COST, "Rs. 48 crore", CHAPTER_SEVEN_COST, "Rs. 52.6 crore");

        // No rule covers two sections of one document; the fake model is confident and
        // picks the detailed cost table, citing both chapters' pages.
        String aiReply = "{\"settle\": true, \"chosen_side\": \"right\", \"reasoning\":"
                + " \"the cost table on page 7 itemises the estimate in detail while"
                + " chapter 2 on page 2 states an earlier rounded figure, so the detailed"
                + " cost table governs\"}";
        DocAssessRunner.run(MANDATE, dbPath, new CostLlm(aiReply));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("intra-doc");
        assertThat(conflict.dataPoint()).isEqualTo("project_cost");
        // The left side is chapter 2's earlier figure, the right the cost table's.
        assertThat(conflict.left().documentVersionId()).isEqualTo(versionId);
        assertThat(conflict.left().value()).isEqualTo("Rs. 48 crore");
        assertThat(conflict.left().pageIndex()).isEqualTo(2);
        assertThat(conflict.right().value()).isEqualTo("Rs. 52.6 crore");
        assertThat(conflict.right().pageIndex()).isEqualTo(7);
        // Settled by AI with written reasoning; the rejected value is kept, not lost.
        assertThat(conflict.settlement()).isEqualTo("ai");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.rulesVersion()).isNull();
        assertThat(conflict.chosenSide()).isEqualTo("right");
        assertThat(conflict.chosenValue()).isEqualTo("Rs. 52.6 crore");
        assertThat(conflict.rejectedValue()).isEqualTo("Rs. 48 crore");
        assertThat(conflict.reasoning())
                .contains("page 7")
                .contains("page 2");
        assertThat(qa.versionsDiffer()).isEmpty();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM conflict WHERE kind = 'intra-doc'"))
                    .isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'project_cost'"))
                    .isEqualTo(2);
            // Settled by AI is flagged (visible, no action required); nothing is open.
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'settled_by_ai'")).isEqualTo(1);
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT status, summary FROM review_queue"
                            + " WHERE category = 'settled_by_ai'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Resolved");
                assertThat(rs.getString("summary"))
                        .contains("Conflict on project_cost settled by AI");
            }
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category = 'conflicting'")).isZero();
        }

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict project_cost [intra-doc]")
                .contains("settled by AI")
                .contains("Rs. 52.6 crore")
                .contains("Rs. 48 crore");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        JsonNode row = root.get("conflicts").get(0);
        assertThat(row.get("settlement").asText()).isEqualTo("ai");
        assertThat(row.get("chosen_value").asText()).isEqualTo("Rs. 52.6 crore");
        assertThat(row.get("rejected_value").asText()).isEqualTo("Rs. 48 crore");
    }

    // --- AC3: the same figure repeated identically creates no conflict -------------

    @Test
    void sameFigureRepeatedAcrossChaptersCreatesNoConflict() throws Exception {
        // Both chapters state the same ₹52.6 cr figure — a repeat, not a contradiction.
        ingestDpr("Project cost: Rs. 52.6 crore", CHAPTER_SEVEN_COST);
        stageCostFixture("Project cost: Rs. 52.6 crore", "Rs. 52.6 crore",
                CHAPTER_SEVEN_COST, "Rs. 52.6 crore");

        // A confident settlement reply is available but must never be needed: equal
        // figures agree before any tier is consulted.
        String confident = "{\"settle\": true, \"chosen_side\": \"left\", \"reasoning\":"
                + " \"the later chapter restates the same figure\"}";
        DocAssessRunner.run(MANDATE, dbPath, new CostLlm(confident));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            // Both statements were extracted and compared — the pair agreed.
            assertThat(count(c, "SELECT COUNT(*) FROM fact WHERE field_key = 'project_cost'"))
                    .isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM conflict")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM review_queue"
                    + " WHERE category IN ('conflicting', 'settled_by_ai')")).isZero();
        }

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out()).contains("conflicts: 0");
    }

    // --- fixtures -------------------------------------------------------------

    /** Ingests the 7-page DPR, lays it out, seeds its stored parse and classifies it. */
    private long ingestDpr(String chapterTwoCostLine, String chapterSevenCostLine)
            throws Exception {
        Path pdf = writeDprPdf(chapterTwoCostLine, chapterSevenCostLine);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "DPR - Bhagyanagar", "v1", "dpr", "dpr"),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        seedStoredParse(ingested.documentVersionId());
        classify(ingested.documentVersionId());
        return ingested.documentVersionId();
    }

    /** The stored parse output the passages are split from: two cost chapters apart. */
    private void seedStoredParse(long versionId) throws Exception {
        List<String> pages = List.of(
                "# 1. Introduction and Project Site\n\n"
                        + "Bhagyanagar Solar Power Project, District Ahmednagar.",
                "# 2. Project Cost\n\n"
                        + "The project cost is stated below.\n\nProject cost: Rs. 48 crore",
                "# 3. Statutory Clearances\n\nAll statutory clearances are in place.",
                "# 4. Market Assessment\n\nPower demand is assessed below.",
                "# 5. Site Photographs\n\nSite photographs are annexed.",
                "# 6. Annexures\n\nList of annexures to this report.",
                "# 7. Project Cost Summary\n\n"
                        + "The detailed cost table is below.\n\n"
                        + "Estimated project cost: Rs. 52.6 crore");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new CloudParsePageRepository(db.connection()).replacePages(
                    versionId, "parse-job-dpr", "agentic", pages, pages);
        }
    }

    private Path writeDprPdf(String chapterTwoCostLine, String chapterSevenCostLine)
            throws Exception {
        Path pdf = tempDir.resolve("dpr_bhagyanagar.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, "Detailed Project Report", "Bhagyanagar Solar Power Project",
                    "1. Introduction and Project Site");
            addPage(doc, "2. Project Cost", "The project cost is stated below.",
                    chapterTwoCostLine);
            addPage(doc, "3. Statutory Clearances", "All statutory clearances are in place.");
            addPage(doc, "4. Market Assessment", "Power demand is assessed below.");
            addPage(doc, "5. Site Photographs", "Site photographs are annexed.");
            addPage(doc, "6. Annexures", "List of annexures to this report.");
            addPage(doc, "7. Project Cost Summary", "The detailed cost table is below.",
                    chapterSevenCostLine);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void addPage(PDDocument doc, String... lines) throws Exception {
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
    }

    /**
     * Stages the fixture-extract sidecar beside the workspace db (the chapter-fact
     * route, M3-06): a cost summary table with one row per chapter's page — page 2 the
     * prose chapter, page 7 the detailed cost table.
     */
    private void stageCostFixture(String pageTwoQuote, String pageTwoValue,
            String pageSevenQuote, String pageSevenValue) throws Exception {
        Path dir = tempDir.resolve("docfixtures");
        Files.createDirectories(dir);
        String json = """
                {
                  "doc_type_code": "report.dpr",
                  "tables": {
                    "cost_summary": [
                      {"project_cost": {"value": "%s", "quote": "%s", "page": 2}},
                      {"project_cost": {"value": "%s", "quote": "%s", "page": 7}}
                    ]
                  }
                }""".formatted(pageTwoValue, pageTwoQuote, pageSevenValue, pageSevenQuote);
        Files.writeString(dir.resolve("dpr_cost.expected.json"), json);
    }

    private void classify(long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(dprPack), true), db.connection());
            assertThat(result.docTypeCode()).isEqualTo("report.dpr");
        }
    }

    // --- helpers --------------------------------------------------------------

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static long count(Connection c, String sql) throws Exception {
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

    /** Deterministic fake over the doc LLM seam: settles the project-cost conflict. */
    private static final class CostLlm implements DocLlmClient {

        private final String settlementReply;

        private CostLlm(String settlementReply) {
            this.settlementReply = settlementReply;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            if (user.contains("Data point: project_cost")) {
                return reply(settlementReply);
            }
            return reply("{}");
        }

        private static Completion reply(String content) {
            return new Completion(content, "fake-model", 100, 50, 0.002, 12);
        }
    }
}
