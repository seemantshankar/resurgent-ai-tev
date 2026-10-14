package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tier selection + production parse wiring from an ingested, layouted version. */
class ParseServiceTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        Files.createDirectories(dbPath.getParent());
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // apply migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
    }

    private long ingestAndLayout(String resource, String fileRole) throws Exception {
        Path src = Path.of("src/test/resources/docfixtures", resource);
        if (!Files.exists(src)) {
            src = Path.of("docfixtures", resource);
        }
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        src, MANDATE, resource, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    @Test
    void identityCertificateSelectsAgenticTier() throws Exception {
        long versionId = ingestAndLayout("golden_net_worth.pdf", "certificate");
        assertThat(ParseService.selectTier(dbPath, versionId)).isEqualTo(ParseTier.AGENTIC);
    }

    @Test
    void unknownVersionIsRejected() {
        assertThatThrownBy(() -> ParseService.selectTier(dbPath, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown document_version");
    }

    @Test
    void missingOriginalBlobFailsBeforeCallingTheApi() throws Exception {
        long versionId = ingestAndLayout("golden_net_worth.pdf", "certificate");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository.DocumentVersionRow version = new DocumentRepository(db.connection())
                    .findVersion(versionId).orElseThrow();
            Files.delete(blobs.originalPath(version.fileHash(), version.fileExt()));
        }
        // Client has no key; parse must fail on the missing blob first, never reach the API.
        LlamaParseClient client = new LlamaParseClient(Map.of());
        assertThatThrownBy(() -> ParseService.parse(dbPath, versionId, ParseTier.AGENTIC, client))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("original blob not found");
    }

    @Test
    void cloudParsePagePersistenceIsReplaceableAndKeepsPageText() throws Exception {
        long versionId = ingestAndLayout("golden_net_worth.pdf", "certificate");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            CloudParsePageRepository repository = new CloudParsePageRepository(db.connection());
            repository.replacePages(versionId, "pjb-1", "agentic",
                    List.of("# Page 1", "", "# Page 3"),
                    List.of("Page one text", "Page two text", "Page three text"));

            assertThat(repository.loadPageText(versionId, 2)).isEqualTo("Page two text");
            assertThat(repository.loadTextPages(versionId)).containsExactly(
                    "# Page 1", "Page two text", "# Page 3");

            repository.replacePages(versionId, "pjb-2", "agentic_plus",
                    List.of("# Replacement"), List.of("Replacement"));
            assertThat(repository.loadTextPages(versionId)).containsExactly("# Replacement");
        }
    }
}
