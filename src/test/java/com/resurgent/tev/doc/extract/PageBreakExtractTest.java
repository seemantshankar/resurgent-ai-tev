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
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sentence or a table split by a page break stays one statement (issue #184).
 */
class PageBreakExtractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long reportId;
    private long versionId;
    private DocTypePack reportPack;

    /** Cloud stand-in. These readings are local and must not call it. */
    static final class RecordingClient implements ExtractClient {
        int calls;

        @Override
        public String id() {
            return "cloud-extract";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
            calls++;
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
                    ".pdf", "dpr", 6, false, sourceId, null);
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }
    }

    @Test
    void sentenceSplitByAPageBreakIsOneSanctionedLimitCitingBothPages() throws Exception {
        long page4 = page(4);
        long page5 = page(5);
        line(page4, 0, "the sanctioned limit of the facility is");
        line(page5, 0, "194.25 crores");
        member(reportId, page4);
        member(reportId, page5);

        RecordingClient cloud = new RecordingClient();
        new ExtractService().read(dbPath, reportId, MANDATE, reportPack, cloud, "test");

        List<FactRow> facts = factsOf(reportId);
        assertThat(cloud.calls).isZero();
        assertThat(facts).hasSize(1);
        FactRow limit = facts.get(0);
        assertThat(limit.fieldKey()).containsIgnoringCase("sanctioned limit");
        assertThat(limit.valueText()).contains("194.25");
        assertThat(pagesOf(limit)).containsExactly(4, 5);
    }

    @Test
    void columnHeadingNamesLaterRowsUntilTheTableEnds() throws Exception {
        long page2 = page(2);
        long page3 = page(3);
        long page4 = page(4);
        long page5 = page(5);
        long page6 = page(6);
        table(page2, new String[][] {
                {"Equipment", "Quantity", "Amount"},
                {"Inverter", "4", "120"}
        });
        table(page3, new String[][] {
                {"Module", "10", "80"}
        });
        table(page4, new String[][] {
                {"Cable", "2", "40"}
        });
        line(page5, 0, "The machinery list ends here.");
        table(page6, new String[][] {
                {"Spare", "1", "5"}
        });
        for (long pageId : List.of(page2, page3, page4, page5, page6)) {
            member(reportId, pageId);
        }

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack,
                new RecordingClient(), "test");

        List<FactRow> facts = factsOf(reportId);
        FactRow moduleQty = lineFact(facts, "plant_and_machinery.qty", "Module");
        FactRow cableQty = lineFact(facts, "plant_and_machinery.qty", "Cable");
        assertThat(moduleQty.valueText()).isEqualTo("10");
        assertThat(pagesOf(moduleQty)).contains(3);
        assertThat(cableQty.valueText()).isEqualTo("2");
        assertThat(pagesOf(cableQty)).contains(4);
        assertThat(facts).noneMatch(fact -> "Spare".equals(fact.lineKey()));
    }

    @Test
    void unexplainedNumberIsStoredAsThatNumberAndItsPage() throws Exception {
        long page3 = page(3);
        long page4 = page(4);
        long page5 = page(5);
        line(page3, 0, "Drawings are listed in the annexure.");
        line(page4, 0, "194.25");
        line(page5, 0, "Notes follow on the next sheet.");
        for (long pageId : List.of(page3, page4, page5)) {
            member(reportId, pageId);
        }

        new ExtractService().read(dbPath, reportId, MANDATE, reportPack,
                new RecordingClient(), "test");

        List<FactRow> facts = factsOf(reportId);
        assertThat(facts).hasSize(1);
        FactRow number = facts.get(0);
        assertThat(number.valueText()).isEqualTo("194.25");
        assertThat(number.fieldKey()).isEqualTo("194.25");
        assertThat(pagesOf(number)).containsExactly(4);
        assertThat(number.ontologyPath()).isNull();
        assertThat(facts).noneMatch(PageBreakExtractTest::mentionsSanctionedLimit);
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

    private void table(long pageId, String[][] rows) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long blockId = docs.insertBlock(pageId, 0, "table", null, null, null, null,
                    "table", "table", 0, "test");
            int cols = rows[0].length;
            boolean headed = rows.length > 1;
            long grid = docs.insertTableGrid(blockId, rows.length, cols, headed ? 1 : 0);
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    docs.insertTableCell(grid, r, c, rows[r][c], null, null, null, null,
                            headed && r == 0);
                }
            }
        }
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

    private static boolean mentionsSanctionedLimit(FactRow fact) {
        return containsSanctioned(fact.fieldKey())
                || containsSanctioned(fact.ontologyPath())
                || containsSanctioned(fact.lineKey())
                || containsSanctioned(fact.subjectEntity())
                || containsSanctioned(fact.valueText());
    }

    private static boolean containsSanctioned(String text) {
        return text != null && text.toLowerCase().contains("sanctioned");
    }

    private static FactRow lineFact(List<FactRow> facts, String fieldKey, String lineKey) {
        return facts.stream()
                .filter(fact -> fieldKey.equals(fact.fieldKey()) && lineKey.equals(fact.lineKey()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Integer> pagesOf(FactRow fact) {
        return fact.evidence().stream()
                .map(FactRow.EvidenceRow::pageIndex)
                .filter(Objects::nonNull)
                .toList();
    }
}
