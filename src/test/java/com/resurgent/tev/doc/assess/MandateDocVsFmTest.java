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
import com.resurgent.tev.parser.classify.ClassifyService;
import com.resurgent.tev.parser.classify.ClassifierLlm;
import com.resurgent.tev.parser.classify.LayerAJudgment;
import com.resurgent.tev.parser.classify.LayerBAssignment;
import com.resurgent.tev.parser.classify.LayerAPrompt;
import com.resurgent.tev.parser.classify.LayerBPrompt;
import com.resurgent.tev.parser.classify.RegionLayoutPrompt;
import com.resurgent.tev.parser.classify.RegionProposal;
import com.resurgent.tev.parser.classify.Relevance;
import com.resurgent.tev.parser.classify.ScheduleFamily;
import com.resurgent.tev.parser.classify.Triage;
import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Golden M3-14 suite (issue #147, ADR 0026): a document's statement against the Excel
 * financial model. The tiny workbook runs the unchanged FM pipeline (ingest, discover,
 * classify, Layer B bind); the comparison joins document facts to bound model cells by
 * ontology path, and every disagreement is recorded as a {@code doc-fm} conflict whose
 * model side names the sheet and the cell — settled through the same three tiers, with
 * the model read and never changed. An ambiguous model path or period is skipped with a
 * recorded reason row instead of a conflict. A money fact the shared nomenclature cannot
 * name (issue #153), and a bound amount the model states no cell for, are skipped the
 * same way, naming the path — the model's silence is never read as agreement. The
 * conflicts stage counts the model comparison apart from document-versus-document.
 * Asserted through the mandate QA read API, and the CLI text/JSON export only; no direct
 * SQL assertions.
 */
class MandateDocVsFmTest {

    private static final long MANDATE = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack dprPack;
    private DocTypePack netWorthPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        dprPack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
    }

    // --- AC1: the model's project-cost path disagrees with the DPR -------------

    @Test
    void fmProjectCostDisagreesWithDprIsADocFmConflictNamingTheSheetAndCell() throws Exception {
        // The tiny model (Rs. in Lakhs): project cost 6,000 lakh = Rs. 60 crore.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        String hashBefore = sha256(xlsx);
        long fmRun = ingestFm(xlsx, List.of(
                new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        assertThat(bindingsOf(fmRun)).anySatisfy(binding -> {
            assertThat(binding.path()).isEqualTo("Project Cost");
            assertThat(binding.coord()).isEqualTo("B3");
        });
        assertThat(readingOf(fmRun, "B3").absoluteAmount()).isEqualTo("600000000");

        var bindingsBefore = bindingsOf(fmRun);
        var readingsBefore = readingsOf(fmRun);
        long dprVersion = ingestDpr("Rs. 52,00,00,000");
        var factsBefore = com.resurgent.tev.doc.db.FactQueryRunner.query(dbPath,
                com.resurgent.tev.doc.query.FactQuery.forMandate(MANDATE));

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.stages()).hasSize(5).allSatisfy(stage ->
                assertThat(stage.status()).isEqualTo("completed"));
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("doc-fm");
        assertThat(conflict.dataPoint()).isEqualTo("project_cost");
        assertThat(conflict.fieldKey()).isEqualTo("project_cost");
        assertThat(conflict.ontologyPath()).isEqualTo("economic > Project Cost");
        assertThat(conflict.verdict()).isEqualTo("disagree");
        assertThat(conflict.left().documentVersionId()).isEqualTo(dprVersion);
        assertThat(conflict.left().fileName()).isEqualTo("dpr_sunbeam.pdf");
        assertThat(conflict.left().pageIndex()).isEqualTo(1);
        assertThat(conflict.left().value()).isEqualTo("Rs. 52,00,00,000");
        // The model side is a workbook cell, not a document version: sheet + coordinate.
        assertThat(conflict.right().documentVersionId()).isNull();
        assertThat(conflict.right().fileName()).isNull();
        assertThat(conflict.right().pageIndex()).isNull();
        assertThat(conflict.right().value()).isEqualTo("INR 600000000");
        assertThat(conflict.right().quote()).isEqualTo("MODEL!B3");
        // No precedence rule governs a document versus the model: tier 3 stays open.
        assertThat(conflict.settlement()).isEqualTo("open");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.rulesVersion()).isNull();
        assertThat(conflict.chosenSide()).isNull();
        assertThat(conflict.reasoning()).contains("precedence");
        // A model disagreement is never a revision, and nothing was skipped.
        assertThat(qa.versionsDiffer()).isEmpty();
        assertThat(qa.conflictSkips()).isEmpty();

        assertThat(bindingsOf(fmRun)).isEqualTo(bindingsBefore);
        assertThat(readingsOf(fmRun)).isEqualTo(readingsBefore);
        assertThat(com.resurgent.tev.doc.db.FactQueryRunner.query(dbPath,
                com.resurgent.tev.doc.query.FactQuery.forMandate(MANDATE)))
                .extracting(com.resurgent.tev.doc.query.FactRow::valueText)
                .containsExactlyElementsOf(factsBefore.stream()
                        .map(com.resurgent.tev.doc.query.FactRow::valueText).toList());
        // The workbook file itself is untouched by the assessment.
        assertThat(sha256(xlsx)).isEqualTo(hashBefore);

        // qa shows the model side by cell in text and JSON.
        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict project_cost [doc-fm]")
                .contains("INR 600000000")
                .contains("cell MODEL!B3")
                .contains("open: no settlement, flagged conflicting")
                .contains("conflict skips: 0");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).hasSize(1);
        JsonNode row = root.get("conflicts").get(0);
        assertThat(row.get("kind").asText()).isEqualTo("doc-fm");
        assertThat(row.get("right").get("quote").asText()).isEqualTo("MODEL!B3");
        assertThat(row.get("right").get("value").asText()).isEqualTo("INR 600000000");
        assertThat(row.get("right").get("document_version_id")).isNull();
        assertThat(root.get("conflict_skips")).isEmpty();

        // A re-run replaces the rows wholesale and never duplicates them.
        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(qa().conflicts()).hasSize(1);
        assertThat(bindingsOf(fmRun)).isEqualTo(bindingsBefore);
        assertThat(readingsOf(fmRun)).isEqualTo(readingsBefore);
    }

    // --- tier 2: AI judgement settles it, citing the model cell ----------------

    @Test
    void docFmDisagreementIsSettledByAiJudgementCitingTheModelCell() throws Exception {
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        ingestDpr("Rs. 52,00,00,000");

        String reply = "{\"settle\": true, \"chosen_side\": \"left\", \"reasoning\":"
                + " \"the DPR page 1 states the project cost while financial model cell"
                + " MODEL!B3 still carries an outdated figure, so the DPR value governs\"}";
        DocAssessRunner.run(MANDATE, dbPath, new DocLlm(reply));

        MandateQa qa = qa();
        assertThat(qa.conflicts()).hasSize(1);
        MandateQa.ConflictRow conflict = qa.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo("doc-fm");
        assertThat(conflict.settlement()).isEqualTo("ai");
        assertThat(conflict.ruleId()).isNull();
        assertThat(conflict.rulesVersion()).isNull();
        assertThat(conflict.chosenSide()).isEqualTo("left");
        assertThat(conflict.chosenValue()).isEqualTo("Rs. 52,00,00,000");
        assertThat(conflict.rejectedValue()).isEqualTo("INR 600000000");
        // The reasoning cites both sources: the document by page, the model by cell.
        assertThat(conflict.reasoning()).contains("MODEL!B3").contains("page 1");
        assertThat(qa.versionsDiffer()).isEmpty();
        assertThat(qa.conflictSkips()).isEmpty();

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict project_cost [doc-fm]")
                .contains("settled by AI")
                .contains("Rs. 52,00,00,000");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts").get(0).get("settlement").asText()).isEqualTo("ai");
        assertThat(root.get("conflicts").get(0).get("chosen_value").asText())
                .isEqualTo("Rs. 52,00,00,000");
    }

    // --- AC2: an agreeing value (within ~1%) creates no conflict ---------------

    @Test
    void fmValueWithinOnePercentCreatesNoConflict() throws Exception {
        // 60.3 crore against the model's 60 crore is 0.5% apart — inside the ~1%
        // tolerance: agreement, and no tolerance decision is ever escalated.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        long fmRun = ingestFm(xlsx, List.of(
                new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        ingestDpr("Rs. 60,30,00,000");

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.versionsDiffer()).isEmpty();
        assertThat(qa.conflictSkips()).isEmpty();
        assertThat(readingOf(fmRun, "B3").absoluteAmount()).isEqualTo("600000000");
    }

    // --- AC3: an ambiguous model period is skipped with a recorded reason ------

    @Test
    void ambiguousFmPeriodIsSkippedWithARecordedReason() throws Exception {
        // The model cell carries two period cues ("2026" from its row label and
        // "FY 2027" from the column header): which period it states is ambiguous.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(1).createCell(1).setCellValue("FY 2027");
            sheet.createRow(2).createCell(0).setCellValue("Project cost for 2026");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        long dprVersion = ingestDpr("Rs. 52,00,00,000");

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.conflictSkips()).hasSize(1);
        MandateQa.ConflictSkipRow skip = qa.conflictSkips().get(0);
        assertThat(skip.kind()).isEqualTo("doc-fm");
        assertThat(skip.dataPoint()).isEqualTo("project_cost");
        assertThat(skip.fmSource()).isEqualTo("MODEL!B3");
        assertThat(skip.documentVersionId()).isEqualTo(dprVersion);
        assertThat(skip.pageIndex()).isEqualTo(1);
        assertThat(skip.reason())
                .contains("ambiguous")
                .contains("2026")
                .contains("2027");
        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(qa().conflictSkips()).hasSize(1);
        assertThat(qa().conflicts()).isEmpty();

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict skip project_cost")
                .contains("MODEL!B3");
        Run json = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString(), "--json");
        JsonNode root = MAPPER.readTree(json.out());
        assertThat(root.get("conflicts")).isEmpty();
        assertThat(root.get("conflict_skips")).hasSize(1);
        JsonNode skipJson = root.get("conflict_skips").get(0);
        assertThat(skipJson.get("fm_source").asText()).isEqualTo("MODEL!B3");
        assertThat(skipJson.get("reason").asText()).contains("ambiguous");
        assertThat(skipJson.get("document_version_id").asLong()).isEqualTo(dprVersion);
    }

    // --- an ambiguous model path is skipped with a recorded reason -------------

    @Test
    void ambiguousFmPathIsSkippedWithARecordedReason() throws Exception {
        // Two cells of the model are bound to the same path: which statement the
        // document should be compared against is ambiguous.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
            sheet.getRow(2).createCell(2).setCellValue(6200);
        });
        ingestFm(xlsx, List.of(
                new LayerBAssignment(null, "B3", "economic", "Project Cost"),
                new LayerBAssignment(null, "C3", "economic", "Project Cost")));
        ingestDpr("Rs. 52,00,00,000");

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.conflictSkips()).hasSize(1);
        MandateQa.ConflictSkipRow skip = qa.conflictSkips().get(0);
        assertThat(skip.kind()).isEqualTo("doc-fm");
        assertThat(skip.dataPoint()).isEqualTo("project_cost");
        assertThat(skip.reason()).contains("ambiguous");
        assertThat(skip.fmSource()).contains("MODEL!B3").contains("MODEL!C3");
    }

    // --- a model cell without a usable reading is skipped with a reason --------

    @Test
    void fmCellWithoutAUsableReadingIsSkippedWithARecordedReason() throws Exception {
        // The only cell bound to the path is a heading: the model states no number.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost: as per schedule");
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "A3", "economic", "Project Cost")));
        ingestDpr("Rs. 52,00,00,000");

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa qa = qa();
        assertThat(qa.conflicts()).isEmpty();
        assertThat(qa.conflictSkips()).hasSize(1);
        MandateQa.ConflictSkipRow skip = qa.conflictSkips().get(0);
        assertThat(skip.kind()).isEqualTo("doc-fm");
        assertThat(skip.dataPoint()).isEqualTo("project_cost");
        assertThat(skip.fmSource()).isEqualTo("MODEL!A3");
        assertThat(skip.reason()).contains("no usable reading");
    }

    @Test
    void differentStatedPeriodsAreSkippedRatherThanReportedAsConflicts() throws Exception {
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(1).createCell(1).setCellValue("2027");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        ingestDpr("Rs. 52,00,00,000", "2026");

        DocAssessRunner.run(MANDATE, dbPath, null);

        assertThat(qa().conflicts()).isEmpty();
        assertThat(qa().conflictSkips()).singleElement().satisfies(skip ->
                assertThat(skip.reason()).contains("different periods", "2026", "2027"));
    }

    // --- #153: a fact bound to a shared path joins the model cell on that path --

    @Test
    void netWorthBoundToTheSharedBalanceSheetPathIsComparedWithTheModelsNetWorthCell()
            throws Exception {
        // The model's net worth cell (Rs. in Lakhs): 2,000 lakh = Rs. 20 crore.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Net worth (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Net worth");
            sheet.getRow(2).createCell(1).setCellValue(2000);
        });
        long fmRun = ingestFm(xlsx, List.of(
                new LayerBAssignment(null, "B3", "economic", "Balance Sheet > Net Worth")));
        assertThat(readingOf(fmRun, "B3").absoluteAmount()).isEqualTo("200000000");
        long certificate = ingestNetWorthCertificate();

        DocAssessRunner.run(MANDATE, dbPath, null);

        // The pack said "Promoter Net Worth > Net Worth"; the shared nomenclature owns
        // the path, and that is the path the model cell is bound to too — so the two
        // statements of one data point meet and disagree.
        assertThat(qa().conflicts()).filteredOn(row -> "net_worth".equals(row.dataPoint()))
                .singleElement().satisfies(conflict -> {
                    assertThat(conflict.kind()).isEqualTo("doc-fm");
                    assertThat(conflict.ontologyPath())
                            .isEqualTo("economic > Balance Sheet > Net Worth");
                    assertThat(conflict.verdict()).isEqualTo("disagree");
                    assertThat(conflict.left().documentVersionId()).isEqualTo(certificate);
                    assertThat(conflict.left().value()).isEqualTo("Rs. 1,60,00,000");
                    assertThat(conflict.right().value()).isEqualTo("INR 200000000");
                    assertThat(conflict.right().quote()).isEqualTo("MODEL!B3");
                    assertThat(conflict.settlement()).isEqualTo("open");
                });
    }

    // --- #153: a money fact the shared nomenclature does not know is skipped -----

    @Test
    void unboundMoneyFactsAreSkippedWithARecordedRowNamingTheirPath() throws Exception {
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Net worth (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Net worth");
            sheet.getRow(2).createCell(1).setCellValue(2000);
        });
        ingestFm(xlsx, List.of(
                new LayerBAssignment(null, "B3", "economic", "Balance Sheet > Net Worth")));
        long certificate = ingestNetWorthCertificate();

        DocAssessRunner.run(MANDATE, dbPath, null);

        // Assets and liabilities are the certificate's other money statements. No shared
        // leaf names them, so they are never compared silently: each records why.
        assertThat(qa().conflictSkips()).hasSize(2);
        assertThat(qa().conflictSkips())
                .filteredOn(skip -> "assets_total".equals(skip.dataPoint()))
                .singleElement().satisfies(skip -> {
                    assertThat(skip.kind()).isEqualTo("doc-fm");
                    assertThat(skip.ontologyPath())
                            .isEqualTo("economic > Promoter Net Worth > Assets");
                    assertThat(skip.fmSource()).isNull();
                    assertThat(skip.documentVersionId()).isEqualTo(certificate);
                    assertThat(skip.pageIndex()).isEqualTo(1);
                    assertThat(skip.reason())
                            .contains("unbound")
                            .contains("Promoter Net Worth > Assets");
                });
        assertThat(qa().conflictSkips())
                .filteredOn(skip -> "liabilities_total".equals(skip.dataPoint()))
                .singleElement().satisfies(skip -> assertThat(skip.ontologyPath())
                        .isEqualTo("economic > Promoter Net Worth > Liabilities"));
        // The fact that did bind is compared, not skipped, and the text export says so.
        assertThat(qa().conflictSkips())
                .extracting(MandateQa.ConflictSkipRow::dataPoint)
                .doesNotContain("net_worth");
        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict skip assets_total [doc-fm]")
                .contains("conflict skips: 2");

        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(qa().conflictSkips()).hasSize(2);
    }

    @Test
    void anUnboundMoneyFactIsStillSkippedWhenTheModelProvesNoPath() throws Exception {
        // The workbook is this mandate's model even when Layer B bound nothing on it.
        // Silence here is the green-but-blind read AC4 (#153) forbids: the comparison
        // was possible and refused, not absent.
        // The workbook is this mandate's model even when Layer B proved no path on it:
        // a numeric cell with no label anywhere. Silence here is the green-but-blind
        // read AC4 (#153) forbids — the comparison was possible and refused, not absent.
        Path xlsx = modelWorkbook(sheet -> sheet.createRow(2).createCell(1).setCellValue(2000));
        long fmRun = ingestFm(xlsx, List.of());
        assertThat(bindingsOf(fmRun)).isEmpty();
        ingestNetWorthCertificate();

        DocAssessRunner.run(MANDATE, dbPath, null);

        assertThat(qa().conflictSkips())
                .extracting(MandateQa.ConflictSkipRow::dataPoint)
                .containsExactlyInAnyOrder("net_worth", "assets_total", "liabilities_total");
        // The bound net worth is refused because the model states no such path; the other
        // two because no shared leaf names them. Both refusals are visible, neither is a
        // pass.
        assertThat(qa().conflictSkips())
                .filteredOn(skip -> "net_worth".equals(skip.dataPoint()))
                .singleElement().satisfies(skip -> assertThat(skip.reason())
                        .contains("no cell bound", "economic > Balance Sheet > Net Worth"));
    }

    // --- #147: the model saying nothing about a bound amount is a recorded refusal --

    @Test
    void aBoundAmountTheModelDoesNotStateIsSkippedNamingTheModelsSilence() throws Exception {
        // The model states a project cost; the certificate states a promoter's net worth,
        // which the shared nomenclature binds to Balance Sheet > Net Worth. No model cell
        // is bound there, so the comparison was possible and refused — and an empty
        // conflict list must not read as agreement.
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        long certificate = ingestNetWorthCertificate();

        DocAssessRunner.run(MANDATE, dbPath, null);

        assertThat(qa().conflicts()).isEmpty();
        assertThat(qa().conflictSkips())
                .filteredOn(skip -> "net_worth".equals(skip.dataPoint()))
                .singleElement().satisfies(skip -> {
                    assertThat(skip.kind()).isEqualTo("doc-fm");
                    assertThat(skip.ontologyPath())
                            .isEqualTo("economic > Balance Sheet > Net Worth");
                    assertThat(skip.fmSource()).isNull();
                    assertThat(skip.documentVersionId()).isEqualTo(certificate);
                    assertThat(skip.pageIndex()).isEqualTo(1);
                    assertThat(skip.reason())
                            .contains("no cell bound", "economic > Balance Sheet > Net Worth");
                });
        // The two amounts no shared leaf names are refused for their own reason, not this.
        assertThat(qa().conflictSkips()).hasSize(3);
        assertThat(qa().conflictSkips())
                .filteredOn(skip -> "assets_total".equals(skip.dataPoint()))
                .singleElement().satisfies(skip ->
                        assertThat(skip.reason()).contains("unbound"));

        Run text = run(new DocQaCommand(), "--mandate", "1", "--db", dbPath.toString());
        assertThat(text.exitCode()).isZero();
        assertThat(text.out())
                .contains("conflict skip net_worth [doc-fm]")
                .contains("conflict skips: 3");

        DocAssessRunner.run(MANDATE, dbPath, null);
        assertThat(qa().conflictSkips()).hasSize(3);
    }

    // --- #147: the model comparison is counted apart from document-vs-document ----

    @Test
    void theConflictsStageReportsTheModelComparisonApartFromDocumentVersusDocument()
            throws Exception {
        Path xlsx = modelWorkbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lakhs)");
            sheet.createRow(2).createCell(0).setCellValue("Project cost");
            sheet.getRow(2).createCell(1).setCellValue(6000);
        });
        ingestFm(xlsx, List.of(new LayerBAssignment(null, "B3", "economic", "Project Cost")));
        ingestDpr("Rs. 52,00,00,000");

        DocAssessRunner.Result result = DocAssessRunner.run(MANDATE, dbPath, null);

        // A run that disagreed with the model reads differently from one that only
        // compared documents: the stage detail breaks the two apart.
        assertThat(result.stages())
                .filteredOn(stage -> AssessConflictsRunner.STAGE.equals(stage.stage()))
                .singleElement().satisfies(stage -> assertThat(stage.detail())
                        .contains("1 conflicts")
                        .contains("0 document-vs-document")
                        .contains("1 document-vs-model"));
    }

    // --- FM seams: the unchanged pipeline over a synthetic workbook ------------

    /**
     * The tiny financial model workbook on sheet {@code MODEL}, built row by row.
     */
    private Path modelWorkbook(Consumer<Sheet> model) throws Exception {
        Path xlsx = tempDir.resolve("financial-model.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                var out = Files.newOutputStream(xlsx)) {
            Sheet sheet = workbook.createSheet("MODEL");
            model.accept(sheet);
            var moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("\"INR\" #,##0.00"));
            for (var row : sheet) {
                for (var cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        cell.setCellStyle(moneyStyle);
                    }
                }
            }
            workbook.write(out);
        }
        return xlsx;
    }

    /** Runs the unchanged FM pipeline: ingest, discover, classify and bind Layer B. */
    private long ingestFm(Path xlsx, List<LayerBAssignment> assignments) throws Exception {
        IngestSummary ingest = new IngestService().ingest(xlsx, MANDATE, dbPath);
        new DiscoverService().discover(dbPath, ingest.parseRunId());
        FmModel model = new FmModel(assignments);
        new ClassifyService(model).classify(dbPath, ingest.parseRunId());
        new ClassifyService(model).bindSheets(dbPath, ingest.parseRunId(), List.of("MODEL"));
        return ingest.parseRunId();
    }

    private List<NomenclatureBinding> bindingsOf(long parseRunId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new WorkspaceRepository(db.connection()).selectNomenclatureBindings(parseRunId);
        }
    }

    private CellReading readingOf(long parseRunId, String coord) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            Map<Long, String> coords = new HashMap<>();
            for (InterpretationCellView cell : repo.selectInterpretationCellsForParseRun(parseRunId)) {
                coords.put(cell.cellId(), cell.coord());
            }
            return repo.selectCellReadingsForParseRun(parseRunId).stream()
                    .filter(reading -> coord.equals(coords.get(reading.cellId())))
                    .findFirst()
                    .orElseThrow();
        }
    }

    // --- doc seams: the M2 golden pattern ---------------------------------------

    /** Ingests one DPR PDF, lays it out, classifies it and extracts the fixture facts. */
    private long ingestDpr(String total) throws Exception {
        return ingestDpr(total, null);
    }

    private long ingestDpr(String total, String period) throws Exception {
        Path pdf = writePdf("dpr_sunbeam.pdf", List.of(
                "Detailed Project Report",
                "Project: Sunbeam Solar Park",
                "Total project cost: " + total,
                "Report Date: 10 June 2026",
                period == null ? "" : "Period: " + period));
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "DPR - Sunbeam Solar Park", "v1", "dpr", "dpr"),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        classify(ingested.documentVersionId());
        FixtureExtractClient extractClient = new FixtureExtractClient();
        JsonNode fixture = dprFixture(total);
        if (period != null) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.get("fields"))
                    .putObject("period").put("value", period)
                    .put("quote", "Period: " + period).put("page", 1);
        }
        extractClient.putFixture("report.dpr", fixture);
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, ingested.documentVersionId(), MANDATE, dprPack, extractClient,
                FixtureExtractClient.ID);
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, dprPack, null);
        assertThat(com.resurgent.tev.doc.db.FactQueryRunner.query(dbPath,
                com.resurgent.tev.doc.query.FactQuery.forMandate(MANDATE)))
                .anySatisfy(fact -> {
                    assertThat(fact.fieldKey()).isEqualTo("project_cost");
                    assertThat(fact.ontologyPath()).isEqualTo("economic > Project Cost");
                    assertThat(fact.valueText()).isEqualTo(total);
                });
        return ingested.documentVersionId();
    }

    private void classify(long versionId) throws Exception {
        classify(versionId, dprPack);
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

    /**
     * Ingests a promoter net-worth certificate: its net worth lands on the shared
     * Balance Sheet path, its assets and liabilities on no shared leaf at all. It states
     * no as-on date — a period the model cannot state would only be a period skip, and
     * these tests are about the path join.
     */
    private long ingestNetWorthCertificate() throws Exception {
        Path pdf = writePdf("net_worth_sharma.pdf", List.of(
                "Certificate of Net Worth",
                "Name of the Member: RAMESH KUMAR SHARMA",
                "Total Assets: Rs. 2,45,00,000",
                "Total Liabilities: Rs. 85,00,000",
                "Net Worth of RAMESH KUMAR SHARMA: Rs. 1,60,00,000"));
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "Promoter Net Worth - Sharma", "v1",
                        "certificate", "certificate"),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        classify(ingested.documentVersionId(), netWorthPack);
        FixtureExtractClient extractClient = new FixtureExtractClient();
        extractClient.putFixture("identity.promoter_net_worth", MAPPER.readTree("""
                {
                  "doc_type_code": "identity.promoter_net_worth",
                  "fields": {
                    "subject_name": {"value": "RAMESH KUMAR SHARMA",
                      "quote": "Name of the Member: RAMESH KUMAR SHARMA", "page": 1},
                    "assets_total": {"value": "Rs. 2,45,00,000",
                      "quote": "Total Assets: Rs. 2,45,00,000", "page": 1},
                    "liabilities_total": {"value": "Rs. 85,00,000",
                      "quote": "Total Liabilities: Rs. 85,00,000", "page": 1},
                    "net_worth": {"value": "Rs. 1,60,00,000",
                      "quote": "Net Worth of RAMESH KUMAR SHARMA: Rs. 1,60,00,000",
                      "page": 1}
                  }
                }"""));
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, ingested.documentVersionId(), MANDATE, netWorthPack, extractClient,
                FixtureExtractClient.ID);
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, netWorthPack, null);
        assertThat(com.resurgent.tev.doc.db.FactQueryRunner.query(dbPath,
                com.resurgent.tev.doc.query.FactQuery.forMandate(MANDATE)))
                .anySatisfy(fact -> {
                    assertThat(fact.fieldKey()).isEqualTo("net_worth");
                    assertThat(fact.ontologyPath()).isEqualTo("economic > Balance Sheet > Net Worth");
                });
        return ingested.documentVersionId();
    }

    private static JsonNode dprFixture(String total) throws Exception {
        return MAPPER.readTree("""
                {
                  "doc_type_code": "report.dpr",
                  "fields": {
                    "project_name": {"value": "Sunbeam Solar Park",
                      "quote": "Project: Sunbeam Solar Park", "page": 1},
                    "project_cost": {"value": "%s",
                      "quote": "Total project cost: %s", "page": 1}
                  }
                }""".formatted(total, total));
    }

    private Path writePdf(String name, List<String> lines) throws Exception {
        Path pdf = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
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
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    // --- helpers -----------------------------------------------------------------

    private MandateQa qa() throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }

    private List<CellReading> readingsOf(long parseRunId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new WorkspaceRepository(db.connection()).selectCellReadingsForParseRun(parseRunId);
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

    /** The tiny model's deterministic classifier: one main region, fixed Layer B paths. */
    private static final class FmModel implements ClassifierLlm {

        private final List<LayerBAssignment> bindings;

        private FmModel(List<LayerBAssignment> bindings) {
            this.bindings = bindings;
        }

        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            return List.of(new RegionProposal(
                    "main", "A1:F10", "model_main", "tiny project cost block"));
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            return new LayerAJudgment(
                    ScheduleFamily.CAPEX_DETAIL,
                    Triage.MAIN,
                    Relevance.PRIMARY,
                    List.of("Project cost"),
                    List.of("(Rs. In Lakhs)"),
                    null,
                    "Tiny project cost block of the financial model.");
        }

        @Override
        public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
            return bindings;
        }
    }

    /** Deterministic fake over the doc LLM seam, keyed on the model side's description. */
    private static final class DocLlm implements DocLlmClient {

        private final String settlementReply;

        private DocLlm(String settlementReply) {
            this.settlementReply = settlementReply;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            if (user.contains("financial model cell")) {
                return reply(settlementReply);
            }
            return reply("{}");
        }

        private static Completion reply(String content) {
            return new Completion(content, "fake-model", 100, 50, 0.002, 12);
        }
    }
}
