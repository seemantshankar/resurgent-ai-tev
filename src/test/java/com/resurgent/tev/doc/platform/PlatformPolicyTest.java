package com.resurgent.tev.doc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.TevDocMain;
import com.resurgent.tev.doc.assess.AssessChecklistRunner;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.cli.DocFactsCommand;
import com.resurgent.tev.doc.cli.DocParseCommand;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.extract.ExtractException;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.extract.LlamaParseClient;
import com.resurgent.tev.doc.extract.PageParseClient;
import com.resurgent.tev.doc.extract.ParseTier;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.BBox;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient.OcrPage;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient.OcrWord;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Platform registry and the spend ceiling, driven through the document commands
 * (issue #189). Synthetic pages only.
 */
class PlatformPolicyTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    @Test
    void policyRecordsTheMandateOnlyWhenARegistryIsNamed() throws Exception {
        Path db = tempDir.resolve("workspace.db");
        Path registry = tempDir.resolve("platform.db");
        Path unnamed = tempDir.resolve("not-created.db");
        open(db);
        industry(db, "Hotels", "hotels");
        industry(db, "Solar power", "solar_power");

        String recorded = policy(db, registry);
        assertThat(Files.isRegularFile(registry)).isTrue();
        assertThat(Files.exists(unnamed)).isFalse();
        assertThat(recorded).contains("mandate 1 path " + db.toAbsolutePath().normalize()
                + " industry hotels, solar_power status open");
        assertThat(recorded).doesNotContain("Hotels");
        assertThat(recorded).contains("residency cloud_allowed");
        assertThat(recorded).contains("mandate spend ceiling unset");
        assertThat(recorded).contains("platform spend ceiling unset");
        assertThat(recorded).contains("effective spend ceiling unset");

        String again = policy(db, registry, "--data-residency", "local_only",
                "--spend-ceiling", "0", "--platform-ceiling", "12");
        assertThat(again).contains("residency local_only");
        assertThat(again).contains("mandate spend ceiling 0");
        assertThat(again).contains("platform spend ceiling 12");
        assertThat(again).contains("effective spend ceiling 0");

        String reread = policy(db, registry);
        assertThat(reread).contains("mandate spend ceiling 0");
        assertThat(reread).contains("platform spend ceiling 12");
        assertThat(reread).contains("effective spend ceiling 0");

        String withoutRegistry = policy(db, null);
        assertThat(withoutRegistry).doesNotContain("platform spend ceiling");
        assertThat(withoutRegistry).contains("mandate spend ceiling 0");

        CommandLine facts = new CommandLine(new DocFactsCommand());
        StringWriter err = new StringWriter();
        facts.setErr(new PrintWriter(err, true));
        facts.setOut(new PrintWriter(new StringWriter(), true));
        assertThat(facts.execute("--mandate", "1", "--db", db.toString()))
                .as(err.toString()).isZero();
        assertThat(TevDocMain.commandLine().getSubcommands()).containsKey("policy");
    }

    @Test
    void anUnsetMandateCeilingInheritsThePlatformDefault() throws Exception {
        Path db = tempDir.resolve("workspace.db");
        Path registry = tempDir.resolve("platform.db");
        open(db);
        String recorded = policy(db, registry, "--platform-ceiling", "4");
        assertThat(recorded).contains("mandate spend ceiling unset");
        assertThat(recorded).contains("platform spend ceiling 4");
        assertThat(recorded).contains("effective spend ceiling 4");
    }

    @Test
    void zeroCeilingSkipsTheCloudCallAndMarksTheChecklistUnclear() throws Exception {
        Scan scan = scan();
        policy(scan.db, null, "--spend-ceiling", "0");
        RecordingParseClient cloud = parse(scan.db, scan.versionId, null);
        assertThat(cloud.asked).isEmpty();
        assertThat(pageOutcome(scan.db, scan.versionId)).isEqualTo(
                "local_ocr_unverified_residency_locked");

        DocTypePack pack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        classify(scan.db, scan.versionId, pack);
        FixtureExtractClient extractor = new FixtureExtractClient();
        ObjectNode fixture = new ObjectMapper().createObjectNode();
        ObjectNode field = fixture.putObject("fields").putObject("gross_total");
        field.put("value", "1000");
        field.put("quote", "Grand Total Rs 1000");
        field.put("page", 1);
        extractor.putFixture(pack.code(), fixture);
        ExtractService.ExtractOutcome extracted = new ExtractService().extract(
                scan.db, scan.versionId, MANDATE, pack, extractor, FixtureExtractClient.ID);
        assertThat(extracted.status()).isEqualTo("success");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(scan.db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT resolution FROM fact_evidence e"
                            + " JOIN fact f ON f.fact_id = e.fact_id"
                            + " WHERE f.document_version_id = " + scan.versionId
                            + " AND f.field_key = 'gross_total'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("resolution")).isEqualTo("ambiguous");
            }
        }
        new AssessChecklistRunner().run(MANDATE, scan.db, null);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(scan.db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT r.status FROM checklist_result r"
                            + " JOIN checklist_item i ON i.checklist_item_id = r.checklist_item_id"
                            + " WHERE i.item_key = 'quotation_gross_total'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("unclear");
            }
        }
    }

    @Test
    void anUnsetMandateCeilingInheritsThePlatformDefaultOnTheNextCloudCall() throws Exception {
        Scan scan = scan();
        Path registry = tempDir.resolve("inherit-platform.db");
        policy(scan.db, registry, "--platform-ceiling", "0");
        RecordingParseClient cloud = parse(scan.db, scan.versionId, registry);
        assertThat(cloud.asked).isEmpty();
        assertThat(pageOutcome(scan.db, scan.versionId)).isEqualTo(
                "local_ocr_unverified_residency_locked");
    }

    @Test
    void aCallThatWouldCrossTheCeilingIsSkippedAndAnUnsetCeilingIsNot() throws Exception {
        Scan blocked = scan();
        policy(blocked.db, null, "--spend-ceiling", "0.001");
        RecordingParseClient skipped = parse(blocked.db, blocked.versionId, null);
        assertThat(skipped.asked).isEmpty();

        Scan open = scan();
        RecordingParseClient allowed = parse(open.db, open.versionId, null);
        assertThat(allowed.asked).containsExactly(List.of(1));
    }

    @Test
    void aFactAlreadyReadStaysWhenALaterCallIsSkipped() throws Exception {
        Scan scan = scan();
        DocTypePack pack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        classify(scan.db, scan.versionId, pack);
        FixtureExtractClient extractor = new FixtureExtractClient();
        ObjectNode fixture = new ObjectMapper().createObjectNode();
        ObjectNode field = fixture.putObject("fields").putObject("gross_total");
        field.put("value", "1000");
        field.put("quote", "Grand Total Rs 1000");
        field.put("page", 1);
        extractor.putFixture(pack.code(), fixture);
        new ExtractService().extract(
                scan.db, scan.versionId, MANDATE, pack, extractor, FixtureExtractClient.ID);

        StringWriter before = new StringWriter();
        CommandLine facts = new CommandLine(new DocFactsCommand());
        facts.setOut(new PrintWriter(before, true));
        facts.setErr(new PrintWriter(new StringWriter(), true));
        assertThat(facts.execute("--mandate", "1", "--db", scan.db.toString())).isZero();
        assertThat(before.toString()).contains("1000");

        policy(scan.db, null, "--spend-ceiling", "0");
        RecordingParseClient cloud = parse(scan.db, scan.versionId, null);
        assertThat(cloud.asked).isEmpty();

        StringWriter after = new StringWriter();
        CommandLine again = new CommandLine(new DocFactsCommand());
        again.setOut(new PrintWriter(after, true));
        again.setErr(new PrintWriter(new StringWriter(), true));
        assertThat(again.execute("--mandate", "1", "--db", scan.db.toString())).isZero();
        assertThat(after.toString()).contains("1000");
    }

    private String policy(Path db, Path platform, String... extra) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = TevDocMain.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        List<String> args = new ArrayList<>();
        args.add("policy");
        args.add("--mandate");
        args.add("1");
        args.add("--db");
        args.add(db.toString());
        if (platform != null) {
            args.add("--platform");
            args.add(platform.toString());
        }
        args.addAll(List.of(extra));
        assertThat(cli.execute(args.toArray(String[]::new))).as(err.toString()).isZero();
        return out.toString();
    }

    private static RecordingParseClient parse(Path db, long versionId, Path platform) {
        RecordingParseClient cloud = new RecordingParseClient();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DocParseCommand(cloud));
        cli.setOut(new PrintWriter(new StringWriter(), true));
        cli.setErr(new PrintWriter(err, true));
        List<String> args = new ArrayList<>(List.of(
                "--db", db.toString(), "--version", Long.toString(versionId), "--tier", "fast"));
        if (platform != null) {
            args.add("--platform");
            args.add(platform.toString());
        }
        assertThat(cli.execute(args.toArray(String[]::new))).as(err.toString()).isZero();
        return cloud;
    }

    private Scan scan() throws Exception {
        Path pdf = tempDir.resolve("scan-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(pdf.toFile());
        }
        Path db = tempDir.resolve("ws-" + System.nanoTime() + ".db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        long versionId = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "quotation", "v1", "quotation", "quotation"),
                db, blobs).documentVersionId();
        new DocumentLayoutService((image, widthPx, heightPx) -> new OcrPage(
                line("Quotation for Supply Grand Total Rs 1000", 40, 0.42)))
                .layout(versionId, db, blobs);
        return new Scan(db, versionId);
    }

    private static void open(Path db) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            assertThat(workspace.connection()).isNotNull();
        }
    }

    private static void industry(Path db, String name, String key) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var ps = workspace.connection().prepareStatement(
                        "INSERT INTO mandate_industry_record"
                                + " (mandate_id, industry, industry_key, is_new_industry, reason, created_at)"
                                + " VALUES (?, ?, ?, 1, ?, ?)")) {
            ps.setLong(1, MANDATE);
            ps.setString(2, name);
            ps.setString(3, key);
            ps.setString(4, "read from the registered address");
            ps.setString(5, "2026-10-26T00:00:00Z");
            ps.executeUpdate();
        }
    }

    private static void classify(Path db, long versionId, DocTypePack pack) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            DocumentRepository docs = new DocumentRepository(workspace.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId).orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(workspace.connection(), versionId);
            var result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true), workspace.connection());
            assertThat(result.docTypeCode()).isEqualTo(pack.code());
        }
    }

    private static String pageOutcome(Path db, long versionId) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT routing_outcome FROM page WHERE document_version_id = " + versionId)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static List<OcrWord> line(String text, double y, double confidence) {
        List<OcrWord> words = new ArrayList<>();
        double x = 40;
        for (String part : text.split(" ")) {
            words.add(new OcrWord(part, new BBox(x, y, x + 70, y + 16), confidence));
            x += 80;
        }
        return words;
    }

    private record Scan(Path db, long versionId) {}

    private static final class RecordingParseClient implements PageParseClient {
        private final List<List<Integer>> asked = new ArrayList<>();

        @Override
        public LlamaParseClient.ParseJob parse(Path file, ParseTier tier, List<Integer> targetPages)
                throws ExtractException {
            asked.add(targetPages == null ? List.of() : List.copyOf(targetPages));
            List<String> pages = new ArrayList<>();
            int count = targetPages == null ? 0 : targetPages.size();
            for (int i = 0; i < count; i++) {
                pages.add("CLOUD PAGE");
            }
            return new LlamaParseClient.ParseJob(
                    "pjb-test", "COMPLETED", pages, pages, null, null);
        }
    }
}
