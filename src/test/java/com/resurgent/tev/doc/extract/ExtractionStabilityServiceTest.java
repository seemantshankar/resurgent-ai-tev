package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.UnstableReadingRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionStabilityServiceTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentVersionId;
    private final long mandateId = 1L;
    private DocTypePack pack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "quote.pdf", "hash-q", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(mandateId, "Quote", "quotation", null);
            documentVersionId = docs.insertDocumentVersion(documentId, "v1", "quote.pdf",
                    "hash-q", ".pdf", "quotation", 1, false, sourceFileId, null);
        }
        pack = new DocTypePack(
                "quotation.vendor", "1.0.0", "quotation", "quotation",
                List.of("general"), List.of("Quotation"),
                List.of(PackField.required(
                        "gross_total", "economic > Project Cost > Quoted Gross", "economic",
                        ValueKind.MONEY)),
                List.of(), List.of(), List.of());
    }

    @Test
    void recordsUnstableReadingWhenTwoRunsDisagree() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);
            long sourceFileId = sourceFileId(c, documentVersionId);
            long runA = repo.insertParseRun(sourceFileId, mandateId, ExtractService.PARSER_VERSION,
                    "extract:stability-test:a", "now", "now", "success", null);
            long runB = repo.insertParseRun(sourceFileId, mandateId, ExtractService.PARSER_VERSION,
                    "extract:stability-test:b", "now", "now", "success", null);
            FactRepository facts = new FactRepository(c);
            facts.replaceFacts(runA, documentVersionId, List.of(
                    factWrite("gross_total", "Rs. 4,07,57,810.18", 21)));
            facts.replaceFacts(runB, documentVersionId, List.of(
                    factWrite("gross_total", "Rs. 73,88,29,781.43", 23)));
            ExtractionStabilityService.StabilityOutcome outcome =
                    ExtractionStabilityService.compareParseRuns(
                            c, mandateId, documentVersionId, pack, runA, runB, null);
            assertThat(outcome.unstableSlots()).isEqualTo(1);
            assertThat(new UnstableReadingRepository(c).listForVersion(documentVersionId))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.valueA()).contains("4,07,57,810");
                        assertThat(row.valueB()).contains("73,88,29,781");
                        assertThat(row.reason()).isEqualTo("unstable_across_runs");
                    });
        }
    }

    private static FactRepository.FactWrite factWrite(String key, String value, int page) {
        return new FactRepository.FactWrite(
                key, null, "economic", null, "money", value, null, null, null, null, null, null,
                null, "llm_field", "test", 0.95, "stated", null, null,
                List.of(Citation.of(page, "Grand Total " + value)), List.of(), null);
    }

    private static long sourceFileId(Connection c, long versionId) throws Exception {
        try (var ps = c.prepareStatement(
                "SELECT source_file_id FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, versionId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("source_file_id");
            }
        }
    }
}
