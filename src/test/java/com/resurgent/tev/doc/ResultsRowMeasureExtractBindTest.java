package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Results-row measure heads (#207, ADR 0036). */
class ResultsRowMeasureExtractBindTest {

    private static final long MANDATE = 1L;

    private static final String PACK_YAML = """
            code: environment.pollution_test
            version: 1.0.0
            family: report
            file_role: report
            industry_tags: [general]
            fields:
              - key: lab_name
                path: identity > Lab
                path_root: identity
                value_kind: text
                required: true
            tables:
              - name: results
                path: technical > Environment
                path_root: technical
                key_column: parameter
                fields:
                  - key: parameter
                    value_kind: text
                  - key: result
                    value_kind: quantity
            """;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentId;
    private DocTypePack pack;

    static final class SilentCloud implements com.resurgent.tev.doc.extract.ExtractClient {
        @Override
        public String id() {
            return "cloud";
        }

        @Override
        public com.resurgent.tev.doc.extract.ExtractResult extractFieldSchema(
                com.resurgent.tev.doc.extract.ExtractRequest req,
                com.fasterxml.jackson.databind.JsonNode schema) {
            throw new AssertionError("layout grid must not need cloud");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        pack = PackLoader.load(new ByteArrayInputStream(PACK_YAML.getBytes(StandardCharsets.UTF_8)),
                "pollution");
        documentId = stageGrid(dbPath, "pollution.pdf", List.of(
                new String[] {"Parameter", "Result"},
                new String[] {"BOD", "12 mg/L"},
                new String[] {"COD", "40 mg/L"}));
    }

    @Test
    void bodTwelveMgPerLBoundAsEnvironmentBod() throws Exception {
        ExtractService.ExtractOutcome extract = read();
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
        FactRow bod = byLine("BOD");
        assertThat(bod.ontologyPath()).contains("Environment > BOD");
        assertThat(bod.ontologyPath()).doesNotContain("Project Cost");
        assertThat(bod.valueText()).contains("12");
    }

    @Test
    void codFromSameTableBoundAsEnvironmentCod() throws Exception {
        ExtractService.ExtractOutcome extract = read();
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
        FactRow cod = byLine("COD");
        assertThat(cod.ontologyPath()).contains("Environment > COD");
        assertThat(cod.ontologyPath()).contains("Environment >");
    }

    @Test
    void tableRemainsScheduleLine() throws Exception {
        ExtractService.ExtractOutcome extract = read();
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
        FactRow bod = byLine("BOD");
        assertThat(bod.fieldKey()).isEqualTo("results.result");
        assertThat(bod.lineKey()).isEqualTo("BOD");
    }

    @Test
    void biochemicalOxygenDemandWordingReusesBodHead() throws Exception {
        ExtractService.ExtractOutcome first = read();
        new PackPathBinder().bind(dbPath, first.parseRunId(), MANDATE, pack, null);
        long second = addGridDocument("pollution-2.pdf", List.of(
                new String[] {"Parameter", "Result"},
                new String[] {"Biochemical Oxygen Demand", "12 mg/L"}));
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, second, MANDATE, pack, new SilentCloud(), "test");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
        FactRow alias = factsOf(second).stream()
                .filter(f -> "Biochemical Oxygen Demand".equals(f.lineKey()))
                .findFirst().orElseThrow();
        assertThat(alias.ontologyPath()).contains("Environment > BOD");
    }

    @Test
    void publishesOptionalLeafBeforeRunEnds() throws Exception {
        ExtractService.ExtractOutcome extract = read();
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
        DocTypePack published = new PackStore(tempDir.resolve("packs"))
                .latest("general", pack.code()).orElseThrow();
        assertThat(published.fields()).extracting(PackField::key).contains("bod");
        assertThat(published.fields().stream().filter(f -> "bod".equals(f.key()))
                .findFirst().orElseThrow().required()).isFalse();
        assertThat(PackLoader.loadSeedPacks()).noneMatch(p -> p.fields().stream()
                .anyMatch(f -> "bod".equals(f.key()) && p.code().equals(pack.code())));
    }

    private ExtractService.ExtractOutcome read() throws Exception {
        return new ExtractService().read(dbPath, documentId, MANDATE, pack, new SilentCloud(),
                "test");
    }

    private long stageGrid(Path dbFile, String fileName, List<String[]> rows) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbFile)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, fileName, "hash-" + fileName, "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long docId = docs.insertDocument(MANDATE, fileName, "report", pack.code());
            long versionId = docs.insertDocumentVersion(docId, "v1", fileName, "hash-" + fileName,
                    ".pdf", "report", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "Lab name : Test Lab", "Lab name : Test Lab", 1, "test");
            long grid = docs.insertTableGrid(
                    docs.insertBlock(pageId, 0, "table", null, null, null, null,
                            "results", "results", 0, "test"),
                    rows.size(), 2, 1);
            for (int r = 0; r < rows.size(); r++) {
                docs.insertTableCell(grid, r, 0, rows.get(r)[0], null, null, null, null, r == 0);
                docs.insertTableCell(grid, r, 1, rows.get(r)[1], null, null, null, null, r == 0);
            }
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, docId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
            return docId;
        }
    }

    private long addGridDocument(String fileName, List<String[]> rows) throws Exception {
        return stageGrid(dbPath, fileName, rows);
    }

    private FactRow byLine(String lineKey) throws Exception {
        return factsOf(documentId).stream()
                .filter(f -> lineKey.equals(f.lineKey()))
                .findFirst().orElseThrow();
    }

    private List<FactRow> factsOf(long docId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(docId));
        }
    }
}
