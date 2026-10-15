package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocInduceClausesCommand;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.pack.PackField;
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
 * Golden M3-09 suite (issue #142): clause types are learned automatically. Two loan
 * documents propose a termination clause in different words; the induction-family
 * promotion groups the two open labels into one type, passes the M2 consensus and
 * quality gates and publishes a new pack version recording the source documents.
 * A one-off open clause stays open, and re-running clauses binds the same text to
 * the promoted type (Pass A). Asserted through the mandate QA read API, the pack
 * registry and stage rows — never prompts; no live LLM or network.
 */
class ClauseTypePromotionTest {

    private static final long MANDATE = 1L;

    /** The pack code the two fixture agreements are classified as. */
    private static final String PACK_CODE = "agreement.loan_syndication";

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private PackStore store;
    private DocTypePack basePack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        store = new PackStore(tempDir.resolve("packs"));
        // The document type's pack as published before any clause learning: it declares
        // its clause section (open extraction on) but knows no termination type yet.
        basePack = store.publish(new PackStore.PublishRequest(
                loanAgreementPack(), "general", null, List.of(Long.toString(MANDATE)), "seed"))
                .pack();
        assertThat(basePack.version()).isEqualTo("1.0.0");
    }

    /** The whole learning loop: read open proposals, promote the recurring group, rebind. */
    @Test
    void recurringOpenLabelsPromoteIntoOnePackTypeAndRebindOnRerun() throws Exception {
        long docA = ingestAndLayout(docA(), "Loan Syndication A", "agreement");
        long docB = ingestAndLayout(docB(), "Loan Syndication B", "agreement");
        classify(docA, basePack);
        classify(docB, basePack);

        // --- Pass B: both termination-worded clauses stay open with their proposed types
        // The shared fake also serves the profile stage's per-document industry reads
        // (M3-05 #138 asks one per document whose pack names no specific industry),
        // so route those a neutral reply first: the clause proposal reads follow in
        // document order.
        FakeDocLlmClient reads = FakeDocLlmClient.scripted(
                NO_INDUSTRY_REPLY, NO_INDUSTRY_REPLY, PROPOSALS_DOC_A, PROPOSALS_DOC_B);
        DocAssessRunner.run(MANDATE, dbPath, reads);
        // Count the clause step only: one model read per document (M3-05 #138 scopes
        // shared-fake call counts this way).
        assertThat(reads.callsWithSystemContaining("You read contract clauses")).isEqualTo(2);

        List<MandateQa.ClauseRow> read = qa().clauses();
        assertThat(read).hasSize(3);
        assertThat(read).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo("open");
            assertThat(c.novelty()).isEqualTo("new");
        });
        assertThat(read).extracting(MandateQa.ClauseRow::typeCode).containsExactlyInAnyOrder(
                "termination_by_procurer", "deemed_generation", "procurer_right_to_terminate");
        // reading clauses alone never publishes a pack version; no human step happens here
        assertThat(store.entries()).hasSize(1);

        // --- offline promotion: labels that are not literally the same never group
        List<DocInduceClausesRunner.Result> offline = DocInduceClausesRunner.run(MANDATE, dbPath, null);
        assertThat(offline).isNotEmpty();
        assertThat(offline).allSatisfy(r -> assertThat(r.promoted()).isEmpty());
        assertThat(store.entries()).hasSize(1);

        // --- promotion: the recurring pair becomes ONE clause type in a new pack version
        FakeDocLlmClient promotion = new FakeDocLlmClient(NORMALISATION_REPLY);
        List<DocInduceClausesRunner.Result> results = DocInduceClausesRunner.run(MANDATE, dbPath, promotion);
        assertThat(promotion.calls()).isEqualTo(1);
        assertThat(results).hasSize(1);
        DocInduceClausesRunner.Result result = results.get(0);
        assertThat(result.docTypeCode()).isEqualTo(PACK_CODE);
        assertThat(result.packVersion()).isEqualTo("1.1.0");
        assertThat(result.promoted()).extracting(ClauseTypePromoter.PromotedType::key)
                .containsExactly("termination_by_procurer");
        assertThat(result.promoted().get(0).aliases())
                .contains("Termination by Procurer", "Procurer's right to terminate");
        assertThat(result.promoted().get(0).sourceDocumentIds())
                .containsExactly("dv:" + docA, "dv:" + docB);
        assertThat(result.skipped()).extracting(ClauseTypePromoter.SkippedGroup::key)
                .contains("deemed_generation");
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s.reason()).contains("one-off"));

        // the new pack version records the source documents and keeps the old content
        PackStore.PackRegistryEntry entry = store.latestEntryForCode(PACK_CODE).orElseThrow();
        assertThat(entry.version()).isEqualTo("1.1.0");
        assertThat(entry.sourceDocuments()).containsExactly("dv:" + docA, "dv:" + docB);
        DocTypePack promotedPack = store.load(PACK_CODE, "1.1.0").orElseThrow();
        assertThat(promotedPack.clauseTypes()).extracting(PackClauseType::key)
                .containsExactly("security_package", "termination_by_procurer");
        PackClauseType termination = promotedPack.clauseTypes().stream()
                .filter(t -> "termination_by_procurer".equals(t.key()))
                .findFirst().orElseThrow();
        assertThat(termination.label()).isEqualTo("Termination by Procurer");
        assertThat(termination.parentFamily()).isEqualTo("termination");
        assertThat(termination.aliases())
                .contains("Termination by Procurer", "Procurer's right to terminate");
        // publishing the new version carries the whole pack forward, not only clauses
        assertThat(promotedPack.documentDateKind()).isEqualTo("execution");
        assertThat(promotedPack.declaresClauses()).isTrue();
        assertThat(promotedPack.clauseOpenExtraction()).isTrue();
        assertThat(promotedPack.requiredFields()).extracting(PackField::key)
                .containsExactly("borrower_name");
        // the previous version stays readable
        assertThat(store.load(PACK_CODE, "1.0.0").orElseThrow().clauseTypes())
                .extracting(PackClauseType::key).containsExactly("security_package");

        // persisted rows: proposals promoted with the pack scope, reason and sources recorded
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type"
                    + " WHERE code = 'termination_by_procurer' AND source = 'proposed'"
                    + " AND status = 'promoted' AND pack_code = '" + PACK_CODE + "'"
                    + " AND pack_version = '1.1.0'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type"
                    + " WHERE code = 'procurer_right_to_terminate' AND status = 'promoted'"))
                    .isEqualTo(1);
            // the one-off open clause's proposal was never promoted
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type"
                    + " WHERE code = 'deemed_generation' AND status = 'open'"
                    + " AND pack_code IS NULL")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM doc_type_pack"
                    + " WHERE code = '" + PACK_CODE + "' AND version = '1.1.0'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat WHERE stage = 'induce-clauses'"
                    + " AND name = 'clause_types_promoted' AND value_num = 1")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM run_stat WHERE stage = 'induce-clauses'"
                    + " AND name = 'clause_types_kept_open' AND value_num = 1")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM llm_usage"
                    + " WHERE stage = 'induce-clauses' AND calls = 1")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM audit_log"
                    + " WHERE event_type = 'clause_type_promoted'"
                    + " AND payload LIKE '%dv:" + docA + "%'"
                    + " AND payload LIKE '%dv:" + docB + "%'"
                    + " AND payload LIKE '%termination_by_procurer%'")).isEqualTo(1);
        }

        // --- the induction-family CLI reports what it learned (and never re-promotes)
        Run cli = run(new DocInduceClausesCommand(null),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(cli.exitCode()).isZero();
        assertThat(cli.out())
                .contains("kept open: deemed_generation")
                .contains("one-off")
                .contains("promotion: 0 promoted, 1 kept open");
        // the promoted pair is not promoted twice: no new pack version was published
        assertThat(store.entries()).hasSize(2);

        // --- after promotion, re-running clauses binds the same text to the new type
        DocAssessRunner.run(MANDATE, dbPath, null);
        List<MandateQa.ClauseRow> clauses = qa().clauses();
        MandateQa.ClauseRow byProcurer = clauseByHeading(clauses, "TERMINATION BY PROCURER");
        assertThat(byProcurer.status()).isEqualTo("bound");
        assertThat(byProcurer.novelty()).isEqualTo("known");
        assertThat(byProcurer.typeCode()).isEqualTo("termination_by_procurer");
        assertThat(byProcurer.typeLabel()).isEqualTo("Termination by Procurer");
        assertThat(byProcurer.parentFamily()).isEqualTo("termination");
        assertThat(byProcurer.extractionMethod()).isEqualTo("alias");
        assertThat(byProcurer.verbatim()).contains("ninety (90) days prior written notice");
        assertThat(byProcurer.documentVersionId()).isEqualTo(docA);

        MandateQa.ClauseRow rightToTerminate = clauseByHeading(clauses, "PROCURER'S RIGHT TO TERMINATE");
        assertThat(rightToTerminate.status()).isEqualTo("bound");
        assertThat(rightToTerminate.typeCode()).isEqualTo("termination_by_procurer");
        assertThat(rightToTerminate.documentVersionId()).isEqualTo(docB);

        // the one-off clause stays open — nothing was invented for it
        MandateQa.ClauseRow oneOff = clauseByHeading(clauses, "DEEMED GENERATION");
        assertThat(oneOff.status()).isEqualTo("open");
        assertThat(oneOff.novelty()).isEqualTo("new");
    }

    /** A single one-off proposal (one document) is never promoted, even with a model. */
    @Test
    void oneOffProposalIsNeverPromoted() throws Exception {
        long docA = ingestAndLayout(docA(), "Loan Syndication A", "agreement");
        classify(docA, basePack);

        FakeDocLlmClient reads = FakeDocLlmClient.scripted(PROPOSALS_DOC_A);
        DocAssessRunner.run(MANDATE, dbPath, reads);

        FakeDocLlmClient promotion = new FakeDocLlmClient(NORMALISATION_REPLY);
        List<DocInduceClausesRunner.Result> results = DocInduceClausesRunner.run(MANDATE, dbPath, promotion);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).promoted()).isEmpty();
        assertThat(results.get(0).skipped()).isNotEmpty();
        assertThat(store.entries()).hasSize(1);

        // the clause stays open with its proposal intact
        assertThat(qa().clauses()).allSatisfy(c -> assertThat(c.status()).isEqualTo("open"));
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            assertThat(count(c, "SELECT COUNT(*) FROM clause_type WHERE status = 'promoted'")).isZero();
        }
    }

    // ---- fixtures ----

    private DocTypePack loanAgreementPack() {
        return new DocTypePack(
                PACK_CODE, "1.0.0", "agreement", "agreement",
                List.of("general"),
                List.of("Loan Syndication Agreement", "Loan Agreement"),
                List.of(new PackField("borrower_name", "identity > Legal Name", "identity",
                        ValueKind.TEXT, true, List.of(), "Borrower name", List.of("Borrower"))),
                List.of(), List.of(), List.of(),
                "execution",
                new ClauseSection(true, true, List.of(
                        new PackClauseType("security_package", "Security Package", "security",
                                List.of("Security Package")))), List.of());
    }

    /** One page: a stated execution date plus two open clause candidates. */
    private Path docA() throws Exception {
        return writePdf("loan_a.pdf", new String[][] {{
            "This Loan Syndication Agreement is executed on 12 March 2024 between the"
                    + " Lender and the Procurer.",
            "TERMINATION BY PROCURER",
            "The Procurer may terminate this Agreement by giving ninety (90) days prior"
                    + " written notice to the Lender.",
            "DEEMED GENERATION",
            "Where the Procurer cannot evacuate energy, the Lender shall be deemed to have"
                    + " generated the scheduled energy and shall pay the applicable tariff.",
        }});
    }

    /** The same clause in different words — it must group with doc A's termination. */
    private Path docB() throws Exception {
        return writePdf("loan_b.pdf", new String[][] {{
            "This Loan Syndication Agreement is executed on 20 April 2024 between the"
                    + " Lender and the Procurer.",
            "PROCURER'S RIGHT TO TERMINATE",
            "The Procurer may, at its sole discretion, terminate this Agreement on thirty"
                    + " (30) days notice to the Lender.",
        }});
    }

    /** The profile stage's per-document industry reads get this neutral reply. */
    private static final String NO_INDUSTRY_REPLY = "{\"industry\": null, \"quote\": null}";

    /** Doc A's model read: both clauses are beyond the pack's vocabulary. */
    private static final String PROPOSALS_DOC_A = """
            {"clauses": [
              {"index": 1, "type": null,
               "proposed_type": {"code": "termination_by_procurer",
                                 "label": "Termination by Procurer",
                                 "parent_family": "termination"},
               "summary": "Procurer may terminate on 90 days notice.",
               "importance": "high", "slots": []},
              {"index": 2, "type": null,
               "proposed_type": {"code": "deemed_generation",
                                 "label": "Deemed Generation",
                                 "parent_family": "payment_security"},
               "summary": "Borrower is paid for deemed generation when evacuation fails.",
               "importance": "high", "slots": []}
            ]}
            """;

    /** Doc B's model read: the same right, worded differently. */
    private static final String PROPOSALS_DOC_B = """
            {"clauses": [
              {"index": 1, "type": null,
               "proposed_type": {"code": "procurer_right_to_terminate",
                                 "label": "Procurer's right to terminate",
                                 "parent_family": "termination"},
               "summary": "Procurer may terminate at its discretion on 30 days notice.",
               "importance": "high", "slots": []}
            ]}
            """;

    /** One label-normalisation reply: the two wordings are one type; deemed generation is alone. */
    private static final String NORMALISATION_REPLY = """
            {"groups": [
              {"key": "termination_by_procurer", "label": "Termination by Procurer",
               "parent_family": "termination",
               "aliases": ["Procurer's right to terminate"],
               "members": ["termination_by_procurer", "procurer_right_to_terminate"]},
              {"key": "deemed_generation", "label": "Deemed Generation",
               "parent_family": "payment_security",
               "aliases": [], "members": ["deemed_generation"]}
            ]}
            """;

    // ---- helpers (M2 golden pattern) ----

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

    static MandateQa.ClauseRow clauseByHeading(List<MandateQa.ClauseRow> clauses, String heading) {
        return clauses.stream()
                .filter(c -> heading.equals(c.heading()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clause with heading " + heading
                        + " in " + clauses.stream().map(MandateQa.ClauseRow::heading).toList()));
    }

    static long count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
