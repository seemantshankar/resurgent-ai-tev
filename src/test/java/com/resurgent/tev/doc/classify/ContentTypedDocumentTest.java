package com.resurgent.tev.doc.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.assess.AssessChecklistRunner;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.DocumentRepository.HeldDocument;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file that is one work is typed from the contents of its pages (issue #180).
 * Synthetic pages in the test database. No fixtures, no client workbook, no cloud.
 */
class ContentTypedDocumentTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO mandate_policy (mandate_id, data_residency)"
                            + " VALUES (?, 'local_only')")) {
                ps.setLong(1, MANDATE);
                ps.executeUpdate();
            }
        }
        blobs = BlobStore.besideDatabase(dbPath);
    }

    @Test
    void projectReportIsTypedFromItsContentsAndItsPagesBelongToIt() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other", List.of(
                List.of(line("paragraph", "Project capacity: 25 MW")),
                List.of(
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules on single-axis trackers"),
                        line("paragraph", "Plant and machinery: modules, inverters, and mounting structures"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("report.dpr");
        assertThat(pageIndexes(file.documentId())).containsExactly(1, 2);

        MandateQa qa = assess();
        MandateQa.ChecklistResultRow dpr = item(qa, "dpr");
        assertThat(dpr.status()).isEqualTo("provided");
        assertThat(dpr.fileName()).isEqualTo("volume-2.pdf");
        assertThat(dpr.pageIndex()).isEqualTo(1);
    }

    @Test
    void installedCapacityAndCapitalCostStillMatchTheProjectReport() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other", List.of(
                List.of(
                        line("paragraph", "Installed capacity: 25 MW"),
                        line("paragraph", "Capital cost of the project: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules"),
                        line("paragraph", "Machinery: inverters and mounting structures"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("report.dpr");
        assertThat(typed.reasons()).anyMatch(reason -> reason.toLowerCase().contains("capacity"));
        assertThat(typed.reasons()).anyMatch(reason -> reason.toLowerCase().contains("cost"));
        assertThat(pageIndexes(file.documentId())).containsExactly(1);
    }

    @Test
    void aMentionOfPpaDoesNotChangeTheProjectReport() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other", List.of(
                List.of(
                        line("paragraph", "Project capacity: 25 MW"),
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules"),
                        line("paragraph", "Plant and machinery: modules and inverters"),
                        line("paragraph", "A PPA may be signed after commissioning."))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("report.dpr");
        assertThat(pageIndexes(file.documentId())).containsExactly(1);
    }

    @Test
    void aCoverThatOnlySaysPpaIsNotADocumentAndTheChecklistStaysNotProvided() throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Cover", "cover.pdf", "other", List.of(
                List.of(
                        line("heading", "PPA"),
                        line("paragraph", "This cover sheet is for the file index only."))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("unknown");
        assertThat(pageIndexes(file.documentId())).isEmpty();

        MandateQa qa = assess();
        MandateQa.ChecklistResultRow ppa = item(qa, "solar_ppa");
        assertThat(ppa.status()).isEqualTo("not_provided");
        assertThat(ppa.documentType()).isEqualTo("agreement.ppa");
        assertThat(ppa.fileName()).isNull();
    }

    @Test
    void aCapacityFigureAloneStaysUnknown() throws Exception {
        Stored file = seed("Figure", "figure.pdf", "other", List.of(
                List.of(line("paragraph", "Capacity: 50 MW"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("unknown");
        assertThat(pageIndexes(file.documentId())).isEmpty();
    }

    @Test
    void anUnknownKindIsInducedFromItsContentsAndKept() throws Exception {
        Stored file = seed("Rating note", "rating-note.pdf", "other", List.of(
                List.of(line("heading", "Detailed Project Report")),
                List.of(
                        line("paragraph", "Instrument rating: AA"),
                        line("paragraph", "Rating outlook: Stable"),
                        line("paragraph", "Rated facility: Working capital"),
                        line("paragraph", "Facility pricing: 9.50 percent per annum"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).startsWith("induced.");
        assertThat(typed.docTypeCode()).isNotEqualTo("report.dpr");
        assertThat(typed.classifier()).isEqualTo("induced");
        assertThat(pageIndexes(file.documentId())).containsExactly(1, 2);
        assertThat(publishedPacks()).anyMatch(path -> path.getFileName().toString()
                .startsWith(typed.docTypeCode()));
    }

    @Test
    void anUnknownKindBesideAKnownWorkIsInducedAndKept() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other", List.of(
                List.of(
                        line("paragraph", "Project capacity: 25 MW"),
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules on single-axis trackers"),
                        line("paragraph", "Plant and machinery: modules, inverters, and mounting structures")),
                List.of(
                        line("paragraph", "Instrument rating: AA"),
                        line("paragraph", "Rating outlook: Stable"),
                        line("paragraph", "Rated facility: Working capital"),
                        line("paragraph", "Facility pricing: 9.50 percent per annum")),
                List.of(line("heading", "Credit Rating Report"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("report.dpr");
        List<Held> held = heldDocuments();
        assertThat(held).extracting(Held::docTypeCode).contains("report.dpr");
        Held induced = held.stream()
                .filter(document -> document.docTypeCode() != null
                        && document.docTypeCode().startsWith("induced."))
                .findFirst().orElseThrow();
        assertThat(induced.pages()).containsExactly(2);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(1, 3);
        assertThat(held).hasSize(2);
        assertThat(publishedPacks()).anyMatch(path -> path.getFileName().toString()
                .startsWith(induced.docTypeCode()));
    }

    @Test
    void aHeadingAloneDoesNotBecomeAPack() throws Exception {
        Stored file = seed("Credit Rating Report", "credit-rating.pdf", "other", List.of(
                List.of(line("heading", "Credit Rating Report"))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("unknown");
        assertThat(pageIndexes(file.documentId())).isEmpty();
        assertThat(publishedPacks()).isEmpty();
    }

    @Test
    void anAgreementThatNamesThePartiesTariffAndTermIsAPowerPurchaseAgreement() throws Exception {
        Stored file = seed("Offtake contract", "offtake-contract.pdf", "other", List.of(
                List.of(
                        line("paragraph", "The seller is Helios Power Private Limited."),
                        line("paragraph", "The buyer is the state distribution licensee."),
                        line("paragraph", "The tariff is Rs. 3.10 per kWh."),
                        line("paragraph", "The term of the agreement is 25 years."),
                        line("paragraph", "The capacity of the plant is 20 MW."))));

        DocClassifyRunner.Result typed = type(file.versionId());

        assertThat(typed.docTypeCode()).isEqualTo("agreement.ppa");
        assertThat(pageIndexes(file.documentId())).containsExactly(1);
    }

    private DocClassifyRunner.Result type(long versionId) throws Exception {
        return DocClassifyRunner.run(MANDATE, versionId, dbPath, blobs, null, "general", true, true);
    }

    private MandateQa assess() throws Exception {
        new AssessChecklistRunner().run(MANDATE, dbPath, null);
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
    }

    private List<Held> heldDocuments() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository repo = new DocumentRepository(db.connection());
            List<Held> held = new ArrayList<>();
            for (HeldDocument document : repo.documentsIn(MANDATE)) {
                List<Integer> pages = repo.pageIndexesOf(document.documentId());
                if (!pages.isEmpty()) {
                    held.add(new Held(document.docTypeCode(), pages));
                }
            }
            return held;
        }
    }

    private static List<Integer> pagesOf(List<Held> held, String docType) {
        return held.stream()
                .filter(document -> docType.equals(document.docTypeCode()))
                .findFirst()
                .orElseThrow()
                .pages();
    }

    private List<Integer> pageIndexes(long documentId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new DocumentRepository(db.connection()).pageIndexesOf(documentId);
        }
    }

    private List<Path> publishedPacks() throws Exception {
        Path packs = tempDir.resolve("packs");
        if (!Files.isDirectory(packs)) {
            return List.of();
        }
        try (var walk = Files.walk(packs)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList();
        }
    }

    private void publishPowerPurchaseChecklist() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new AssessmentRepository(db.connection()).insertIndustryRecord(
                    MANDATE, "solar power", "solar_power", false,
                    "The file is for a solar project.", null);
        }
        Path dir = tempDir.resolve("checklists");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("in.solar_power.yaml"), """
                code: in.solar_power
                version: 1.0.0
                scope: industry
                country: IN
                industries:
                  - solar_power
                items:
                  - key: solar_ppa
                    label: Power Purchase Agreement (PPA)
                    level: document
                    granularity: site
                    document_type: agreement.ppa
                    mandatory: true
                    basis:
                      kind: regulation
                      text: "Electricity Act 2003 — a solar generator sells power under a PPA per site"
                    request: "Please share the signed PPA for {site}."
                """);
    }

    private Stored seed(String logicalName, String fileName, String fileRole,
            List<List<Line>> pages) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository repo = new DocumentRepository(db.connection());
            long documentId = repo.insertDocument(MANDATE, logicalName, "other", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", fileName, fileName + "-hash", ".pdf", fileRole,
                    pages.size(), false, null, null);
            int pageIndex = 1;
            for (List<Line> page : pages) {
                long pageId = repo.insertPage(versionId, pageIndex, null, null, null);
                int blockIndex = 0;
                for (Line line : page) {
                    repo.insertBlock(pageId, blockIndex, line.kind(), null, null, null, null,
                            line.text(), line.text(), blockIndex, "test");
                    blockIndex++;
                }
                pageIndex++;
            }
            return new Stored(documentId, versionId);
        }
    }

    private static MandateQa.ChecklistResultRow item(MandateQa qa, String key) {
        return qa.checklist().stream()
                .filter(row -> key.equals(row.itemKey()))
                .findFirst()
                .orElseThrow();
    }

    private static Line line(String kind, String text) {
        return new Line(kind, text);
    }

    private record Line(String kind, String text) {}

    private record Stored(long documentId, long versionId) {}

    private record Held(String docTypeCode, List<Integer> pages) {}
}
