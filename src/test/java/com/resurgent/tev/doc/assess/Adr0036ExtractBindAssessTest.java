package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.ConflictRepository.ConflictFactRow;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0036 pipeline seam: extract → bind → optional publish → conflict identity (#204).
 */
class Adr0036ExtractBindAssessTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    static final class SilentCloud implements com.resurgent.tev.doc.extract.ExtractClient {
        @Override
        public String id() {
            return "cloud";
        }

        @Override
        public com.resurgent.tev.doc.extract.ExtractResult extractFieldSchema(
                com.resurgent.tev.doc.extract.ExtractRequest req,
                com.fasterxml.jackson.databind.JsonNode schema) {
            throw new AssertionError("labeled lines must not need cloud");
        }
    }

    @Test
    void tanLabeledLineBindsPublishesAndGroupsDocDocOnBoundPath() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        DocTypePack pack = thinCertificate();
        long first = stageCertificate(dbPath, "coi.pdf", "hash-1",
                List.of("CIN : U35106TS2026PTC209425", "TAN : HYDB16570B", "Nominee director :"));

        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, first, MANDATE, pack, new SilentCloud(), "test");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);

        FactRow tan = byKey(dbPath, first, "tan");
        assertThat(tan.nomenclatureStatus()).isEqualTo("bound");
        assertThat(tan.ontologyPath()).contains("TAN");
        DocTypePack published = new PackStore(tempDir.resolve("packs"))
                .latest("general", pack.code()).orElseThrow();
        assertThat(published.fields()).extracting(PackField::key).contains("tan");
        assertThat(PackLoader.loadSeedPacks()).noneMatch(p -> p.fields().stream()
                .anyMatch(f -> "tan".equals(f.key())));

        FactRow blank = byKey(dbPath, first, "nominee_director");
        assertThat(blank.nomenclatureStatus()).isEqualTo("unbound");
        assertThat(blank.unboundReason()).isEqualTo("not_a_value");

        long second = stageCertificate(dbPath, "coi-2.pdf", "hash-2",
                List.of("CIN : U35106TS2026PTC209425", "TAN : HYDB16570B"));
        ExtractService.ExtractOutcome secondExtract = new ExtractService().read(
                dbPath, second, MANDATE, published, new SilentCloud(), "test");
        new PackPathBinder().bind(dbPath, secondExtract.parseRunId(), MANDATE, published, null);
        List<FactRow> secondFacts = facts(dbPath, second);
        assertThat(secondFacts.stream().filter(f -> "tan".equals(f.fieldKey())).count())
                .isEqualTo(1);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            db.connection().setAutoCommit(false);
            List<ConflictScan.Written> written = ConflictScan.scan(
                    db.connection(), MANDATE, null, null, usage -> { });
            db.connection().commit();
            assertThat(written).isEmpty();
            List<FactRow> all = new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE));
            ConflictFactRow boundTan = conflictRow(byKey(all, "tan"));
            ConflictFactRow unbound = conflictRow(blank);
            assertThat(ConflictScan.compareBoundPath(boundTan)).startsWith("path:");
            assertThat(ConflictScan.compareBoundPath(unbound)).isEqualTo("field:nominee_director");
        }
    }

    private static DocTypePack thinCertificate() {
        return new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "identity > CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null, ClauseSection.NONE, List.of());
    }

    private long stageCertificate(Path dbPath, String file, String hash, List<String> lines)
            throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, file, hash, "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long docId = docs.insertDocument(MANDATE, file, "certificate",
                    thinCertificate().code());
            long versionId = docs.insertDocumentVersion(docId, "v1", file, hash,
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i);
                docs.insertBlock(pageId, i, "paragraph", null, null, null, null,
                        text, text, i, "test");
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

    private static List<FactRow> facts(Path dbPath, long docId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(docId));
        }
    }

    private static FactRow byKey(Path dbPath, long docId, String key) throws Exception {
        return byKey(facts(dbPath, docId), key);
    }

    private static FactRow byKey(List<FactRow> facts, String key) {
        return facts.stream().filter(f -> key.equals(f.fieldKey())).findFirst().orElseThrow();
    }

    private static ConflictFactRow conflictRow(FactRow fact) {
        return new ConflictFactRow(
                fact.factId(), fact.documentVersionId(), 1L, "Doc", "doc.pdf",
                "certificate", "2024-01-01",
                fact.fieldKey(), fact.ontologyPath(), fact.nomenclatureStatus(),
                fact.unboundReason(), fact.lineKey(),
                fact.valueKind(), fact.valueText(), fact.valueNumber(), fact.valueDate(),
                fact.unit(), fact.currency(),
                null, null, null, null, null, 1, "quote",
                null, null);
    }
}
