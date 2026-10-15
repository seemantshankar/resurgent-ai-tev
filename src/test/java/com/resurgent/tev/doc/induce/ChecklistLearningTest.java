package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Golden client-set learning: synthetic PDFs, real databases, fake external seams. */
class ChecklistLearningTest {
    @TempDir Path temp;

    @Test
    void threeSolarWorkspacesLearnAndReassessWithoutChangingHistory() throws Exception {
        var workspaces = List.of(workspace("solar-a", "solar_power", "approval.evacuation"),
                workspace("solar-b", "solar_power", "approval.evacuation"),
                workspace("solar-c", "solar_power", "approval.evacuation"));
        var result = DocInduceChecklistsRunner.run(workspaces, new FakeDocLlmClient("{}"),
                ChecklistLearner.Config.defaults());
        assertThat(result.mandatesRead()).isEqualTo(3);
        for (var workspace : workspaces) {
            DocAssessRunner.run(1L, workspace.dbPath(), new FakeDocLlmClient("{}"));
            MandateQa qa = qa(workspace);
            assertThat(qa.stages()).hasSize(5).allSatisfy(s ->
                    assertThat(s.status()).isEqualTo("completed"));
            assertThat(qa.checklist()).filteredOn(r -> r.itemKey().equals("approval_evacuation"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.checklistVersion()).isEqualTo("1.1.0");
                        assertThat(row.origin()).isEqualTo("observed");
                        assertThat(row.basisKind()).isEqualTo("observed");
                        for (var source : workspaces) {
                            assertThat(row.basisText()).contains(source.dbPath().toString() + "#1");
                        }
                        assertThat(row.status()).isEqualTo("provided");
                        assertThat(row.pageIndex()).isEqualTo(1);
                    });
            assertThat(qa.checklist()).filteredOn(r -> r.itemKey().equals("pvsyst_report"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.mandatory()).isFalse();
                        assertThat(row.note()).contains("optional:", "3 of the 3");
                    });
            assertThat(store(workspace).loadAll()).filteredOn(d -> d.code().equals("in.solar_power"))
                    .extracting(ChecklistDefinition::version).containsExactly("1.0.0", "1.1.0");
        }
        assertThat(DocInduceChecklistsRunner.run(workspaces, null,
                ChecklistLearner.Config.defaults()).updates()).isEmpty();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(workspaces.get(0).dbPath())) {
            var history = new com.resurgent.tev.doc.db.ChecklistRepository(db.connection())
                    .learningHistory(1L);
            assertThat(history).filteredOn(e -> e.eventType().equals("checklist_learning_summary"))
                    .hasSize(2).allSatisfy(event -> {
                        var payload = new ObjectMapper().readTree(event.payload());
                        assertThat(payload.path("mandates_read").asInt()).isEqualTo(3);
                        assertThat(payload.path("thresholds").path("min_observed_mandates").asInt()).isEqualTo(2);
                        assertThat(payload.path("thresholds").path("min_missing_mandates").asInt()).isEqualTo(2);
                        assertThat(payload.path("thresholds").path("min_industries_for_spine").asInt()).isEqualTo(2);
                    });
            assertThat(history).filteredOn(e -> e.eventType().equals("checklist_learned"))
                    .hasSize(2).allSatisfy(event -> {
                        var payload = new ObjectMapper().readTree(event.payload());
                        assertThat(payload.path("reason").asText()).isNotBlank();
                        assertThat(payload.path("new_version").asText()).isEqualTo("1.1.0");
                        assertThat(payload.path("source_mandates")).hasSize(3);
                        List<String> paths = new ArrayList<>();
                        payload.path("source_mandates").forEach(source -> {
                            assertThat(source.path("mandate_id").asLong()).isEqualTo(1);
                            paths.add(source.path("workspace").asText());
                        });
                        assertThat(paths).containsExactlyInAnyOrderElementsOf(workspaces.stream()
                                .map(w -> w.dbPath().toString()).toList());
                    });
        }
    }

    @Test
    void neverProvidedMeansAbsentFromEveryMandateNotJustNOfThem() throws Exception {
        var a = workspace("a", "solar_power", "approval.evacuation", "report.pvsyst");
        var b = workspace("b", "solar_power", "approval.evacuation");
        var c = workspace("c", "solar_power", "approval.evacuation");
        DocInduceChecklistsRunner.run(List.of(a, b, c), null, ChecklistLearner.Config.defaults());
        DocAssessRunner.run(1L, b.dbPath(), new FakeDocLlmClient("{}"));
        assertThat(qa(b).checklist()).filteredOn(r -> r.itemKey().equals("pvsyst_report"))
                .singleElement().satisfies(r -> assertThat(r.mandatory()).isTrue());
    }

    @Test
    void inductionCliPromotesOnlyCrossIndustryLlmJudgedItemsAndHonorsThresholds() throws Exception {
        var workspaces = List.of(
                workspace("solar-a", "solar_power", "approval.evacuation", "certificate.tax", "certificate.project"),
                workspace("solar-b", "solar_power", "approval.evacuation", "certificate.tax", "certificate.project"),
                workspace("solar-c", "solar_power", "approval.evacuation", "certificate.tax", "certificate.project"),
                workspace("hotel", "hotel", "certificate.tax", "certificate.project"));
        FakeDocLlmClient judge = new FakeDocLlmClient("""
                {"judgements":[
                  {"document_type":"certificate.tax","independent":true,"reason":"Tax compliance applies to every borrower"},
                  {"document_type":"certificate.project","independent":false,"reason":"Project-specific licence"},
                  {"document_type":"approval.evacuation","independent":true,"reason":"Unrequested solar-only type"},
                  {"document_type":"invented.type","independent":true,"reason":"Not observed"}]}
                """);
        var command = new picocli.CommandLine(
                new com.resurgent.tev.doc.cli.DocInduceChecklistsCommand(judge));
        List<String> args = new ArrayList<>(List.of("--min-observed-mandates", "3",
                "--min-missing-mandates", "4", "--min-industries-for-spine", "2"));
        for (var workspace : workspaces) {
            args.addAll(List.of("--mandate", "1", "--db", workspace.dbPath().toString()));
        }
        assertThat(com.resurgent.tev.doc.TevDocMain.commandLine().getSubcommands())
                .containsKey("induce-checklists");
        assertThat(command.execute(args.toArray(String[]::new))).isZero();
        for (var workspace : workspaces) {
            DocAssessRunner.run(1L, workspace.dbPath(), new FakeDocLlmClient("{}"));
            MandateQa qa = qa(workspace);
            assertThat(qa.checklist()).filteredOn(r -> r.itemKey().equals("certificate_tax"))
                    .singleElement().satisfies(r -> {
                        assertThat(r.origin()).isEqualTo("promoted");
                        assertThat(r.checklistVersion()).isEqualTo("1.1.0");
                        assertThat(r.note()).contains("Tax compliance");
                    });
            assertThat(qa.checklist()).filteredOn(r -> r.itemKey().equals("pvsyst_report"))
                    .singleElement().satisfies(r -> assertThat(r.mandatory()).isTrue());
            var spine = ChecklistStore.loadCatalog(workspace.dbPath()).values().stream()
                    .filter(ChecklistDefinition::isSpine).findFirst().orElseThrow();
            assertThat(spine.items()).extracting(ChecklistItemDef::documentType)
                    .contains("certificate.tax").doesNotContain("approval.evacuation",
                            "certificate.project", "invented.type");
        }
    }

    @Test
    void repeatedWorkspaceCannotManufactureAConsensus() throws Exception {
        var a = workspace("one", "solar_power", "approval.evacuation");
        var alias = new DocInduceChecklistsRunner.MandateWorkspace(1L,
                a.dbPath().getParent().resolve(".").resolve("workspace.db"));
        var result = DocInduceChecklistsRunner.run(List.of(a, a, alias), null,
                ChecklistLearner.Config.defaults());
        assertThat(result.mandatesRead()).isEqualTo(1);
        assertThat(result.updates()).isEmpty();
        assertThat(store(a).loadAll()).extracting(ChecklistDefinition::version).containsExactly("1.0.0");
    }

    @Test
    void latestVersionAcrossStoresWinsEvenWhenAnOlderWorkspaceIsLast() throws Exception {
        var a = workspace("newer", "solar_power", "approval.evacuation");
        var b = workspace("older", "solar_power", "approval.evacuation");
        var initial = store(a).loadAll().get(0);
        store(a).publishVersioned(new ChecklistDefinition(initial.code(), "1.4.0", initial.scope(),
                initial.country(), initial.industries(), initial.items(), "existing learning"));
        DocInduceChecklistsRunner.run(List.of(a, b), null, ChecklistLearner.Config.defaults());
        for (var workspace : List.of(a, b)) {
            DocAssessRunner.run(1L, workspace.dbPath(), new FakeDocLlmClient("{}"));
            assertThat(qa(workspace).checklist()).filteredOn(r -> r.itemKey().equals("approval_evacuation"))
                    .singleElement().satisfies(r -> assertThat(r.checklistVersion()).isEqualTo("1.5.0"));
        }
    }

    private DocInduceChecklistsRunner.MandateWorkspace workspace(String name, String industry,
            String... types) throws Exception {
        Path directory = Files.createDirectories(temp.resolve(name)).toRealPath();
        Path dbPath = directory.resolve("workspace.db");
        var workspace = new DocInduceChecklistsRunner.MandateWorkspace(1L, dbPath);
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) { }
        store(workspace).publish(new ChecklistDefinition("in." + industry, "1.0.0", "industry",
                "IN", List.of(industry), List.of(new ChecklistItemDef("pvsyst_report",
                "PVsyst report", "document", "mandate", null, "report.pvsyst", null, null,
                true, "practice", "Lender yield assessment practice", "drafted",
                "Please share the {item}.", null)), null));
        for (String type : types) {
            document(workspace, industry, type);
        }
        DocAssessRunner.run(1L, dbPath, new FakeDocLlmClient("{}"));
        assertThat(qa(workspace).industries()).extracting(MandateQa.IndustryRow::industry)
                .contains(industry.replace('_', ' '));
        assertThat(qa(workspace).country().country()).isEqualTo("India");
        return workspace;
    }

    private void document(DocInduceChecklistsRunner.MandateWorkspace workspace, String industry,
            String type) throws Exception {
        String title = "Certificate " + type;
        Path pdf = workspace.dbPath().getParent().resolve(type + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(72, 720);
                for (String line : List.of(title, "Registered office: Jaipur, India",
                        "Project: " + industry.replace('_', ' '),
                        "Reference: CLIENT-001", "Issue Date: 5 April 2025")) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        DocTypePack pack = new DocTypePack(type, "1.0.0", "certificate", "certificate",
                List.of(industry), List.of(title), List.of(new PackField("reference",
                "identity > Reference", "identity", ValueKind.TEXT, true, List.of(),
                "Reference", List.of("Reference"))), List.of(), List.of(), List.of());
        new PackStore(workspace.dbPath().getParent().resolve("packs")).publish(
                new PackStore.PublishRequest(pack, industry, null, List.of(), "fixture"));
        BlobStore blobs = BlobStore.besideDatabase(workspace.dbPath());
        long version = new DocumentIngestService().ingest(new DocumentIngestService.IngestRequest(
                pdf, 1L, title, "v1", "certificate", "certificate"), workspace.dbPath(), blobs)
                .documentVersionId();
        new DocumentLayoutService().layout(version, workspace.dbPath(), blobs);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(workspace.dbPath())) {
            var classified = new DocTypeClassifier().classify(new ClassifyRequest(version,
                    "certificate", pdf.getFileName().toString(),
                    DocTypeClassifier.loadPage1Text(db.connection(), version), industry,
                    List.of(pack), true), db.connection());
            assertThat(classified.docTypeCode()).isEqualTo(type);
        }
        FixtureExtractClient extractor = new FixtureExtractClient();
        extractor.putFixture(type, new ObjectMapper().readTree("""
                {"doc_type_code":"%s", "fields":{"reference":
                  {"value":"CLIENT-001", "quote":"Reference: CLIENT-001", "page":1}}}
                """.formatted(type)));
        assertThat(new ExtractService().extract(workspace.dbPath(), version, 1L, pack,
                extractor, FixtureExtractClient.ID).status()).isEqualTo("success");
    }

    private static ChecklistStore store(DocInduceChecklistsRunner.MandateWorkspace workspace) {
        return new ChecklistStore(ChecklistStore.storeRoot(workspace.dbPath()));
    }

    private static MandateQa qa(DocInduceChecklistsRunner.MandateWorkspace workspace)
            throws Exception {
        return MandateQaQueryRunner.query(workspace.dbPath(), MandateQaQuery.forMandate(1L));
    }
}
