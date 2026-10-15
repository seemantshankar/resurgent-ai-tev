package com.resurgent.tev.doc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * CLI contract for the M3-03 entities section of {@code tev-doc qa} (issue #136):
 * entities with roles, aliases and source pages are listed from the persisted
 * assessment only — text and JSON — never re-parsed from the documents.
 */
class DocQaEntitiesCommandTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        ingestAndLayout(mandateLetterPdf(), "Mandate Letter - ABC", "mandate_letter");
        ingestAndLayout(moaExtractPdf(), "MOA - ABC", "moa");
        DocAssessRunner.run(MANDATE, dbPath, new DocumentReplyLlm());
    }

    @Test
    void qaTextListsEntitiesWithRolesAliasesAndPages() {
        Run qa = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(qa.exitCode()).isZero();
        assertThat(qa.out())
                .contains("entities: 3")
                .contains("borrower: ABC Pvt Ltd")
                .contains("alias: ABC Pvt Ltd (page 1)")
                .contains("alias: ABC Private Limited (page 1)")
                .contains("promoter: RAMESH KUMAR SHARMA")
                .contains("promoter: Suresh Kumar Sharma")
                .contains("pages: 2");
    }

    @Test
    void qaJsonCarriesEntitiesRolesAliasesAndPages() throws Exception {
        Run qa = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString(), "--json");
        assertThat(qa.exitCode()).isZero();

        JsonNode root = new ObjectMapper().readTree(qa.out());
        JsonNode entities = root.get("entities");
        assertThat(entities).hasSize(3);

        JsonNode borrower = entities.get(0);
        assertThat(borrower.get("role").asText()).isEqualTo("borrower");
        assertThat(borrower.get("name").asText()).isEqualTo("ABC Pvt Ltd");
        assertThat(borrower.get("reason").asText()).isNotBlank();
        assertThat(borrower.get("aliases")).hasSize(2);
        assertThat(borrower.get("aliases").get(0).get("alias").asText())
                .isEqualTo("ABC Pvt Ltd");
        assertThat(borrower.get("aliases").get(1).get("alias").asText())
                .isEqualTo("ABC Private Limited");
        assertThat(borrower.get("aliases").get(1).get("page_index").asInt()).isEqualTo(1);
        assertThat(borrower.get("page_indexes").get(0).asInt()).isEqualTo(1);

        JsonNode promoter = entities.get(1);
        assertThat(promoter.get("role").asText()).isEqualTo("promoter");
        assertThat(promoter.get("name").asText()).isEqualTo("RAMESH KUMAR SHARMA");
        assertThat(promoter.get("page_indexes").get(0).asInt()).isEqualTo(2);
    }

    // --- fixtures & helpers ---

    private long ingestAndLayout(Path pdf, String logicalName, String fileRole)
            throws Exception {
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private Path mandateLetterPdf() throws Exception {
        return writePdf("mandate_letter.pdf",
                List.of(
                        "Mandate Letter",
                        "To the Board of Directors, ABC Pvt Ltd (the Borrower)",
                        "Sub: Proposed financing of a 10 MW solar power project.",
                        "Date: 1 January 2025"),
                List.of(
                        "The promoters of the Borrower are:",
                        "RAMESH KUMAR SHARMA",
                        "Suresh Kumar Sharma"));
    }

    private Path moaExtractPdf() throws Exception {
        return writePdf("moa_extract.pdf",
                List.of(
                        "Memorandum of Association",
                        "ABC Private Limited is a company incorporated under the Companies Act, 2013",
                        "Date of incorporation: 15 May 2019"));
    }

    @SafeVarargs
    private Path writePdf(String name, List<String>... pages) throws Exception {
        Path pdf = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 720;
                    for (String line : lines) {
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(72, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 18;
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private record Run(int exitCode, String out, String err) {}

    private Run run(Callable<Integer> command, String... args) {
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        List<String> all = new ArrayList<>(List.of(args));
        Integer exit = cmd.execute(all.toArray(String[]::new));
        return new Run(exit == null ? -1 : exit, out.toString(), err.toString());
    }

    /**
     * Deterministic fake over the doc LLM seam, keyed by document markers, so call
     * order never couples the fixture.
     */
    private static final class DocumentReplyLlm implements DocLlmClient {

        private static final Map<String, String> REPLIES = Map.of(
                "Mandate Letter", """
                        {"entities": [
                          {"name": "ABC Pvt Ltd", "role": "borrower",
                           "quote": "ABC Pvt Ltd (the Borrower)",
                           "reason": "The mandate letter names it as the borrower"},
                          {"name": "RAMESH KUMAR SHARMA", "role": "promoter",
                           "quote": "RAMESH KUMAR SHARMA",
                           "reason": "Listed as a promoter of the borrower"},
                          {"name": "Suresh Kumar Sharma", "role": "promoter",
                           "quote": "Suresh Kumar Sharma",
                           "reason": "Listed as a promoter of the borrower"}]}""",
                "Memorandum of Association", """
                        {"entities": [
                          {"name": "ABC Private Limited", "role": "borrower",
                           "quote": "ABC Private Limited is a company incorporated",
                           "reason": "The MOA names the incorporated company"}]}""");

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Completion complete(String system, String user, int maxTokens) {
            for (Map.Entry<String, String> reply : REPLIES.entrySet()) {
                if (user.contains(reply.getKey())) {
                    return new Completion(reply.getValue(), "fake-model", 100, 50, 0.002, 12);
                }
            }
            return new Completion("{\"entities\": []}", "fake-model", 100, 50, 0.002, 12);
        }
    }
}
