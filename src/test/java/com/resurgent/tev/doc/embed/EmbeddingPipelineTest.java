package com.resurgent.tev.doc.embed;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.assess.AssessClausesRunner;
import com.resurgent.tev.doc.assess.AssessPassagesRunner;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocSimilarCommand;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.InductionDecisionRepository;
import com.resurgent.tev.doc.db.InductionDecisionRepository.Decision;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.ClauseTypePromoter;
import com.resurgent.tev.doc.induce.DocInduceClausesRunner;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
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
 * Clause and passage embeddings, cosine pre-clustering of paraphrased open
 * clauses, and top-K similarity through {@code tev-doc similar} and MandateQa.
 * Synthetic PDFs only. No network.
 */
class EmbeddingPipelineTest {

    private static final long MANDATE = 7L;

    private static final String DSCR_A =
            "The borrower shall maintain a minimum debt service coverage ratio of not less than"
                    + " 1.25 times for the entire tenor of the facility.";
    private static final String DSCR_B =
            "The borrower must keep a minimum debt service coverage ratio of not less than"
                    + " 1.25 times for the entire tenor of the facility.";
    private static final String ASSIGNMENT =
            "The lender may assign this agreement to an affiliate and the borrower shall be"
                    + " notified within ten business days.";

    private static final String PROPOSALS_A = """
            {"clauses": [
              {"index": 1, "type": null,
               "proposed_type": {"code": "minimum_dscr", "label": "Minimum DSCR",
                                 "parent_family": "covenant"},
               "summary": "DSCR floor of 1.25.", "importance": "high", "slots": []},
              {"index": 2, "type": null,
               "proposed_type": {"code": "assignment_to_affiliate",
                                 "label": "Assignment to affiliate", "parent_family": "transfer"},
               "summary": "Lender may assign.", "importance": "low", "slots": []}
            ]}
            """;

    private static final String PROPOSALS_B = """
            {"clauses": [
              {"index": 1, "type": null,
               "proposed_type": {"code": "dscr_covenant", "label": "DSCR covenant",
                                 "parent_family": "covenant"},
               "summary": "Same DSCR floor, other words.", "importance": "high", "slots": []}
            ]}
            """;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private PackStore store;
    private DocTypePack loanPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations, including V73
        }
        blobs = BlobStore.besideDatabase(dbPath);
        store = new PackStore(tempDir.resolve("packs"));
        loanPack = store.publish(new PackStore.PublishRequest(
                loanPack(), "general", null, List.of(Long.toString(MANDATE)), "seed")).pack();
    }

    @Test
    void clausesAndPassagesEmbedPreclusterAndRetrieveWithProvenance() throws Exception {
        long loanA = ingest(pdf("loan_a.pdf",
                "Facility Agreement",
                "MINIMUM DSCR", DSCR_A, "ASSIGNMENT", ASSIGNMENT),
                "Facility Agreement A", "agreement");
        long loanB = ingest(pdf("loan_b.pdf",
                "Facility Agreement",
                "DSCR COVENANT", DSCR_B),
                "Facility Agreement B", "agreement");
        classify(loanA, loanPack);
        classify(loanB, loanPack);

        new AssessClausesRunner().run(MANDATE, dbPath,
                FakeDocLlmClient.scripted(PROPOSALS_A, PROPOSALS_B));
        assertEmbeddingsMatchClauses();

        new AssessClausesRunner().run(MANDATE, dbPath,
                FakeDocLlmClient.scripted(PROPOSALS_A, PROPOSALS_B));
        assertEmbeddingsMatchClauses();

        List<DocInduceClausesRunner.Result> induced =
                DocInduceClausesRunner.run(MANDATE, dbPath, null);
        assertThat(induced).hasSize(1);
        assertThat(induced.get(0).promoted()).extracting(ClauseTypePromoter.PromotedType::key)
                .containsExactly("minimum_dscr");
        assertThat(induced.get(0).promoted().get(0).sourceDocumentIds())
                .containsExactlyInAnyOrder("dv:" + loanA, "dv:" + loanB);
        assertThat(induced.get(0).skipped()).extracting(ClauseTypePromoter.SkippedGroup::key)
                .contains("assignment_to_affiliate");

        List<Decision> decisions;
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            decisions = new InductionDecisionRepository(database.connection())
                    .forMandate(MANDATE, "induce_clauses");
        }
        assertThat(decisions).anySatisfy(decision -> {
            assertThat(decision.outcome()).isEqualTo("promoted");
            assertThat(decision.reasonCode()).isEqualTo("cluster_promoted");
            assertThat(decision.targetCode()).isEqualTo("minimum_dscr");
            assertThat(decision.stage()).isEqualTo("induce_clauses");
            assertThat(decision.detailJson()).contains("dv:" + loanA).contains("sanitized_query");
        });
        assertThat(decisions).anySatisfy(decision -> {
            assertThat(decision.outcome()).isEqualTo("dropped");
            assertThat(decision.reasonCode()).isEqualTo("below_threshold");
            assertThat(decision.candidateKey()).contains("assignment_to_affiliate");
        });

        long dpr = ingest(pdf("dpr.pdf",
                "Detailed Project Report",
                "1. Introduction and Project Site",
                "The project is a 10 MW solar photovoltaic power plant.",
                "Land area: 4.5 acres"),
                "Bhagyanagar DPR", "dpr");
        DocTypePack dprPack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");
        classify(dpr, dprPack);
        seedParse(dpr, "# 1. Introduction and Project Site\n\n"
                + "The project is a 10 MW solar photovoltaic power plant.\n\n"
                + "Land area: 4.5 acres\n");

        long rating = ingest(pdf("rating.pdf",
                "Project Report",
                "Credit Rating Report",
                "Rating rationale",
                "Liquidity is weak because receivables are stretched."),
                "CRISIL Rating", "dpr");
        classify(rating, dprPack);
        seedParse(rating, "# Rating rationale\n\n"
                + "Liquidity is weak because receivables are stretched and the rating drivers"
                + " cite delayed debtor recovery.\n");

        new AssessPassagesRunner().run(MANDATE, dbPath, null);
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            EmbeddingRepository embeddings = new EmbeddingRepository(database.connection());
            long passages = scalar(database, "SELECT COUNT(*) FROM passage");
            assertThat(passages).isGreaterThanOrEqualTo(2);
            assertThat(embeddings.passageCount()).isEqualTo(passages);
            assertThat(scalar(database,
                    "SELECT COUNT(*) FROM passage_embedding WHERE model = 'local-ngram-hash-v1'"))
                    .isEqualTo(passages);
        }

        Run clauseSearch = run(new DocSimilarCommand(),
                "--mandate", Long.toString(MANDATE),
                "--db", dbPath.toString(),
                "--kind", "clause",
                "--text", "minimum debt service coverage ratio of 1.25 times",
                "--top-k", "2");
        assertThat(clauseSearch.exitCode()).isZero();
        assertThat(clauseSearch.out()).contains("Facility Agreement");
        assertThat(clauseSearch.out()).contains("page 1");
        assertThat(clauseSearch.out()).contains("debt service coverage ratio");
        assertThat(clauseSearch.out()).contains("similar: 2");

        MandateQa qa = MandateQaQueryRunner.query(dbPath, MandateQaQuery.similar(
                MANDATE, "passage",
                "weak liquidity stretched receivables rating drivers", null, 1));
        assertThat(qa.similar()).hasSize(1);
        MandateQa.SimilarHit hit = qa.similar().get(0);
        assertThat(hit.kind()).isEqualTo("passage");
        assertThat(hit.logicalName()).isEqualTo("CRISIL Rating");
        assertThat(hit.fileName()).contains("rating");
        assertThat(hit.page()).isEqualTo(1);
        assertThat(hit.snippet()).containsIgnoringCase("liquidity is weak");
        assertThat(hit.score()).isGreaterThan(0.5);

        Run byId = run(new DocSimilarCommand(),
                "--mandate", Long.toString(MANDATE),
                "--db", dbPath.toString(),
                "--kind", "passage",
                "--id", Long.toString(hit.id()),
                "--top-k", "3");
        assertThat(byId.exitCode()).isZero();
        assertThat(byId.out()).doesNotContain("passage " + hit.id() + " ");
        assertThat(byId.out()).contains("Bhagyanagar DPR");
        assertThat(byId.out()).contains("page ");
    }

    private void assertEmbeddingsMatchClauses() throws Exception {
        EmbeddingClient client = new LocalHashEmbeddingClient();
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            EmbeddingRepository embeddings = new EmbeddingRepository(database.connection());
            long clauses = scalar(database, "SELECT COUNT(*) FROM clause");
            assertThat(clauses).isEqualTo(3);
            assertThat(embeddings.clauseCount()).isEqualTo(clauses);
            try (var statement = database.connection().createStatement();
                    var rows = statement.executeQuery(
                            "SELECT verbatim_text, model, dims, vec FROM clause c"
                                    + " JOIN clause_embedding e ON e.clause_id = c.clause_id")) {
                while (rows.next()) {
                    assertThat(rows.getString("model")).isEqualTo(LocalHashEmbeddingClient.MODEL);
                    assertThat(rows.getInt("dims")).isEqualTo(LocalHashEmbeddingClient.DIMS);
                    float[] stored = EmbeddingVectors.fromLittleEndian(rows.getBytes("vec"));
                    assertThat(stored).containsExactly(client.embed(rows.getString("verbatim_text")));
                }
            }
        }
    }

    private DocTypePack loanPack() {
        return new DocTypePack(
                "agreement.facility", "1.0.0", "agreement", "agreement",
                List.of("general"),
                List.of("Facility Agreement"),
                List.of(new PackField("borrower_name", "identity > Legal Name", "identity",
                        ValueKind.TEXT, true, List.of(), "Borrower name", List.of("Borrower"))),
                List.of(), List.of(), List.of(),
                "execution",
                new ClauseSection(true, true, List.of(
                        new PackClauseType("governing_law", "Governing Law", "legal",
                                List.of("Governing Law")))),
                List.of());
    }

    private long ingest(Path pdf, String logicalName, String fileRole) throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private Path pdf(String name, String... lines) throws Exception {
        Path file = tempDir.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                float y = 720;
                for (String line : lines) {
                    stream.beginText();
                    stream.setFont(font, 11);
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private void classify(long versionId, DocTypePack pack) throws Exception {
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(database.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId).orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(database.connection(), versionId);
            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), database.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    private void seedParse(long versionId, String page) throws Exception {
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            new CloudParsePageRepository(database.connection()).replacePages(
                    versionId, "parse-job-" + versionId, "agentic", List.of(page), List.of(page));
        }
    }

    private static long scalar(WorkspaceDatabase database, String sql) throws Exception {
        try (var statement = database.connection().createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private record Run(int exitCode, String out, String err) {}

    private Run run(Callable<Integer> command, String... args) {
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        Integer exit = cmd.execute(new ArrayList<>(List.of(args)).toArray(String[]::new));
        return new Run(exit == null ? -1 : exit, out.toString(), err.toString());
    }
}
