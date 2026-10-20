package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.BasisEvidenceWrite;
import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fact persistence (S2.5, S2.13): replace-on-re-extract, quote resolution,
 * evidence join, run_stat / audit / ingest_status.
 */
class FactRepositoryTest {

    @TempDir
    Path tempDir;

    private WorkspaceDatabase db;
    private Connection connection;
    private FactRepository facts;
    private DocumentRepository docs;
    private long documentVersionId;
    private long parseRunId;
    private long pageId;

    @BeforeEach
    void setUp() throws Exception {
        db = WorkspaceDatabase.open(tempDir.resolve("workspace.db"));
        connection = db.connection();
        facts = new FactRepository(connection);
        docs = new DocumentRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);

        long sourceFileId = repo.insertSourceFile(1L, "nw.pdf", "hash-nw", "pdf",
                "2026-01-01T00:00:00Z", "0.1.0", null);
        parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0-doc-extract",
                "extract:test@1:1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "success", null);
        long documentId = docs.insertDocument(1L, "NW", "certificate", null);
        documentVersionId = docs.insertDocumentVersion(documentId, "v1", "nw.pdf",
                "hash-nw", ".pdf", "certificate", 1, false, sourceFileId, null);
        pageId = docs.insertPage(documentVersionId, 1, 600, 800, "hash-nw/pages/p001.png");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private void insertLayout() throws Exception {
        long blockId = docs.insertBlock(pageId, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                "Total Assets are Rs. 1,00,00,000", "Total Assets are Rs. 1,00,00,000",
                0, "test");
        docs.insertSpan(blockId, 0, "Total Assets are Rs. 1,00,00,000",
                72.0, 600.0, 500.0, 620.0, 0.99);
        long tableBlock = docs.insertBlock(pageId, 1, "table", 72.0, 100.0, 500.0, 300.0,
                "Item Value", "Item Value", 1, "test");
        long grid = docs.insertTableGrid(tableBlock, 2, 2, 1);
        docs.insertTableCell(grid, 0, 0, "Item", 72.0, 100.0, 200.0, 120.0, true);
        docs.insertTableCell(grid, 1, 1, "42,00,000", 200.0, 120.0, 400.0, 140.0, false);
    }

    private static FactRepository.FactWrite moneyFact(String key, String text, String abs,
            double confidence, Citation... citations) {
        return new FactRepository.FactWrite(
                key, "economic > Test > " + key, "economic", "Sharma",
                "money", text, null, null, null, "INR", "lakh", abs,
                null, "llm_field", "test-extract", confidence, "stated",
                null, null, List.of(citations), List.of());
    }

    @Test
    void replaceFactsDoesNotDuplicateOnReExtract() throws Exception {
        List<FactRepository.FactWrite> first = List.of(
                moneyFact("assets_total", "Rs. 1,00,00,000", "10000000", 0.9),
                moneyFact("liabilities_total", "Rs. 25,00,000", "2500000", 0.9));
        FactRepository.ReplaceStats s1 = facts.replaceFacts(parseRunId, documentVersionId, first);
        assertThat(s1.factsInserted()).isEqualTo(2);
        assertThat(facts.countFactsForParseRun(parseRunId)).isEqualTo(2);

        List<FactRepository.FactWrite> second = List.of(
                moneyFact("assets_total", "Rs. 2,00,00,000", "20000000", 0.95));
        facts.replaceFacts(parseRunId, documentVersionId, second);

        assertThat(facts.countFactsForParseRun(parseRunId)).isEqualTo(1);
        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).valueText()).isEqualTo("Rs. 2,00,00,000");
        assertThat(rows.get(0).confidence()).isEqualTo(0.95);
    }

    @Test
    void quoteResolutionPrefersSpanThenBlockThenTableCell() throws Exception {
        insertLayout();
        Citation spanQuote = Citation.of(1, "Total Assets are Rs. 1,00,00,000");
        Citation blockQuote = Citation.of(1, "Item Value");
        Citation cellQuote = Citation.of(1, "42,00,000");
        Citation missing = Citation.of(1, "no such text anywhere");

        facts.replaceFacts(parseRunId, documentVersionId, List.of(
                moneyFact("a", "x", "1", 0.9, spanQuote),
                moneyFact("b", "y", "2", 0.9, blockQuote),
                moneyFact("c", "z", "3", 0.9, cellQuote),
                moneyFact("d", "w", "4", 0.9, missing)));

        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        assertThat(rows).hasSize(4);

        FactRow a = byKey(rows, "a");
        assertThat(a.evidence()).hasSize(1);
        assertThat(a.evidence().get(0).resolution()).isEqualTo("resolved");
        assertThat(a.evidence().get(0).pageIndex()).isEqualTo(1);
        assertThat(a.evidence().get(0).provenanceId()).isNotNull();
        assertThat(a.evidence().get(0).verbatim()).isEqualTo(spanQuote.verbatim());

        FactRow b = byKey(rows, "b");
        assertThat(b.evidence().get(0).resolution()).isEqualTo("resolved");

        FactRow c = byKey(rows, "c");
        assertThat(c.evidence().get(0).resolution()).isEqualTo("resolved");
        assertThat(c.evidence().get(0).role()).isEqualTo("verbatim_quote");

        FactRow d = byKey(rows, "d");
        assertThat(d.evidence()).hasSize(1);
        assertThat(d.evidence().get(0).resolution()).isEqualTo("missing");
        assertThat(d.evidence().get(0).verbatim()).isEqualTo("no such text anywhere");
        assertThat(d.evidence().get(0).pageIndex()).isEqualTo(1);
    }

    @Test
    void queryFiltersPathKindAndSortsByPathThenConfidence() throws Exception {
        // same path on both money facts so confidence decides the order
        FactRepository.FactWrite low = new FactRepository.FactWrite(
                "low", "economic > NW > Assets", "economic", null,
                "money", "1", null, null, null, null, null, "1",
                null, "llm_field", "test-extract", 0.2, "stated",
                null, null, List.of(), List.of());
        FactRepository.FactWrite high = new FactRepository.FactWrite(
                "high", "economic > NW > Assets", "economic", null,
                "money", "2", null, null, null, null, null, "2",
                null, "llm_field", "test-extract", 0.9, "stated",
                null, null, List.of(), List.of());
        FactRepository.FactWrite name = new FactRepository.FactWrite(
                "name", "identity > Legal Name", "identity", null,
                "text", "Sharma", null, null, null, null, null, null,
                null, "llm_field", "test-extract", 0.8, "stated",
                null, null, List.of(), List.of());
        facts.replaceFacts(parseRunId, documentVersionId, List.of(low, high, name));

        List<FactRow> all = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        assertThat(all).hasSize(3);
        assertThat(all.stream().map(FactRow::fieldKey).toList())
                .containsExactlyInAnyOrder("low", "high", "name");

        // confidence desc within the same ontology path
        List<FactRow> samePath = facts.query(FactQuery.forMandate(1L)
                .withPathPrefix("economic > NW > Assets"));
        assertThat(samePath).hasSize(2);
        assertThat(samePath.get(0).fieldKey()).isEqualTo("high");
        assertThat(samePath.get(1).fieldKey()).isEqualTo("low");

        // path prefix filter
        List<FactRow> identity = facts.query(FactQuery.forMandate(1L)
                .withPathPrefix("identity"));
        assertThat(identity).hasSize(1);
        assertThat(identity.get(0).valueText()).isEqualTo("Sharma");

        List<FactRow> money = facts.query(FactQuery.forMandate(1L).withValueKind("money"));
        assertThat(money).hasSize(2);

        List<FactRow> otherVersion = facts.query(FactQuery.forMandate(1L).withVersion(999L));
        assertThat(otherVersion).isEmpty();
    }

    @Test
    void derivedFactEvidencePointsAtParentKeys() throws Exception {
        facts.replaceFacts(parseRunId, documentVersionId, List.of(
                moneyFact("assets_total", "Rs. 1,00,00,000", "10000000", 0.9),
                moneyFact("liabilities_total", "Rs. 25,00,000", "2500000", 0.9),
                new FactRepository.FactWrite(
                        "net_worth", "economic > NW", "economic", null,
                        "money", "7500000", 7500000.0, null, null, "INR", null,
                        "7500000", null, "derived", "test-extract", 0.9, "derived",
                        null, null, List.of(),
                        List.of("assets_total", "liabilities_total"))));

        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        FactRow netWorth = byKey(rows, "net_worth");
        assertThat(netWorth.extractionMethod()).isEqualTo("derived");
        List<FactRow.EvidenceRow> derivedEvidence = netWorth.evidence().stream()
                .filter(e -> "derived_from".equals(e.role()))
                .toList();
        assertThat(derivedEvidence).hasSize(2);
        assertThat(derivedEvidence.get(0).verbatim()).isEqualTo("assets_total");
        assertThat(derivedEvidence.get(1).verbatim()).isEqualTo("liabilities_total");
    }

    @Test
    void runStatAuditAndIngestStatus() throws Exception {
        facts.recordRunStat(parseRunId, "doc-extract", "facts_total", 2.0, null);
        facts.recordRunStat(parseRunId, "doc-bind", "facts_bound", 1.0, null);
        facts.insertAuditLog(parseRunId, "extract_completed",
                "2026-01-01T00:00:00Z", "{\"facts\":2}", "info");
        facts.updateVersionIngestStatus(documentVersionId, "extracted");

        try (var st = connection.createStatement();
                var rs = st.executeQuery(
                        "SELECT value_num FROM run_stat WHERE parse_run_id = " + parseRunId
                                + " AND stage = 'doc-extract' AND name = 'facts_total'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble(1)).isEqualTo(2.0);
        }
        try (var st = connection.createStatement();
                var rs = st.executeQuery(
                        "SELECT COUNT(*) FROM audit_log WHERE parse_run_id = " + parseRunId
                                + " AND event_type = 'extract_completed'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
        try (var st = connection.createStatement();
                var rs = st.executeQuery(
                        "SELECT ingest_status FROM document_version WHERE document_version_id = "
                                + documentVersionId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("extracted");
        }
    }

    @Test
    void quoteResolvesFromCloudParsePageWhenNoLayoutText() throws Exception {
        // scanned doc: no spans/blocks, only the LlamaParse markdown carries the text
        new CloudParsePageRepository(connection).replacePages(documentVersionId, "pjb-test",
                "agentic",
                List.of("| Net Sales | 571,178,252.43 |\nTotal Income of the year"),
                List.of("Net Sales 571,178,252.43"));

        facts.replaceFacts(parseRunId, documentVersionId, List.of(
                moneyFact("a", "x", "1", 0.9, Citation.of(1, "Net Sales 571,178,252.43")),
                moneyFact("b", "y", "2", 0.9, Citation.of(1, "net sales 571,178,252.43")),
                moneyFact("c", "z", "3", 0.9, Citation.of(1, "text on no parsed page"))));

        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        assertThat(byKey(rows, "a").evidence().get(0).resolution()).isEqualTo("resolved");
        assertThat(byKey(rows, "b").evidence().get(0).resolution()).isEqualTo("resolved");
        assertThat(byKey(rows, "c").evidence().get(0).resolution()).isEqualTo("missing");
    }

    @Test
    void quoteResolvesFromCloudParsePageWithoutPageRow() throws Exception {
        // scan-only docs can have facts cited to pages that never got a layout page row
        long docId = docs.insertDocument(1L, "SCAN", "agreement", null);
        long versionId = docs.insertDocumentVersion(docId, "v1", "scan.pdf",
                "hash-scan", ".pdf", "agreement", 2, true, null, null);
        new CloudParsePageRepository(connection).replacePages(versionId, "pjb-scan", "agentic",
                List.of("Consolidated Balance Sheet", "Gross Profit 25,616,169.45 for the year"),
                List.of("Consolidated Balance Sheet", "Gross Profit 25,616,169.45 for the year"));

        facts.replaceFacts(parseRunId, versionId, List.of(
                moneyFact("gp", "25,616,169.45", "25616169.45", 0.9,
                        Citation.of(2, "Gross Profit 25,616,169.45 for the year"))));

        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(versionId));
        FactRow gp = byKey(rows, "gp");
        assertThat(gp.evidence()).hasSize(1);
        assertThat(gp.evidence().get(0).resolution()).isEqualTo("resolved");
        assertThat(gp.evidence().get(0).provenanceId()).isNotNull();
    }

    @Test
    void basisEvidenceRecordedForUnitCurrencyAndScale() throws Exception {
        insertLayout();
        long scaleBlockId = docs.insertBlock(pageId, 2, "paragraph", 72.0, 700.0, 500.0, 720.0,
                "Project cost (Rs. in Lakhs)", "Project cost (Rs. in Lakhs)", 2, "test");
        long unitBlockId = docs.insertBlock(pageId, 3, "paragraph", 72.0, 500.0, 500.0, 520.0,
                "Installed Capacity 1200 kWp", "Installed Capacity 1200 kWp", 3, "test");
        docs.insertSpan(unitBlockId, 0, "Installed Capacity 1200 kWp", 72.0, 500.0, 500.0, 520.0, 0.99);

        FactRepository.FactWrite fact = new FactRepository.FactWrite(
                "capacity", "technical > Capacity", "technical", null,
                "quantity", "1200", 1200.0, null, "kWp", null, null, null,
                null, "llm_field", "test-extract", 0.9, "stated", null, null,
                List.of(Citation.of(1, "Installed Capacity 1200 kWp")),
                List.of(),
                null,
                List.of(BasisEvidenceWrite.of("unit", "kWp", "kWp", Citation.of(1, "kWp"))));

        FactRepository.FactWrite money = new FactRepository.FactWrite(
                "cost", "economic > Cost", "economic", null,
                "money", "45", 45.0, null, null, "INR", "lakh", "4500000",
                null, "llm_field", "test-extract", 0.9, "stated", null, null,
                List.of(Citation.of(1, "Total Assets are Rs. 1,00,00,000")),
                List.of(),
                null,
                List.of(
                        BasisEvidenceWrite.of("currency", "Rs.", "INR", Citation.of(1, "Rs.")),
                        BasisEvidenceWrite.of("scale", "Rs. in Lakhs", "lakh", Citation.of(1, "Rs. in Lakhs"))));

        facts.replaceFacts(parseRunId, documentVersionId, List.of(fact, money));

        List<FactRow> rows = facts.query(FactQuery.forMandate(1L).withVersion(documentVersionId));
        FactRow capRow = byKey(rows, "capacity");
        assertThat(capRow.unit()).isEqualTo("kWp");
        assertThat(capRow.evidence()).hasSize(2);
        assertThat(capRow.evidence()).anySatisfy(e -> {
            assertThat(e.role()).isEqualTo("verbatim_quote");
            assertThat(e.resolution()).isEqualTo("resolved");
        });
        assertThat(capRow.evidence()).anySatisfy(e -> {
            assertThat(e.role()).isEqualTo("unit");
            assertThat(e.verbatim()).isEqualTo("kWp");
            assertThat(e.normalizedValue()).isEqualTo("kWp");
            assertThat(e.resolution()).isEqualTo("resolved");
            assertThat(e.provenanceId()).isNotNull();
            assertThat(e.pageIndex()).isEqualTo(1);
        });

        FactRow moneyRow = byKey(rows, "cost");
        assertThat(moneyRow.currency()).isEqualTo("INR");
        assertThat(moneyRow.scale()).isEqualTo("lakh");
        assertThat(moneyRow.evidence()).hasSize(3);
        assertThat(moneyRow.evidence()).anySatisfy(e -> {
            assertThat(e.role()).isEqualTo("currency");
            assertThat(e.verbatim()).isEqualTo("Rs.");
            assertThat(e.normalizedValue()).isEqualTo("INR");
            assertThat(e.resolution()).isEqualTo("resolved");
            assertThat(e.provenanceId()).isNotNull();
            assertThat(e.pageIndex()).isEqualTo(1);
        });
        assertThat(moneyRow.evidence()).anySatisfy(e -> {
            assertThat(e.role()).isEqualTo("scale");
            assertThat(e.verbatim()).isEqualTo("Rs. in Lakhs");
            assertThat(e.normalizedValue()).isEqualTo("lakh");
            assertThat(e.resolution()).isEqualTo("resolved");
            assertThat(e.provenanceId()).isNotNull();
            assertThat(e.pageIndex()).isEqualTo(1);
        });
    }

    private static FactRow byKey(List<FactRow> rows, String fieldKey) {
        return rows.stream().filter(r -> fieldKey.equals(r.fieldKey())).findFirst().orElseThrow();
    }
}
