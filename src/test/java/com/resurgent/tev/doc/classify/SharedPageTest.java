package com.resurgent.tev.doc.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.DocumentRepository.HeldDocument;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ExtractClient;
import com.resurgent.tev.doc.extract.ExtractRequest;
import com.resurgent.tev.doc.extract.ExtractResult;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.ExtractStats;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A page that states two works belongs to both documents, and each statement
 * stays a fact of the work it belongs to (issue #185, ADR 0031).
 * Synthetic pages, blocks, and tables. No fixtures, no client workbook, no cloud.
 */
class SharedPageTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;

    /** Local reading must not call a cloud extractor. */
    static final class NoCloud implements ExtractClient {
        @Override
        public String id() {
            return "cloud-extract";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
            return new ExtractResult(id(), null, List.of(), List.of(), ExtractStats.empty());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO mandate_policy (mandate_id, data_residency)"
                            + " VALUES (?, 'local_only')")) {
                ps.setLong(1, MANDATE);
                ps.executeUpdate();
            }
        }
        blobs = BlobStore.besideDatabase(dbPath);
    }

    @Test
    void aPageThatEndsTheMachineryTableThenNamesTheSellerAndTheTariffBelongsToBothWorks()
            throws Exception {
        Stored file = seedSharedPage();

        DocClassifyRunner.run(MANDATE, file.versionId(), dbPath, blobs, null, "general", true, true);

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        Held report = held(held, "report.dpr");
        Held agreement = held(held, "agreement.ppa");
        assertThat(report.pages()).containsExactly(1);
        assertThat(agreement.pages()).containsExactly(1);

        ExtractService extract = new ExtractService();
        extract.read(dbPath, report.documentId(), MANDATE, PackLoader.findSeed("report.dpr"),
                new NoCloud(), "test");
        extract.read(dbPath, agreement.documentId(), MANDATE, PackLoader.findSeed("agreement.ppa"),
                new NoCloud(), "test");

        List<FactRow> reportFacts = factsOf(report.documentId());
        List<FactRow> agreementFacts = factsOf(agreement.documentId());

        FactRow quantity = fact(reportFacts, "plant_and_machinery.qty", "Inverter");
        FactRow amount = fact(reportFacts, "plant_and_machinery.amount", "Inverter");
        assertThat(quantity.valueText()).isEqualTo("4");
        assertThat(amount.valueText()).isEqualTo("120");
        assertThat(pageOf(quantity)).isEqualTo(1);
        assertThat(pageOf(amount)).isEqualTo(1);
        assertThat(reportFacts).noneMatch(fact -> "seller_name".equals(fact.fieldKey())
                || "tariff".equals(fact.fieldKey()));
        assertThat(agreementFacts).noneMatch(fact -> fact.fieldKey() != null
                && fact.fieldKey().startsWith("plant_and_machinery"));

        FactRow seller = fact(agreementFacts, "seller_name", null);
        FactRow tariff = fact(agreementFacts, "tariff", null);
        assertThat(seller.valueText()).contains("Helios Power");
        assertThat(tariff.valueText()).contains("3.10");
        assertThat(pageOf(seller)).isEqualTo(1);
        assertThat(pageOf(tariff)).isEqualTo(1);
    }

    private Stored seedSharedPage() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository files = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = files.insertSourceFile(MANDATE, "volume-2.pdf", "hash-volume-2",
                    "pdf", "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(MANDATE, "Volume 2", "other", null);
            long versionId = docs.insertDocumentVersion(
                    documentId, "v1", "volume-2.pdf", "hash-volume-2", ".pdf", "other",
                    1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, null, null, null);
            long tableBlock = docs.insertBlock(pageId, 0, "table", null, null, null, null,
                    "Plant and machinery", "Plant and machinery", 0, "test");
            long grid = docs.insertTableGrid(tableBlock, 2, 3, 1);
            cell(docs, grid, 0, 0, "Equipment");
            cell(docs, grid, 0, 1, "Quantity");
            cell(docs, grid, 0, 2, "Amount");
            cell(docs, grid, 1, 0, "Inverter");
            cell(docs, grid, 1, 1, "4");
            cell(docs, grid, 1, 2, "120");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "Seller: Helios Power Private Limited",
                    "Seller: Helios Power Private Limited", 1, "test");
            docs.insertBlock(pageId, 2, "paragraph", null, null, null, null,
                    "Tariff: Rs. 3.10 per kWh", "Tariff: Rs. 3.10 per kWh", 2, "test");
            return new Stored(documentId, versionId);
        }
    }

    private static void cell(DocumentRepository docs, long grid, int row, int col, String text)
            throws Exception {
        docs.insertTableCell(grid, row, col, text, null, null, null, null, row == 0);
    }

    private List<Held> heldDocuments() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository repo = new DocumentRepository(db.connection());
            List<Held> held = new ArrayList<>();
            for (HeldDocument document : repo.documentsIn(MANDATE)) {
                List<Integer> pages = repo.pageIndexesOf(document.documentId());
                if (!pages.isEmpty()) {
                    held.add(new Held(document.documentId(), document.docTypeCode(), pages));
                }
            }
            return held;
        }
    }

    private static Held held(List<Held> held, String docType) {
        return held.stream()
                .filter(document -> docType.equals(document.docTypeCode()))
                .findFirst()
                .orElseThrow();
    }

    private List<FactRow> factsOf(long documentId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(documentId));
        }
    }

    private static FactRow fact(List<FactRow> facts, String fieldKey, String lineKey) {
        return facts.stream()
                .filter(fact -> fieldKey.equals(fact.fieldKey()))
                .filter(fact -> lineKey == null || lineKey.equals(fact.lineKey()))
                .findFirst()
                .orElseThrow();
    }

    private static int pageOf(FactRow fact) {
        return fact.evidence().stream()
                .map(FactRow.EvidenceRow::pageIndex)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private record Stored(long documentId, long versionId) {}

    private record Held(long documentId, String docTypeCode, List<Integer> pages) {}
}
