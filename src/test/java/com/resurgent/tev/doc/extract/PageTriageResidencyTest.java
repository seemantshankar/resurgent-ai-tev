package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.assess.AssessChecklistRunner;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.BBox;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient.OcrPage;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient.OcrWord;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Page triage, selective cloud escalation, and the local-only residency floor
 * (issue #175). Synthetic pages only — no {@code fixtures/} and no network.
 */
class PageTriageResidencyTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    @Test
    void cloudAllowedEscalatesOnlyFailingPagesOfAHybridDocument() throws Exception {
        Path pdf = tempDir.resolve("hybrid.pdf");
        try (PDDocument doc = new PDDocument()) {
            textPage(doc, "This Power Purchase Agreement is made between the Seller and the Buyer.");
            blank(doc);
            blank(doc);
            inkPage(doc);
            blank(doc);
            blank(doc);
            blank(doc);
            blank(doc);
            doc.save(pdf.toFile());
        }

        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        long versionId = ingest(pdf, db, blobs, "hybrid");
        AtomicInteger calls = new AtomicInteger();
        new DocumentLayoutService((image, widthPx, heightPx) -> {
            int scan = calls.incrementAndGet();
            return switch (scan) {
                case 1 -> new OcrPage(line("unreadable scan of the invoice page", 40, 0.45));
                case 2 -> new OcrPage(numericLine());
                case 3 -> new OcrPage(List.of(word("x", 40, 40, 0.99)));
                case 4 -> new OcrPage(concat(
                        row(40, "Description", "Quantity", "Amount"),
                        row(80, "Bolt", "2", "5"),
                        row(120, "Nut", "4")));
                case 5 -> new OcrPage(concat(
                        row(40, "Description", "Quantity", "Amount"),
                        row(80, "Alpha", "2", "10"),
                        row(120, "Beta", "3", "20"),
                        row(160, "Total", "paid", "100")));
                case 6 -> new OcrPage(handwritingPage());
                case 7 -> new OcrPage(line(
                        "This local reading is clear and complete for the page", 40, 0.93));
                default -> throw new AssertionError("unexpected OCR call " + scan);
            };
        }).layout(versionId, db, blobs);
        assertThat(calls).hasValue(7);

        RecordingParseClient cloud = new RecordingParseClient();
        ParseService.parse(db, versionId, ParseTier.FAST, cloud, blobs, Map.of());

        assertThat(cloud.asked).containsExactly(List.of(2, 3, 4, 5, 6, 7));
        Map<Integer, PageRow> pages = pages(db, versionId);
        assertThat(pages.get(1).outcome).isEqualTo("digital_text");
        assertThat(pages.get(1).source).isEqualTo("digital_layout");
        assertThat(pages.get(1).reason).isNull();
        assertThat(pages.get(2).outcome).isEqualTo("escalated_cloud");
        assertThat(pages.get(2).source).isEqualTo("llama_parse");
        assertThat(pages.get(2).reason).contains("0.70");
        assertThat(pages.get(3).reason).contains("numeric");
        assertThat(pages.get(4).reason).contains("ink");
        assertThat(pages.get(5).reason).contains("column");
        assertThat(pages.get(6).reason).contains("printed Total");
        assertThat(pages.get(7).reason).contains("handwriting");
        assertThat(pages.get(8).outcome).isEqualTo("local_ocr_kept");
        assertThat(pages.get(8).source).isEqualTo("local_ocr");
        assertThat(pages.get(8).reason).isNull();

        Map<Integer, String> text = cloudText(db, versionId);
        assertThat(text.get(1)).contains("Power Purchase").doesNotContain("CLOUD");
        assertThat(text.get(2)).contains("CLOUD PAGE 2");
        assertThat(text.get(8)).doesNotContain("CLOUD");
    }

    @Test
    void localOnlyBlocksCloudAndMarksFactsAmbiguousChecklistUnclear() throws Exception {
        Path pdf = tempDir.resolve("quote.pdf");
        try (PDDocument doc = new PDDocument()) {
            blank(doc);
            doc.save(pdf.toFile());
        }
        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        long versionId = ingest(pdf, db, blobs, "quotation");
        new DocumentLayoutService((image, widthPx, heightPx) -> new OcrPage(
                line("Quotation for Supply Grand Total Rs 1000", 40, 0.42)))
                .layout(versionId, db, blobs);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new MandatePolicyRepository(workspace.connection())
                    .upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }

        RecordingParseClient cloud = new RecordingParseClient();
        ParseService.parse(db, versionId, ParseTier.FAST, cloud, blobs, Map.of());
        assertThat(cloud.asked).isEmpty();

        PageRow page = pages(db, versionId).get(1);
        assertThat(page.outcome).isEqualTo("local_ocr_unverified_residency_locked");
        assertThat(page.source).isEqualTo("local_ocr");

        DocTypePack pack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        classify(db, versionId, pack);
        FixtureExtractClient extractor = new FixtureExtractClient();
        ObjectNode fixture = new ObjectMapper().createObjectNode();
        ObjectNode field = fixture.putObject("fields").putObject("gross_total");
        field.put("value", "1000");
        field.put("quote", "Grand Total Rs 1000");
        field.put("page", 1);
        extractor.putFixture(pack.code(), fixture);
        ExtractService.ExtractOutcome extracted = new ExtractService().extract(
                db, versionId, MANDATE, pack, extractor, FixtureExtractClient.ID);
        assertThat(extracted.status()).isEqualTo("success");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT resolution FROM fact_evidence e"
                            + " JOIN fact f ON f.fact_id = e.fact_id"
                            + " WHERE f.document_version_id = " + versionId
                            + " AND f.field_key = 'gross_total'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("resolution")).isEqualTo("ambiguous");
            }
        }

        new AssessChecklistRunner().run(MANDATE, db, null);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT i.item_key, r.status FROM checklist_result r"
                            + " JOIN checklist_item i ON i.checklist_item_id = r.checklist_item_id"
                            + " WHERE i.item_key IN ('cost_quotations', 'quotation_gross_total')")) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    assertThat(rs.getString("status")).isEqualTo("unclear");
                }
                assertThat(rows).isEqualTo(2);
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'ocr_low_confidence'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    void processFloorOverridesACloudAllowedMandateRow() throws Exception {
        Path pdf = tempDir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            blank(doc);
            doc.save(pdf.toFile());
        }
        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        long versionId = ingest(pdf, db, blobs, "scan");
        new DocumentLayoutService((image, widthPx, heightPx) -> new OcrPage(
                line("unreadable scan of the invoice page", 40, 0.45)))
                .layout(versionId, db, blobs);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new MandatePolicyRepository(workspace.connection())
                    .upsert(MANDATE, DataResidency.CLOUD_ALLOWED);
        }
        RecordingParseClient cloud = new RecordingParseClient();
        ParseService.parse(db, versionId, ParseTier.FAST, cloud, blobs,
                Map.of(DataResidency.ENV, DataResidency.LOCAL_ONLY));
        assertThat(cloud.asked).isEmpty();
        assertThat(pages(db, versionId).get(1).outcome)
                .isEqualTo("local_ocr_unverified_residency_locked");
    }

    @Test
    void llamaAdaptersRejectLocalOnlyBeforeAnyHttpCall() {
        assertThatThrownBy(() -> new LlamaParseClient(Map.of(
                "LLAMA_CLOUD_API_KEY", "secret",
                DataResidency.ENV, DataResidency.LOCAL_ONLY))
                .parse(tempDir.resolve("missing.pdf"), ParseTier.FAST, List.of(2)))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("local_only");

        ObjectNode schema = new ObjectMapper().createObjectNode();
        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                "LLAMA_CLOUD_API_KEY", "secret",
                DataResidency.ENV, DataResidency.LOCAL_ONLY))
                .extractFieldSchema(new ExtractRequest(
                        tempDir, null, List.of(), null, null, null, Map.of()), schema))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("local_only");

        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of("LLAMA_CLOUD_API_KEY", "secret"))
                .extractFieldSchema(new ExtractRequest(
                        tempDir, null, List.of(), null, null, null,
                        Map.of(DataResidency.OPTION, DataResidency.LOCAL_ONLY)), schema))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("local_only");
    }

    private static long ingest(Path pdf, Path db, BlobStore blobs, String name) throws Exception {
        return new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, name, "v1", "quotation", "quotation"),
                db, blobs).documentVersionId();
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

    private static Map<Integer, PageRow> pages(Path db, long versionId) throws Exception {
        Map<Integer, PageRow> rows = new LinkedHashMap<>();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_index, routing_outcome, triage_reason, parse_source"
                            + " FROM page WHERE document_version_id = " + versionId
                            + " ORDER BY page_index")) {
                while (rs.next()) {
                    rows.put(rs.getInt("page_index"), new PageRow(
                            rs.getString("routing_outcome"),
                            rs.getString("triage_reason"),
                            rs.getString("parse_source")));
                }
            }
        }
        return rows;
    }

    private static Map<Integer, String> cloudText(Path db, long versionId) throws Exception {
        Map<Integer, String> rows = new LinkedHashMap<>();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_index, page_text FROM cloud_parse_page"
                            + " WHERE document_version_id = " + versionId)) {
                while (rs.next()) {
                    rows.put(rs.getInt("page_index"), rs.getString("page_text"));
                }
            }
        }
        return rows;
    }

    private static void textPage(PDDocument doc, String sentence) throws Exception {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(72, 700);
            cs.showText(sentence);
            cs.endText();
        }
    }

    private static void blank(PDDocument doc) {
        doc.addPage(new PDPage(PDRectangle.LETTER));
    }

    private static void inkPage(PDDocument doc) throws Exception {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.addRect(36, 36, 540, 720);
            cs.fill();
        }
    }

    private static List<OcrWord> line(String text, double y, double confidence) {
        List<OcrWord> words = new ArrayList<>();
        double x = 40;
        for (String part : text.split(" ")) {
            words.add(word(part, x, y, confidence));
            x += 80;
        }
        return words;
    }

    private static List<OcrWord> numericLine() {
        List<OcrWord> words = line("Invoice line items for the supply of equipment", 40, 0.96);
        words.add(word("100", 760, 40, 0.40));
        words.add(word("200", 840, 40, 0.50));
        words.add(word("300", 920, 40, 0.96));
        return words;
    }

    private static List<OcrWord> row(double y, String... cells) {
        double[] xs = {40, 220, 400};
        List<OcrWord> words = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            words.add(word(cells[i], xs[i], y, 0.96));
        }
        return words;
    }

    private static List<OcrWord> handwritingPage() {
        List<OcrWord> words = line(
                "Certified reading of the equipment supply agreement remains clear", 20, 0.95);
        words.add(word("5000", 100, 80, 0.95));
        words.add(new OcrWord("scribble", new BBox(90, 78, 190, 104), 0.40, true));
        return words;
    }

    private static OcrWord word(String text, double x, double y, double confidence) {
        return new OcrWord(text, new BBox(x, y, x + 70, y + 16), confidence);
    }

    @SafeVarargs
    private static List<OcrWord> concat(List<OcrWord>... rows) {
        List<OcrWord> all = new ArrayList<>();
        for (List<OcrWord> row : rows) {
            all.addAll(row);
        }
        return all;
    }

    private record PageRow(String outcome, String reason, String source) {}

    private static final class RecordingParseClient implements PageParseClient {
        private final List<List<Integer>> asked = new ArrayList<>();

        @Override
        public LlamaParseClient.ParseJob parse(Path file, ParseTier tier, List<Integer> targetPages)
                throws ExtractException {
            if (targetPages == null || targetPages.isEmpty()) {
                throw new AssertionError("cloud parse must name the failing pages");
            }
            asked.add(List.copyOf(targetPages));
            List<String> pages = new ArrayList<>();
            for (int page : targetPages) {
                pages.add("CLOUD PAGE " + page);
            }
            return new LlamaParseClient.ParseJob(
                    "pjb-selective", "COMPLETED", pages, pages, null, null);
        }
    }
}
