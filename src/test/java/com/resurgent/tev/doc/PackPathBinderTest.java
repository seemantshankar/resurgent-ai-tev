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
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pack-path bind (T8): pack fields bound with ontology_path/path_root; extras stay
 * unbound with {@code no_pack_field}; derived facts bind to the primary leaf path.
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
    void packFieldsBindAndExtrasStayUnbound() throws Exception {
        PackPathBinder.BindOutcome outcome = new PackPathBinder().bind(dbPath, parseRunId, pack);

        // subject_name + assets_total + liabilities_total + derived net_worth
        assertThat(outcome.bound()).isEqualTo(4);
        // pan_number has no pack field
        assertThat(outcome.unbound()).isEqualTo(1);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId)
                    .withVersion(documentVersionId));

            FactRow subject = byKey(rows, "subject_name");
            assertThat(subject.ontologyPath()).isEqualTo("identity > Legal Name");
            assertThat(subject.pathRoot()).isEqualTo("identity");

            FactRow assets = byKey(rows, "assets_total");
            assertThat(assets.ontologyPath()).isEqualTo("economic > Promoter Net Worth > Assets");
            assertThat(assets.pathRoot()).isEqualTo("economic");

            FactRow netWorth = byKey(rows, "net_worth");
            assertThat(netWorth.extractionMethod()).isEqualTo("derived");
            assertThat(netWorth.ontologyPath())
                    .isEqualTo("economic > Promoter Net Worth > Net Worth");

            // nomenclature_status via SQL
            assertThat(statusOf(db.connection(), "pan_number")).isEqualTo("unbound");
            assertThat(unboundReasonOf(db.connection(), "pan_number"))
                    .isEqualTo("no_pack_field");
            assertThat(statusOf(db.connection(), "assets_total")).isEqualTo("bound");
            assertThat(statusOf(db.connection(), "net_worth")).isEqualTo("bound");
        }
    }

    @Test
    void bindWritesRunStatCounts() throws Exception {
        new PackPathBinder().bind(dbPath, parseRunId, pack);
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
            assertThat(stats.get("facts_bound")).isEqualTo(4.0); // 3 pack + derived net_worth
            assertThat(stats.get("facts_unbound")).isEqualTo(1.0); // pan_number
        }
    }

    @Test
    void lineItemsBindToTablePath() throws Exception {
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
                new PackPathBinder().bind(dbPath, extracted.parseRunId(), quotation);

        assertThat(bound.bound()).isEqualTo(2);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId).withVersion(versionId));
            FactRow line = byKey(rows, "line_items");
            assertThat(line.ontologyPath()).isEqualTo("economic > Project Cost > Line Item");
            assertThat(statusOf(db.connection(), "line_items")).isEqualTo("bound");
        }
    }

    private static FactRow byKey(List<FactRow> rows, String fieldKey) {
        return rows.stream().filter(r -> fieldKey.equals(r.fieldKey())).findFirst().orElseThrow();
    }

    private static String statusOf(Connection c, String fieldKey) throws Exception {
        try (var ps = c.prepareStatement(
                "SELECT nomenclature_status FROM fact WHERE field_key = ? LIMIT 1")) {
            ps.setString(1, fieldKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String unboundReasonOf(Connection c, String fieldKey) throws Exception {
        try (var ps = c.prepareStatement(
                "SELECT unbound_reason FROM fact WHERE field_key = ? LIMIT 1")) {
            ps.setString(1, fieldKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
