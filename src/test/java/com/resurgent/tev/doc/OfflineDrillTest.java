package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.layout.BBox;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient.OcrWord;
import com.resurgent.tev.doc.platform.CloudClientConstruction;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Offline drill (issue #192). Synthetic pages only. Layout uses the injected
 * fixture reader. Every other stage is a document command. Cloud is forbidden,
 * and the drill fails if a cloud client is constructed.
 */
class OfflineDrillTest {

    private static final long MANDATE = 1L;
    private static final Pattern VERSION = Pattern.compile("version (\\d+)");

    @TempDir
    Path tempDir;

    @Test
    void localOnlyDigitalQuotationFinishesWithoutAnUnclearItem() throws Exception {
        Path db = tempDir.resolve("digital-local.db");
        Path pdf = tempDir.resolve("quotation.pdf");
        writeDigitalQuotation(pdf);
        drill(db, pdf, (image, width, height) -> {
            throw new AssertionError("digital layout must not call the fixture reader");
        }, "--data-residency", "local_only");
        assertThat(checklistStatus(db, "quotation_gross_total")).isEqualTo("provided");
        assertThat(unclearCount(db)).isZero();
    }

    @Test
    void localOnlyScanThatFailsTriageStaysLockedAndUnclear() throws Exception {
        Path db = tempDir.resolve("scan-local.db");
        Path pdf = tempDir.resolve("scan.pdf");
        blankPdf(pdf);
        long version = drill(db, pdf, (image, width, height) -> quotationScan(),
                "--data-residency", "local_only");
        assertThat(pageOutcome(db, version)).isEqualTo("local_ocr_unverified_residency_locked");
        assertThat(checklistStatus(db, "quotation_gross_total")).isEqualTo("unclear");
        assertThat(unclearCount(db)).isPositive();
    }

    @Test
    void zeroCeilingDigitalQuotationFinishesWithoutAnUnclearItem() throws Exception {
        Path db = tempDir.resolve("digital-ceiling.db");
        Path pdf = tempDir.resolve("quotation-ceiling.pdf");
        writeDigitalQuotation(pdf);
        drill(db, pdf, (image, width, height) -> {
            throw new AssertionError("digital layout must not call the fixture reader");
        }, "--spend-ceiling", "0");
        assertThat(checklistStatus(db, "quotation_gross_total")).isEqualTo("provided");
        assertThat(unclearCount(db)).isZero();
    }

    @Test
    void zeroCeilingScanThatFailsTriageStaysLockedAndUnclear() throws Exception {
        Path db = tempDir.resolve("scan-ceiling.db");
        Path pdf = tempDir.resolve("scan-ceiling.pdf");
        blankPdf(pdf);
        long version = drill(db, pdf, (image, width, height) -> quotationScan(),
                "--spend-ceiling", "0");
        assertThat(pageOutcome(db, version)).isEqualTo("local_ocr_unverified_residency_locked");
        assertThat(checklistStatus(db, "quotation_gross_total")).isEqualTo("unclear");
        assertThat(unclearCount(db)).isPositive();
    }

    private long drill(Path db, Path pdf, LocalOcrClient reader, String... policyFlag) throws Exception {
        CloudClientConstruction.watch();
        try {
            String ingested = run("ingest",
                    "--file", pdf.toString(),
                    "--mandate", Long.toString(MANDATE),
                    "--logical-doc", "Vendor quotation",
                    "--file-role", "quotation",
                    "--db", db.toString());
            long version = versionOf(ingested);
            List<String> policy = new ArrayList<>();
            policy.add("policy");
            policy.add("--mandate");
            policy.add(Long.toString(MANDATE));
            policy.add("--db");
            policy.add(db.toString());
            policy.addAll(List.of(policyFlag));
            run(policy.toArray(String[]::new));
            new DocumentLayoutService(reader).layout(version, db, BlobStore.besideDatabase(db));
            run("parse", "--db", db.toString(), "--version", Long.toString(version));
            String classified = run("classify",
                    "--mandate", Long.toString(MANDATE),
                    "--version", Long.toString(version),
                    "--db", db.toString(),
                    "--no-auto-induce");
            assertThat(classified).contains("quotation.vendor");
            run("extract",
                    "--mandate", Long.toString(MANDATE),
                    "--version", Long.toString(version),
                    "--db", db.toString(),
                    "--extractor", "llama-extract",
                    "--no-auto-induce");
            run("induce",
                    "--mandate", Long.toString(MANDATE),
                    "--db", db.toString(),
                    "--doc-type", "quotation.vendor");
            run("assess", "--mandate", Long.toString(MANDATE), "--db", db.toString());
            assertThat(CloudClientConstruction.seen())
                    .as("cloud clients constructed")
                    .isEmpty();
            return version;
        } finally {
            CloudClientConstruction.clear();
        }
    }

    private static String run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = TevDocMain.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        assertThat(cli.execute(args)).as(err.toString()).isZero();
        return out.toString();
    }

    private static long versionOf(String ingested) {
        Matcher matcher = VERSION.matcher(ingested);
        assertThat(matcher.find()).as(ingested).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static void writeDigitalQuotation(Path pdf) throws Exception {
        writePdf(pdf, List.of(
                "Quotation for the supply of project equipment and civil works",
                "Grand Total Rs 1000"));
    }

    private static void blankPdf(Path pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(pdf.toFile());
        }
    }

    /**
     * The residency quotation at confidence 0.42. The total sits on its own line,
     * shifted so the two rows are not read as a table. Mean confidence stays under
     * the triage floor, and the local reader can keep {@code gross_total}.
     */
    private static LocalOcrClient.OcrPage quotationScan() {
        List<OcrWord> words = new ArrayList<>();
        words.addAll(line("Quotation for Supply", 40, 40, 0.42));
        words.addAll(line("Grand Total Rs 1000", 90, 80, 0.42));
        return new LocalOcrClient.OcrPage(words);
    }

    private static List<OcrWord> line(String text, double x, double y, double confidence) {
        List<OcrWord> words = new ArrayList<>();
        for (String part : text.split(" ")) {
            words.add(new OcrWord(part, new BBox(x, y, x + 70, y + 16), confidence));
            x += 80;
        }
        return words;
    }

    private static void writePdf(Path pdf, List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (String line : lines) {
                    stream.beginText();
                    stream.setFont(font, 11);
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 40;
                }
            }
            doc.save(pdf.toFile());
        }
    }

    private static String pageOutcome(Path db, long versionId) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT routing_outcome FROM page WHERE document_version_id = "
                                + versionId)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String checklistStatus(Path db, String itemKey) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT r.status FROM checklist_result r"
                                + " JOIN checklist_item i ON i.checklist_item_id = r.checklist_item_id"
                                + " WHERE i.item_key = '" + itemKey + "'")) {
            assertThat(rs.next()).as(itemKey).isTrue();
            return rs.getString(1);
        }
    }

    private static int unclearCount(Path db) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT COUNT(*) FROM checklist_result WHERE status = 'unclear'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
