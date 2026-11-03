package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Labels with no real value are stored for bind to mark unbound (#205). */
class LabeledLineNotAValueExtractTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    @Test
    void blankAndPlaceholderValuesAreStoredWithQuote() throws Exception {
        Path dbPath = tempDir.resolve("not-a-value.db");
        DocTypePack pack = new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null,
                com.resurgent.tev.doc.pack.ClauseSection.NONE, List.of());

        long versionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "x.pdf", "h", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long docId = docs.insertDocument(MANDATE, "X", "certificate", pack.code());
            versionId = docs.insertDocumentVersion(docId, "v1", "x.pdf", "h",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "Nominee director :", "Nominee director :", 0, "test");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "Former name : n/a", "Former name : n/a", 1, "test");
            docs.insertBlock(pageId, 2, "paragraph", null, null, null, null,
                    "CIN : U35106TS2026PTC209425", "CIN : U35106TS2026PTC209425", 2, "test");
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            LayoutExtractBridge.LocalReading reading = LayoutExtractBridge.readLocal(
                    db.connection(), versionId, pack, null);
            assertThat(reading.result().facts()).extracting(FactCandidate::key)
                    .contains("cin", "nominee_director", "former_name");
        }
    }
}
