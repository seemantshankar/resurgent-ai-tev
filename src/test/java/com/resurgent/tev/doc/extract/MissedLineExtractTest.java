package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackCodes;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lines and tables the label pass misses are still facts of the document
 * they were read on (issue #182).
 */
class MissedLineExtractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long reportId;
    private long versionId;
    private DocTypePack reportPack;

    /** Cloud stand-in. Local reading must not need it to return the labeled lines. */
    static final class RecordingClient implements ExtractClient {
        int calls;
        ExtractRequest lastRequest;

        @Override
        public String id() {
            return "cloud-extract";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
            calls++;
            lastRequest = req;
            return new ExtractResult(id(), MAPPER.createObjectNode(), List.of(), List.of(),
                    ExtractStats.empty());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        reportPack = PackLoader.findSeed(PackCodes.PROJECT_REPORT);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "bundle.pdf", "hash-bundle", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            reportId = docs.insertDocument(MANDATE, "Project report", "dpr", PackCodes.PROJECT_REPORT);
            versionId = docs.insertDocumentVersion(reportId, "v1", "bundle.pdf", "hash-bundle",
                    ".pdf", "dpr", 2, false, sourceId, null);
        }
    }

    @Test
    void capacityAndCostWithoutAColonAreFactsOfTheProjectReport() throws Exception {
        long pageId = page(2);
        line(pageId, 0, "Technology: Monocrystalline silicon");
        line(pageId, 1, "Project Capacity 51.30 MW AC");
        line(pageId, 2, "Total Project Cost ₹259.00 Crores");
        member(reportId, pageId);

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack,
                new RecordingClient(), "test");

        List<FactRow> facts = factsOf(reportId);
        FactRow capacity = fact(facts, "capacity_mw");
        FactRow cost = fact(facts, "project_cost");
        FactRow technology = fact(facts, "technology");

        assertThat(capacity.valueText()).contains("51.30");
        assertThat(pageOf(capacity)).isEqualTo(2);
        assertThat(cost.valueText()).contains("259.00");
        assertThat(cost.currency()).isEqualTo("INR");
        assertThat(cost.scale()).isEqualTo("crore");
        assertThat(pageOf(cost)).isEqualTo(2);
        assertThat(technology.valueText()).contains("Monocrystalline");
    }

    @Test
    void machineryTableOnTheProjectReportBecomesScheduleLines() throws Exception {
        long pageId = page(2);
        member(reportId, pageId);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long blockId = docs.insertBlock(pageId, 0, "table", null, null, null, null,
                    "machinery", "machinery", 0, "test");
            long grid = docs.insertTableGrid(blockId, 2, 3, 1);
            cell(docs, grid, 0, 0, "Equipment");
            cell(docs, grid, 0, 1, "Quantity");
            cell(docs, grid, 0, 2, "Amount");
            cell(docs, grid, 1, 0, "Inverter");
            cell(docs, grid, 1, 1, "4");
            cell(docs, grid, 1, 2, "120");
        }

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack,
                new RecordingClient(), "test");

        List<FactRow> facts = factsOf(reportId);
        FactRow qty = fact(facts, "plant_and_machinery.qty");
        FactRow amount = fact(facts, "plant_and_machinery.amount");
        assertThat(qty.lineKey()).isEqualTo("Inverter");
        assertThat(qty.valueText()).isEqualTo("4");
        assertThat(amount.lineKey()).isEqualTo("Inverter");
        assertThat(amount.valueText()).isEqualTo("120");
        assertThat(pageOf(qty)).isEqualTo(2);
        assertThat(pageOf(amount)).isEqualTo(2);
    }

    @Test
    void costAndTariffStayOnTheirOwnDocuments() throws Exception {
        long reportPage = page(2);
        line(reportPage, 0, "Total Project Cost ₹259.00 Crores");
        member(reportId, reportPage);

        long agreementPage = page(3);
        line(agreementPage, 0, "Tariff: ₹2.54 per kWh");
        long agreementId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            agreementId = new DocumentRepository(db.connection()).insertDocument(
                    MANDATE, "Power purchase agreement", "agreement", "agreement.ppa");
            try (var ps = db.connection().prepareStatement(
                    "UPDATE document_version SET page_count = 3 WHERE document_version_id = ?")) {
                ps.setLong(1, versionId);
                ps.executeUpdate();
            }
        }
        member(agreementId, agreementPage);

        ExtractService service = new ExtractService();
        service.read(dbPath, reportId, MANDATE, reportPack, new RecordingClient(), "test");
        service.read(dbPath, agreementId, MANDATE, PackLoader.findSeed("agreement.ppa"),
                new RecordingClient(), "test");

        List<FactRow> reportFacts = factsOf(reportId);
        List<FactRow> agreementFacts = factsOf(agreementId);
        assertThat(reportFacts).anyMatch(fact -> "project_cost".equals(fact.fieldKey())
                && fact.valueText() != null && fact.valueText().contains("259.00"));
        assertThat(reportFacts).noneMatch(fact -> "tariff".equals(fact.fieldKey()));
        assertThat(agreementFacts).anyMatch(fact -> "tariff".equals(fact.fieldKey())
                && fact.valueText() != null && fact.valueText().contains("2.54"));
        assertThat(agreementFacts).noneMatch(fact -> "project_cost".equals(fact.fieldKey()));
    }

    @Test
    void localOnlyResidencyMakesNoCloudCall() throws Exception {
        stageReportLines();
        residency(DataResidency.LOCAL_ONLY);
        RecordingClient cloud = new RecordingClient();

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack, cloud, "test");

        assertThat(cloud.calls).isZero();
        List<FactRow> facts = factsOf(reportId);
        assertThat(fact(facts, "capacity_mw").valueText()).contains("51.30");
        assertThat(fact(facts, "project_cost").valueText()).contains("259.00");
    }

    @Test
    void cloudIsAskedOnlyForLinesTheLocalPassCouldNotRead() throws Exception {
        stageReportLines();
        RecordingClient cloud = new RecordingClient();

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack, cloud, "test");

        assertThat(cloud.calls).isEqualTo(1);
        assertThat(cloud.lastRequest.options().get(ExtractRequest.REMAINDER_LINES))
                .isEqualTo("Evacuation voltage 33 kV");
        List<FactRow> facts = factsOf(reportId);
        assertThat(fact(facts, "capacity_mw").valueText()).contains("51.30");
        assertThat(fact(facts, "project_cost").valueText()).contains("259.00");
        assertThat(fact(facts, "technology").valueText()).contains("Monocrystalline");
    }

    private void stageReportLines() throws Exception {
        long pageId = page(2);
        line(pageId, 0, "Technology: Monocrystalline silicon");
        line(pageId, 1, "Project Capacity 51.30 MW AC");
        line(pageId, 2, "Total Project Cost ₹259.00 Crores");
        line(pageId, 3, "Evacuation voltage 33 kV");
        member(reportId, pageId);
    }

    private void residency(String mode) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, mode);
        }
    }

    private long page(int index) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new DocumentRepository(db.connection())
                    .insertPage(versionId, index, 600, 800, null);
        }
    }

    private void line(long pageId, int index, String text) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new DocumentRepository(db.connection()).insertBlock(pageId, index, "paragraph",
                    null, null, null, null, text, text, index, "test");
        }
    }

    private static void cell(DocumentRepository docs, long grid, int row, int col, String text)
            throws Exception {
        docs.insertTableCell(grid, row, col, text, null, null, null, null, row == 0);
    }

    private void member(long documentId, long pageId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
        }
    }

    private List<FactRow> factsOf(long documentId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(documentId));
        }
    }

    private static FactRow fact(List<FactRow> facts, String fieldKey) {
        return facts.stream().filter(f -> fieldKey.equals(f.fieldKey())).findFirst().orElseThrow();
    }

    private static int pageOf(FactRow fact) {
        return fact.evidence().stream()
                .map(FactRow.EvidenceRow::pageIndex)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }
}
