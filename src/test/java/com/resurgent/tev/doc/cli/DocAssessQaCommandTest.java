package com.resurgent.tev.doc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * CLI contract for {@code tev-doc assess} and {@code tev-doc qa} (issue #134), plus the
 * automatic re-assessment after extract/bind: persisted results only, no duplicates.
 */
class DocAssessQaCommandTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private long versionId;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        DocTypePack ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        Path pdf = writeDatedPpa();
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "PPA - Dated", "v1", "agreement", "agreement"),
                dbPath, blobs);
        versionId = ingested.documentVersionId();
        new DocumentLayoutService().layout(versionId, dbPath, blobs);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(ppaPack), true), db.connection());
        }
    }

    private Path writeDatedPpa() throws Exception {
        Path pdf = tempDir.resolve("dated_ppa.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(72, 720);
                cs.showText("Power Purchase Agreement");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("This Power Purchase Agreement is executed on 12 March 2024 by the Seller.");
                cs.endText();
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

    @Test
    void assessThenQaTextListsDocumentsWithDates() {
        Run assess = run(new DocAssessCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(assess.exitCode()).isZero();
        assertThat(assess.out()).contains("assess_profile");

        Run qa = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(qa.exitCode()).isZero();
        assertThat(qa.out())
                .contains("2024-03-12")
                .contains("day")
                .contains("stated")
                .contains("upload_date")
                .contains("PPA - Dated");
    }

    @Test
    void qaJsonCarriesDatesPrecisionSourceAndUploadDate() throws Exception {
        Run assess = run(new DocAssessCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString());
        assertThat(assess.exitCode()).isZero();

        Run qa = run(new DocQaCommand(),
                "--mandate", Long.toString(MANDATE), "--db", dbPath.toString(), "--json");
        assertThat(qa.exitCode()).isZero();

        JsonNode root = new ObjectMapper().readTree(qa.out());
        assertThat(root.get("mandate_id").asLong()).isEqualTo(MANDATE);
        JsonNode doc = root.get("documents").get(0);
        assertThat(doc.get("document_date").asText()).isEqualTo("2024-03-12");
        assertThat(doc.get("date_precision").asText()).isEqualTo("day");
        assertThat(doc.get("date_source").asText()).isEqualTo("stated");
        assertThat(doc.get("upload_date").asText()).isNotBlank();
        JsonNode stage = root.get("stages").get(0);
        assertThat(stage.get("stage").asText()).isEqualTo("assess_profile");
        assertThat(stage.get("status").asText()).isEqualTo("completed");
    }

    @Test
    void extractAutomaticallyReassessesWithoutDuplicates() throws Exception {
        Run extract = run(new DocExtractCommand(),
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(versionId),
                "--db", dbPath.toString(),
                "--pack", "agreement.ppa",
                "--extractor", "fixture");
        assertThat(extract.exitCode()).isZero();

        // No explicit assess call: extract ends by re-running the assessment.
        MandateQa qa = MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
        assertThat(qa.stages())
                .anySatisfy(s -> assertThat(s.stage()).isEqualTo("assess_profile"));
        assertThat(qa.documents().get(0).documentDate()).isEqualTo("2024-03-12");

        Matcher run = Pattern.compile("parse_run: (\\d+)").matcher(extract.out());
        assertThat(run.find()).isTrue();
        Run bind = run(new DocBindCommand(),
                "--mandate", Long.toString(MANDATE),
                "--run", run.group(1),
                "--db", dbPath.toString(),
                "--pack", "agreement.ppa");
        assertThat(bind.exitCode()).isZero();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(),
                    "SELECT COUNT(*) FROM assessment_run WHERE mandate_id = " + MANDATE
                            + " AND stage = 'assess_profile'"))
                    .isEqualTo(1);
            assertThat(count(db.connection(),
                    "SELECT COUNT(*) FROM provenance_doc WHERE entity_type = 'document_date'"))
                    .isEqualTo(1);
        }
        MandateQa after = MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
        assertThat(after.documents()).hasSize(1);
        assertThat(after.stages()).extracting(MandateQa.AssessmentRunRow::stage)
                .contains("assess_profile");
    }

    private static long count(java.sql.Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
