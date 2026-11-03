package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FactCandidate;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
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

/** Labeled lines the pack does not name (#205, ADR 0036). */
class LabeledLineExtractBindTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentId;
    private DocTypePack thinCertificate;

    static final class SilentCloud implements com.resurgent.tev.doc.extract.ExtractClient {
        @Override
        public String id() {
            return "cloud";
        }

        @Override
        public com.resurgent.tev.doc.extract.ExtractResult extractFieldSchema(
                com.resurgent.tev.doc.extract.ExtractRequest req,
                com.fasterxml.jackson.databind.JsonNode schema) {
            throw new AssertionError("local reading must not need cloud for labeled lines");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        thinCertificate = new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null,
                com.resurgent.tev.doc.pack.ClauseSection.NONE, List.of());

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "coi.pdf", "hash", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            documentId = docs.insertDocument(MANDATE, "COI", "certificate", thinCertificate.code());
            long versionId = docs.insertDocumentVersion(documentId, "v1", "coi.pdf", "hash",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "CIN : U35106TS2026PTC209425", "CIN : U35106TS2026PTC209425", 0, "test");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "TAN : HYDB16570B", "TAN : HYDB16570B", 1, "test");
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }
    }

    @Test
    void tanLabeledLineIsStoredBoundWithLayoutQuote() throws Exception {
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        assertThat(extract.status()).isEqualTo("success");

        List<FactRow> facts = factsOf(documentId);
        assertThat(facts).extracting(FactRow::fieldKey).contains("cin", "tan");

        PackPathBinder.BindOutcome bind = new PackPathBinder().bind(
                dbPath, extract.parseRunId(), MANDATE, thinCertificate, null);
        facts = factsOf(documentId);
        assertThat(bind.bound()).isGreaterThanOrEqualTo(1);
        FactRow tan = byKey(facts, "tan");
        assertThat(tan.valueText()).isEqualTo("HYDB16570B");
        if (!"bound".equals(tan.nomenclatureStatus())) {
            org.junit.jupiter.api.Assertions.fail(
                    "tan status=" + tan.nomenclatureStatus()
                            + " reason=" + tan.unboundReason()
                            + " path=" + tan.ontologyPath()
                            + " msg=" + tan.nomenclatureReason());
        }
        assertThat(tan.ontologyPath()).contains("TAN");
        assertThat(tan.evidence().get(0).verbatim()).isEqualTo("TAN : HYDB16570B");
        assertThat(tan.ontologyPath()).doesNotContain("Project Cost");

        FactRow cin = byKey(facts, "cin");
        assertThat(cin.valueText()).contains("U35106TS2026PTC209425");
        assertThat(cin.evidence().get(0).verbatim()).isEqualTo("CIN : U35106TS2026PTC209425");
    }

    @Test
    void registeredOfficeContinuationIsOneValue() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long versionId = docs.versionHolding(documentId);
            try (var ps = db.connection().prepareStatement(
                    "UPDATE document_version SET page_count = 2 WHERE document_version_id = ?")) {
                ps.setLong(1, versionId);
                ps.executeUpdate();
            }
            long pageId = docs.insertPage(versionId, 2, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "Registered Office : 123 Main Street", "Registered Office : 123 Main Street",
                    0, "test");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "Jaipur, Rajasthan 302001", "Jaipur, Rajasthan 302001", 1, "test");
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
        }

        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        PackPathBinder.BindOutcome bind = new PackPathBinder().bind(
                dbPath, extract.parseRunId(), MANDATE, thinCertificate, null);
        assertThat(bind.bound()).isGreaterThanOrEqualTo(1);

        FactRow office = byKey(factsOf(documentId), "registered_office");
        assertThat(office.valueText()).contains("Jaipur");
        assertThat(office.valueText()).contains("123 Main Street");
    }

    @Test
    void blankLabeledValueIsStoredUnboundWithQuote() throws Exception {
        Path blankDb = tempDir.resolve("blank-label.db");
        long blankDocId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(blankDb)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "blank.pdf", "hash2", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            blankDocId = docs.insertDocument(MANDATE, "Blank", "certificate", thinCertificate.code());
            long versionId = docs.insertDocumentVersion(blankDocId, "v1", "blank.pdf", "hash2",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "Nominee director :", "Nominee director :", 0, "test");
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, blankDocId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }

        ExtractService.ExtractOutcome extract = new ExtractService().read(
                blankDb, blankDocId, MANDATE, thinCertificate, new SilentCloud(), "test");
        assertThat(extract.status()).isEqualTo("success");
        new PackPathBinder().bind(blankDb, extract.parseRunId(), MANDATE, thinCertificate, null);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(blankDb)) {
            List<FactRow> facts = new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(blankDocId));
            FactRow blank = facts.stream()
                    .filter(f -> "nominee_director".equals(f.fieldKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(blank.valueText()).isBlank();
            assertThat(blank.nomenclatureStatus()).isEqualTo("unbound");
            assertThat(blank.unboundReason()).isEqualTo("not_a_value");
            assertThat(blank.evidence().get(0).verbatim()).isEqualTo("Nominee director :");
        }
    }

    @Test
    void twoColumnIdentityPairUsesValueQuote() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long versionId = docs.versionHolding(documentId);
            try (var ps = db.connection().prepareStatement(
                    "UPDATE document_version SET page_count = 2 WHERE document_version_id = ?")) {
                ps.setLong(1, versionId);
                ps.executeUpdate();
            }
            long pageId = docs.insertPage(versionId, 2, 600, 800, null);
            long grid = docs.insertTableGrid(
                    docs.insertBlock(pageId, 0, "table", null, null, null, null,
                            "identity", "identity", 0, "test"),
                    1, 2, 1);
            docs.insertTableCell(grid, 0, 0, "Registered Office", null, null, null, null, true);
            docs.insertTableCell(grid, 0, 1,
                    "123 Main Street, Jaipur, Rajasthan 302001", null, null, null, null, true);
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
        }

        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        PackPathBinder.BindOutcome bind = new PackPathBinder().bind(
                dbPath, extract.parseRunId(), MANDATE, thinCertificate, null);
        assertThat(bind.bound()).isGreaterThanOrEqualTo(1);

        FactRow office = byKey(factsOf(documentId), "registered_office");
        assertThat(office.valueText()).contains("Jaipur");
        assertThat(office.evidence().stream()
                .anyMatch(e -> e.verbatim() != null && e.verbatim().contains("Jaipur"))).isTrue();
    }

    private List<FactRow> factsOf(long docId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(docId));
        }
    }

    private static FactRow byKey(List<FactRow> facts, String key) {
        return facts.stream().filter(f -> key.equals(f.fieldKey())).findFirst().orElseThrow();
    }
}
