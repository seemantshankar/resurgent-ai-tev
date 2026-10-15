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
    }

    private DocInduceChecklistsRunner.MandateWorkspace workspace(String name, String industry,
            String... types) throws Exception {
        Path directory = Files.createDirectories(temp.resolve(name));
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
