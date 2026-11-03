package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.LabeledLinePackPublisher;
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
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Optional pack leaf publish after labeled-line bind (#206, ADR 0036). */
class LabeledLinePackPublishTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private Path packsRoot;
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
            throw new AssertionError("local reading must not need cloud");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        packsRoot = tempDir.resolve("packs");
        thinCertificate = new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "identity > CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null,
                com.resurgent.tev.doc.pack.ClauseSection.NONE, List.of());

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "coi.pdf", "hash", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            documentId = docs.insertDocument(MANDATE, "COI", "certificate",
                    thinCertificate.code());
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
    void bindPublishesOptionalTanLeafWithoutTouchingSeedPacks() throws Exception {
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, thinCertificate, null);

        PackStore store = new PackStore(packsRoot);
        DocTypePack published = store.latest("general", thinCertificate.code()).orElseThrow();
        assertThat(published.fields()).extracting(PackField::key).contains("cin", "tan");
        assertThat(published.fields().stream().filter(f -> "tan".equals(f.key()))
                .findFirst().orElseThrow().required()).isFalse();
        assertThat(PackLoader.loadSeedPacks()).noneMatch(p -> p.fields().stream()
                .anyMatch(f -> "tan".equals(f.key())));
    }

    @Test
    void secondDocumentLoadsPublishedLeafAndExtractsTanOnce() throws Exception {
        ExtractService.ExtractOutcome first = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        new PackPathBinder().bind(dbPath, first.parseRunId(), MANDATE, thinCertificate, null);

        DocTypePack widened = new PackStore(packsRoot)
                .latest("general", thinCertificate.code()).orElseThrow();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = new WorkspaceRepository(db.connection())
                    .insertSourceFile(MANDATE, "coi-2.pdf", "hash2", "pdf",
                            "2026-01-02T00:00:00Z", "0.1.0", null);
            long doc2 = docs.insertDocument(MANDATE, "COI copy", "certificate",
                    thinCertificate.code());
            long version2 = docs.insertDocumentVersion(doc2, "v1", "coi-2.pdf", "hash2",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(version2, 1, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "CIN : U35106TS2026PTC209425", "CIN : U35106TS2026PTC209425", 0, "test");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "TAN : HYDB16570B", "TAN : HYDB16570B", 1, "test");
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, doc2);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }

            ExtractService.ExtractOutcome second = new ExtractService().read(
                    dbPath, doc2, MANDATE, widened, new SilentCloud(), "test");
            assertThat(second.status()).isEqualTo("success");
            List<FactRow> facts = new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(doc2));
            assertThat(facts.stream().filter(f -> "tan".equals(f.fieldKey())).count()).isEqualTo(1);
            assertThat(facts.stream().filter(f -> "tan".equals(f.fieldKey())).findFirst().orElseThrow()
                    .valueText()).isEqualTo("HYDB16570B");
        }
    }

    @Test
    void publishesOptionalLeafWhenRequiredFieldMissingOnSameDocument() throws Exception {
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        List<LabeledLinePackPublisher.Candidate> candidates = List.of(
                new LabeledLinePackPublisher.Candidate(
                        "tan", "identity > Identity > TAN", "identity", List.of("TAN")));
        LabeledLinePackPublisher.Outcome outcome = LabeledLinePackPublisher.publish(
                dbPath, MANDATE, thinCertificate, extract.parseRunId(), candidates);
        assertThat(outcome.published()).isTrue();
        assertThat(outcome.addedKeys()).containsExactly("tan");
        DocTypePack published = new PackStore(packsRoot)
                .latest("general", thinCertificate.code()).orElseThrow();
        assertThat(published.fields().stream().filter(f -> "cin".equals(f.key()))
                .findFirst().orElseThrow().required()).isTrue();
        assertThat(published.fields().stream().filter(f -> "tan".equals(f.key()))
                .findFirst().orElseThrow().required()).isFalse();
    }

    @Test
    void gateFailurePublishesNothing() throws Exception {
        DocTypePack noRequired = new DocTypePack(
                thinCertificate.code(), "1.0.0", "certificate", "certificate",
                List.of("general"), List.of(),
                List.of(PackField.optional("cin", "identity > CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null,
                com.resurgent.tev.doc.pack.ClauseSection.NONE, List.of());
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, noRequired, new SilentCloud(), "test");
        List<LabeledLinePackPublisher.Candidate> candidates = List.of(
                new LabeledLinePackPublisher.Candidate(
                        "tan", "identity > Identity > TAN", "identity", List.of("TAN")));
        LabeledLinePackPublisher.Outcome outcome = LabeledLinePackPublisher.publish(
                dbPath, MANDATE, noRequired, extract.parseRunId(), candidates);
        assertThat(outcome.published()).isFalse();
        assertThat(outcome.rejectReasons()).isNotEmpty();
        assertThat(Files.exists(packsRoot)).isFalse();
    }
}
