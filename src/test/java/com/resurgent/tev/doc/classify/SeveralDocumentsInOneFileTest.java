package com.resurgent.tev.doc.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.assess.AssessChecklistRunner;
import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.DocumentRepository.HeldDocument;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.db.ReportQuestionQueryRunner;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.doc.query.ReportAnswer;
import com.resurgent.tev.doc.query.ReportQuestion;
import com.resurgent.tev.doc.version.FactSupersessionService;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One file holds as many documents as the client included (issue #183).
 * Synthetic pages in the test database. No fixtures, no client workbook, no cloud.
 */
class SeveralDocumentsInOneFileTest {

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
    void projectReportThenAgreementAreTwoDocumentsAndBothChecklistLinesAreProvided()
            throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Volume 2", "volume-2.pdf", "other", null, List.of(
                List.of(
                        line("paragraph", "Project capacity: 25 MW"),
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules on single-axis trackers"),
                        line("paragraph", "Plant and machinery: modules, inverters, and mounting structures")),
                List.of(
                        line("paragraph", "The seller is Helios Power Private Limited."),
                        line("paragraph", "The buyer is the state distribution licensee."),
                        line("paragraph", "The tariff is Rs. 3.10 per kWh."),
                        line("paragraph", "The term of the agreement is 25 years."))));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(1);
        assertThat(pagesOf(held, "agreement.ppa")).containsExactly(2);

        MandateQa qa = assess();
        assertThat(item(qa, "dpr").status()).isEqualTo("provided");
        MandateQa.ChecklistResultRow ppa = item(qa, "solar_ppa");
        assertThat(ppa.status()).isEqualTo("provided");
        assertThat(ppa.documentType()).isEqualTo("agreement.ppa");
    }

    @Test
    void indexAndBlankPageBelongToNeitherDocumentAndTheirWordsStaySearchable() throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Volume 2", "volume-2.pdf", "other", null, List.of(
                List.of(
                        line("heading", "Index"),
                        line("paragraph", "Enclosure schedule alpha lists the papers in this volume.")),
                reportPage(),
                List.of(),
                agreementPage()));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(2);
        assertThat(pagesOf(held, "agreement.ppa")).containsExactly(4);
        assertThat(held).allSatisfy(document -> assertThat(document.pages()).doesNotContain(1, 3));

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            List<ReportAnswer> answers = new ReportQuestionQueryRunner(db.connection())
                    .answer(new ReportQuestion(MANDATE, "enclosure schedule alpha"));
            assertThat(answers).anySatisfy(answer -> {
                assertThat(answer.store()).isEqualTo(ReportAnswer.LAYOUT_TEXT);
                assertThat(answer.filePage()).isEqualTo(1);
                assertThat(answer.text()).contains("Enclosure schedule alpha");
            });
        }
    }

    @Test
    void aHeadingThatOnlyNamesTheAgreementStaysWithThePrecedingDocument() throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Volume 2", "volume-2.pdf", "other", null, List.of(
                reportPage(),
                List.of(line("heading", "Annexure: Power Purchase Agreement")),
                agreementPage()));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(1, 2);
        assertThat(pagesOf(held, "agreement.ppa")).containsExactly(3);
    }

    @Test
    void aProjectReportInterruptedByTheAgreementStaysOneDocument() throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Volume 2", "volume-2.pdf", "other", null, List.of(
                reportPage(),
                agreementPage(),
                List.of(line("paragraph",
                        "Plant and machinery: modules, inverters, and mounting structures"))));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(1, 3);
        assertThat(pagesOf(held, "agreement.ppa")).containsExactly(2);
    }

    @Test
    void aThirdWorkIsItsOwnDocument() throws Exception {
        publishPowerPurchaseChecklist();
        Stored file = seed("Volume 2", "volume-2.pdf", "other", null, List.of(
                reportPage(),
                agreementPage(),
                List.of(
                        line("paragraph", "Vendor: Gamma Civil Works"),
                        line("paragraph", "Grand Total: Rs. 8,00,000"))));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(3);
        assertThat(pagesOf(held, "report.dpr")).containsExactly(1);
        assertThat(pagesOf(held, "agreement.ppa")).containsExactly(2);
        assertThat(pagesOf(held, "quotation.vendor")).containsExactly(3);
    }

    @Test
    void twoQuotationsAreTwoDocumentsTheChecklistLineIsProvidedOnceAndBothFactSetsRemain()
            throws Exception {
        Stored file = seed("Quotations", "quotations.pdf", "other", null, List.of(
                List.of(
                        line("paragraph", "Vendor: Alpha Modules Private Limited"),
                        line("paragraph", "Grand Total: Rs. 10,00,000")),
                List.of(
                        line("paragraph", "Vendor: Beta Structures Private Limited"),
                        line("paragraph", "Grand Total: Rs. 4,00,000"))));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(held).extracting(Held::docTypeCode).containsOnly("quotation.vendor");
        Held alpha = held.stream().filter(document -> document.pages().equals(List.of(1)))
                .findFirst().orElseThrow();
        Held beta = held.stream().filter(document -> document.pages().equals(List.of(2)))
                .findFirst().orElseThrow();

        MandateQa qa = assess();
        List<MandateQa.ChecklistResultRow> quotations = qa.checklist().stream()
                .filter(row -> "cost_quotations".equals(row.itemKey()))
                .toList();
        assertThat(quotations).hasSize(1);
        assertThat(quotations.get(0).status()).isEqualTo("provided");

        writeFact(alpha.documentVersionId(), "alpha-run", "gross_total",
                "economic > Project Cost > Quoted Gross",
                "Alpha Modules Private Limited", "1000000");
        writeFact(beta.documentVersionId(), "beta-run", "gross_total",
                "economic > Project Cost > Quoted Gross",
                "Beta Structures Private Limited", "400000");
        FactSupersessionService.resolveSupersession(dbPath, MANDATE);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            List<FactRow> current = new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE));
            assertThat(current).extracting(FactRow::valueText)
                    .containsExactlyInAnyOrder("1000000", "400000");
            assertThat(current).allMatch(FactRow::isCurrent);
        }
    }

    @Test
    void anAmendmentSupersedesOnlyTheFactsItRestates() throws Exception {
        Stored file = seed("Power purchase agreement", "agreement.pdf", "other", null, List.of(
                agreementPage(),
                List.of(
                        line("paragraph", "Amendment No. 1 to the agreement."),
                        line("paragraph", "The seller is Helios Power Private Limited."),
                        line("paragraph", "The tariff is Rs. 3.40 per kWh."))));

        type(file.versionId());

        List<Held> held = heldDocuments();
        assertThat(held).hasSize(2);
        assertThat(held).extracting(Held::docTypeCode).containsOnly("agreement.ppa");
        Held original = held.stream().filter(document -> document.pages().equals(List.of(1)))
                .findFirst().orElseThrow();
        Held amendment = held.stream().filter(document -> document.pages().equals(List.of(2)))
                .findFirst().orElseThrow();

        writeFact(original.documentVersionId(), "original-run", "tariff",
                "commercial > Offtake > Tariff", "Helios Power Private Limited", "3.10");
        writeFact(original.documentVersionId(), "original-term-run", "tenor",
                "commercial > Offtake > Tenor", "Helios Power Private Limited", "25 years");
        writeFact(amendment.documentVersionId(), "amendment-run", "tariff",
                "commercial > Offtake > Tariff", "Helios Power Private Limited", "3.40");
        FactSupersessionService.resolveSupersession(dbPath, MANDATE);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> current = facts.query(FactQuery.forMandate(MANDATE));
            assertThat(current).extracting(FactRow::valueText)
                    .containsExactlyInAnyOrder("3.40", "25 years");
            assertThat(current).filteredOn(row -> "25 years".equals(row.valueText()))
                    .allMatch(row -> row.documentVersionId() == original.documentVersionId());

            List<FactRow> history = facts.query(FactQuery.forMandate(MANDATE).includingHistory());
            FactRow olderTariff = history.stream()
                    .filter(row -> "3.10".equals(row.valueText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(olderTariff.isCurrent()).isFalse();
            assertThat(olderTariff.supersededByFactId()).isNotNull();
        }
    }

    @Test
    void eachDocumentsDateComesFromItsOwnPages() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other",
                "{\"file_modified_at\":\"2020-08-15T00:00:00Z\"}", List.of(
                List.of(
                        line("paragraph", "Report date: 4 January 2024"),
                        line("paragraph", "Project capacity: 25 MW"),
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules"),
                        line("paragraph", "Plant and machinery: modules and inverters")),
                List.of(
                        line("paragraph", "This agreement is executed on 2 June 2025."),
                        line("paragraph", "The seller is Helios Power Private Limited."),
                        line("paragraph", "The buyer is the state distribution licensee."),
                        line("paragraph", "The tariff is Rs. 3.10 per kWh."),
                        line("paragraph", "The term of the agreement is 25 years."))));

        type(file.versionId());
        Held report = heldDocuments().stream()
                .filter(document -> "report.dpr".equals(document.docTypeCode()))
                .findFirst().orElseThrow();
        Held agreement = heldDocuments().stream()
                .filter(document -> "agreement.ppa".equals(document.docTypeCode()))
                .findFirst().orElseThrow();

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa.DocumentDateRow reportDate = dateOf(report.documentVersionId());
        MandateQa.DocumentDateRow agreementDate = dateOf(agreement.documentVersionId());
        assertThat(reportDate.documentDate()).isEqualTo("2024-01-04");
        assertThat(reportDate.dateSource()).isEqualTo("stated");
        assertThat(agreementDate.documentDate()).isEqualTo("2025-06-02");
        assertThat(agreementDate.dateSource()).isEqualTo("stated");
    }

    @Test
    void aDocumentThatStatesNoDateUsesFileMetadataNotItsNeighboursDate() throws Exception {
        Stored file = seed("Volume 2", "volume-2.pdf", "other",
                "{\"file_modified_at\":\"2020-08-15T00:00:00Z\"}", List.of(
                List.of(
                        line("paragraph", "Report date: 4 January 2024"),
                        line("paragraph", "Project capacity: 25 MW"),
                        line("paragraph", "Project cost: Rs. 120 crore"),
                        line("paragraph", "Technology: monocrystalline modules"),
                        line("paragraph", "Plant and machinery: modules and inverters")),
                agreementPage()));

        type(file.versionId());
        Held agreement = heldDocuments().stream()
                .filter(document -> "agreement.ppa".equals(document.docTypeCode()))
                .findFirst().orElseThrow();

        DocAssessRunner.run(MANDATE, dbPath, null);

        MandateQa.DocumentDateRow agreementDate = dateOf(agreement.documentVersionId());
        assertThat(agreementDate.dateSource()).isEqualTo("file_metadata");
        assertThat(agreementDate.documentDate()).isEqualTo("2020-08-15");
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
                    held.add(new Held(document.documentId(), document.documentVersionId(),
                            document.docTypeCode(), pages));
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

    private Stored seed(String logicalName, String fileName, String fileRole, String rawMetadata,
            List<List<Line>> pages) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository repo = new DocumentRepository(db.connection());
            long documentId = repo.insertDocument(MANDATE, logicalName, "other", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", fileName, fileName + "-hash", ".pdf", fileRole,
                    pages.size(), false, null, rawMetadata);
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

    private MandateQa.DocumentDateRow dateOf(long documentVersionId) throws Exception {
        return MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE))
                .documents().stream()
                .filter(row -> row.documentVersionId() == documentVersionId)
                .findFirst()
                .orElseThrow();
    }

    private static MandateQa.ChecklistResultRow item(MandateQa qa, String key) {
        return qa.checklist().stream()
                .filter(row -> key.equals(row.itemKey()))
                .findFirst()
                .orElseThrow();
    }

    private void writeFact(long documentVersionId, String runKey, String fieldKey, String path,
            String subject, String value) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, runKey + ".pdf", "hash-" + runKey,
                    "pdf", "2026-01-01T00:00:00Z", "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceId, MANDATE, "0.1.0-doc-extract",
                    "extract:" + runKey, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                    "success", null);
            new FactRepository(db.connection()).replaceFacts(parseRunId, documentVersionId, List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey(fieldKey)
                            .ontologyPath(path)
                            .subjectEntity(subject)
                            .valueKind("text")
                            .valueText(value)
                            .extractionMethod("deterministic")
                            .build()));
        }
    }

    private static List<Line> reportPage() {
        return List.of(
                line("paragraph", "Project capacity: 25 MW"),
                line("paragraph", "Project cost: Rs. 120 crore"),
                line("paragraph", "Technology: monocrystalline modules on single-axis trackers"),
                line("paragraph", "Plant and machinery: modules, inverters, and mounting structures"));
    }

    private static List<Line> agreementPage() {
        return List.of(
                line("paragraph", "The seller is Helios Power Private Limited."),
                line("paragraph", "The buyer is the state distribution licensee."),
                line("paragraph", "The tariff is Rs. 3.10 per kWh."),
                line("paragraph", "The term of the agreement is 25 years."));
    }

    private static Line line(String kind, String text) {
        return new Line(kind, text);
    }

    private record Line(String kind, String text) {}

    private record Stored(long documentId, long versionId) {}

    private record Held(long documentId, long documentVersionId, String docTypeCode,
            List<Integer> pages) {}
}
