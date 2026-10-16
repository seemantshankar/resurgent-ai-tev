package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FactCandidate;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pack-path bind (T8, read through M3-17 / issue #153): a fact binds only to a path of
 * the shared nomenclature, and every fact that does not is {@code unbound} with the
 * reason — {@code no_pack_field} for a stray, the pack's own path kept so the fact is
 * still retrievable. Offline, a leaf the nomenclature does not know stays unbound too.
 */
class PackPathBinderTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentVersionId;
    private long parseRunId;
    private final long mandateId = 1L;

    private ExtractServiceTest.FakeExtractClient client;
    private DocTypePack pack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        pack = ExtractServiceTest.netWorthPack();
        client = new ExtractServiceTest.FakeExtractClient();

        client.facts.add(new FactCandidate("subject_name", ValueKind.TEXT)
                .valueText("Ramesh Sharma").confidence(0.9).extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("assets_total", ValueKind.MONEY)
                .valueText("Rs. 1,00,00,000").confidence(0.9).extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("liabilities_total", ValueKind.MONEY)
                .valueText("Rs. 25,00,000").confidence(0.9).extractionMethod("llm_field"));
        // stray extra with no pack field
        client.facts.add(new FactCandidate("pan_number", ValueKind.TEXT)
                .valueText("ABCDE1234F").confidence(0.8).extractionMethod("llm_open"));

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId == 0 ? seedDocument() : documentVersionId,
                mandateId, pack, client, "1");
        documentVersionId = outcome.documentVersionId();
        parseRunId = outcome.parseRunId();
    }

    private long seedDocument() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "nw.pdf", "hash-nw", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(mandateId, "NW", "certificate", null);
            return docs.insertDocumentVersion(documentId, "v1", "nw.pdf",
                    "hash-nw", ".pdf", "certificate", 1, false, sourceFileId, null);
        }
    }

    @Test
    void sharedLeavesBindAndNovelOnesStayUnboundOffline() throws Exception {
        PackPathBinder.BindOutcome outcome =
                new PackPathBinder().bind(dbPath, parseRunId, mandateId, pack, null);

        // subject_name is an identity head and derived net_worth is the bank's
        // Balance Sheet > Net Worth; the two promoter-only totals are leaves that
        // nothing in the shared nomenclature names.
        assertThat(outcome.bound()).isEqualTo(2);
        assertThat(outcome.unbound()).isEqualTo(3);

        List<FactRow> rows = facts();
        FactRow subject = byKey(rows, "subject_name");
        assertThat(subject.ontologyPath()).isEqualTo("identity > Project Identity > Legal Name");
        assertThat(subject.pathRoot()).isEqualTo("identity");
        assertThat(subject.nomenclatureReason()).contains("alias");

        FactRow netWorth = byKey(rows, "net_worth");
        assertThat(netWorth.extractionMethod()).isEqualTo("derived");
        assertThat(netWorth.ontologyPath()).isEqualTo("economic > Balance Sheet > Net Worth");
        assertThat(netWorth.nomenclatureStatus()).isEqualTo("bound");

        // an unknown leaf keeps the pack's own path so the fact stays retrievable
        FactRow assets = byKey(rows, "assets_total");
        assertThat(assets.ontologyPath()).isEqualTo("economic > Promoter Net Worth > Assets");
        assertThat(assets.nomenclatureStatus()).isEqualTo("unbound");

        FactRow pan = byKey(rows, "pan_number");
        assertThat(pan.nomenclatureStatus()).isEqualTo("unbound");
    }

    @Test
    void unboundReasonsSayWhyEachFactDidNotBind() throws Exception {
        new PackPathBinder().bind(dbPath, parseRunId, mandateId, pack, null);
        List<FactRow> rows = facts();
        assertThat(byKey(rows, "pan_number").unboundReason()).isEqualTo("no_pack_field");
        assertThat(byKey(rows, "assets_total").unboundReason()).isEqualTo("no_model");
        assertThat(byKey(rows, "assets_total").nomenclatureStatus()).isEqualTo("unbound");
        assertThat(byKey(rows, "net_worth").nomenclatureStatus()).isEqualTo("bound");
    }

    @Test
    void bindWritesRunStatCounts() throws Exception {
        new PackPathBinder().bind(dbPath, parseRunId, mandateId, pack, null);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
                var st = db.connection().createStatement();
                var rs = st.executeQuery(
                        "SELECT name, value_num FROM run_stat WHERE parse_run_id = "
                                + parseRunId + " AND stage = 'doc-bind' ORDER BY name")) {
            java.util.Map<String, Double> stats = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                stats.put(rs.getString(1), rs.getDouble(2));
            }
            assertThat(stats).containsKeys("facts_bound", "facts_unbound", "facts_not_applicable");
            assertThat(stats.get("facts_bound")).isEqualTo(2.0);
            assertThat(stats.get("facts_unbound")).isEqualTo(3.0);
        }
    }

    @Test
    void lineItemsKeepTheirTablePathWhenTheSharedOntologyHasNoSuchLeaf() throws Exception {
        // reuse quotation pack + line fact
        DocTypePack quotation = ExtractServiceTest.quotationPack();
        ExtractServiceTest.FakeExtractClient quotationClient =
                new ExtractServiceTest.FakeExtractClient();
        quotationClient.facts.add(new FactCandidate("vendor_name", ValueKind.TEXT)
                .valueText("Acme").confidence(0.9).extractionMethod("llm_field"));
        quotationClient.facts.add(new FactCandidate("line_items", ValueKind.TEXT)
                .valueText("Panel A").lineKey("Panel A").valueNumber(100.0)
                .confidence(0.9).extractionMethod("llm_field"));

        long versionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "q.pdf", "hash-q", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(mandateId, "Quote", "quotation", null);
            versionId = docs.insertDocumentVersion(documentId, "v1", "q.pdf",
                    "hash-q", ".pdf", "quotation", 1, false, sourceFileId, null);
        }
        ExtractService.ExtractOutcome extracted = new ExtractService().extract(
                dbPath, versionId, mandateId, quotation, quotationClient, "1");
        PackPathBinder.BindOutcome bound =
                new PackPathBinder().bind(dbPath, extracted.parseRunId(), mandateId,
                        quotation, null);

        // the vendor is the shared legal name; "Line Item" is a shape, not a head
        assertThat(bound.bound()).isEqualTo(1);
        assertThat(bound.unbound()).isEqualTo(1);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            List<FactRow> rows = new FactRepository(db.connection())
                    .query(FactQuery.forMandate(mandateId).withVersion(versionId));
            FactRow line = byKey(rows, "line_items");
            assertThat(line.ontologyPath()).isEqualTo("economic > Project Cost > Line Item");
            assertThat(line.nomenclatureStatus()).isEqualTo("unbound");
            assertThat(line.unboundReason()).isEqualTo("no_model");
        }
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(mandateId).withVersion(documentVersionId));
        }
    }

    private static FactRow byKey(List<FactRow> rows, String fieldKey) {
        return rows.stream().filter(r -> fieldKey.equals(r.fieldKey())).findFirst().orElseThrow();
    }
}
