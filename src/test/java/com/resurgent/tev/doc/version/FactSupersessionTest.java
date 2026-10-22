package com.resurgent.tev.doc.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M4-05: Per-fact supersession engine (#167, ADR 0028 §2–§4).
 */
class FactSupersessionTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocumentIngestService ingestService;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        ingestService = new DocumentIngestService();
    }

    @Test
    void amendmentChangingTwoOutOfTenFactsSupersedesOnlyThoseTwo() throws Exception {
        // Base agreement with 10 facts
        Path basePdf = writePdf("ppa_base.pdf", List.of("Power Purchase Agreement", "Date: 01 Jan 2026"));
        var baseRes = ingestService.ingest(new DocumentIngestService.IngestRequest(
                basePdf, MANDATE, "PPA - Jaisingh Pura", "base", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(baseRes.documentVersionId(), "2026-01-01");

        List<FactRepository.FactWrite> baseFacts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            baseFacts.add(new FactRepository.FactWrite.Builder()
                    .fieldKey("term_" + i)
                    .ontologyPath("Commercial > Offtake > Term_" + i)
                    .valueKind("text")
                    .valueText("Original Value " + i)
                    .extractionMethod("deterministic")
                    .build());
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(baseRes.parseRunId(), baseRes.documentVersionId(), baseFacts);
        }

        // Amendment changing only term_2 and term_7
        Path amendPdf = writePdf("ppa_amend1.pdf", List.of("Amendment No. 1 to PPA", "Date: 01 June 2026"));
        var amendRes = ingestService.ingest(new DocumentIngestService.IngestRequest(
                amendPdf, MANDATE, "PPA - Jaisingh Pura - Amendment 1", "Amendment No. 1", "agreement", "agreement"), dbPath, blobs);
        setDocumentDate(amendRes.documentVersionId(), "2026-06-01");

        List<FactRepository.FactWrite> amendFacts = List.of(
                new FactRepository.FactWrite.Builder()
                        .fieldKey("term_2")
                        .ontologyPath("Commercial > Offtake > Term_2")
                        .valueKind("text")
                        .valueText("Amended Value 2")
                        .extractionMethod("deterministic")
                        .build(),
                new FactRepository.FactWrite.Builder()
                        .fieldKey("term_7")
                        .ontologyPath("Commercial > Offtake > Term_7")
                        .valueKind("text")
                        .valueText("Amended Value 7")
                        .extractionMethod("deterministic")
                        .build()
        );

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(amendRes.parseRunId(), amendRes.documentVersionId(), amendFacts);
        }

        // Run version precedence and fact supersession
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
            FactSupersessionService.resolveSupersession(db.connection(), MANDATE);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());

            // Query current facts: exactly 10 active facts
            List<FactRow> currentFacts = factsRepo.query(FactQuery.forMandate(MANDATE));
            assertThat(currentFacts).hasSize(10);

            // term_2 and term_7 are from amendment (amended values)
            FactRow currentTerm2 = findByOntologyPath(currentFacts, "Commercial > Offtake > Term_2");
            assertThat(currentTerm2.valueText()).isEqualTo("Amended Value 2");
            assertThat(currentTerm2.documentVersionId()).isEqualTo(amendRes.documentVersionId());

            FactRow currentTerm7 = findByOntologyPath(currentFacts, "Commercial > Offtake > Term_7");
            assertThat(currentTerm7.valueText()).isEqualTo("Amended Value 7");
            assertThat(currentTerm7.documentVersionId()).isEqualTo(amendRes.documentVersionId());

            // Unchanged terms (e.g. term_1, term_3) are still from the base agreement and active
            FactRow currentTerm1 = findByOntologyPath(currentFacts, "Commercial > Offtake > Term_1");
            assertThat(currentTerm1.valueText()).isEqualTo("Original Value 1");
            assertThat(currentTerm1.documentVersionId()).isEqualTo(baseRes.documentVersionId());

            // Query including history: 12 total facts (10 base + 2 amended)
            List<FactRow> allFacts = factsRepo.query(FactQuery.forMandate(MANDATE).includingHistory());
            assertThat(allFacts).hasSize(12);

            // Trace supersession: the superseded base term_2 points to amended term_2
            FactRow oldTerm2 = allFacts.stream()
                    .filter(f -> f.ontologyPath().equals("Commercial > Offtake > Term_2")
                            && f.documentVersionId() == baseRes.documentVersionId())
                    .findFirst().orElseThrow();
            assertThat(oldTerm2.isCurrent()).isFalse();
            assertThat(oldTerm2.supersededByFactId()).isEqualTo(currentTerm2.factId());
        }
    }

    @Test
    void outOfOrderOlderDocumentDoesNotSupersedeNewerFact() throws Exception {
        // Newer document ingested first (dated June)
        Path pdfJune = writePdf("spec_june.pdf", List.of("Turbine Spec", "Date: 01 June 2026"));
        var resJune = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdfJune, MANDATE, "Turbine Specification", "v2", "technical_report", "technical_report"), dbPath, blobs);
        setDocumentDate(resJune.documentVersionId(), "2026-06-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(resJune.parseRunId(), resJune.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("rated_capacity")
                            .ontologyPath("Technical > Generation > Capacity")
                            .valueKind("quantity")
                            .valueNumber(2.5)
                            .unit("MW")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        // Older document uploaded later (dated April)
        Path pdfApril = writePdf("spec_april.pdf", List.of("Turbine Spec", "Date: 01 April 2026"));
        var resApril = ingestService.ingest(new DocumentIngestService.IngestRequest(
                pdfApril, MANDATE, "Turbine Specification", "v1", "technical_report", "technical_report"), dbPath, blobs);
        setDocumentDate(resApril.documentVersionId(), "2026-04-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(resApril.parseRunId(), resApril.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("rated_capacity")
                            .ontologyPath("Technical > Generation > Capacity")
                            .valueKind("quantity")
                            .valueNumber(2.1)
                            .unit("MW")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
            FactSupersessionService.resolveSupersession(db.connection(), MANDATE);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());

            // Current fact must be the newer June reading (2.5 MW)
            List<FactRow> currentFacts = factsRepo.query(FactQuery.forMandate(MANDATE));
            assertThat(currentFacts).hasSize(1);
            FactRow current = currentFacts.get(0);
            assertThat(current.valueNumber()).isEqualTo(2.5);
            assertThat(current.documentVersionId()).isEqualTo(resJune.documentVersionId());
            assertThat(current.isCurrent()).isTrue();
            assertThat(current.supersededByFactId()).isNull();

            // The older April fact (2.1 MW) was marked not current and points to current June fact
            List<FactRow> allFacts = factsRepo.query(FactQuery.forMandate(MANDATE).includingHistory());
            assertThat(allFacts).hasSize(2);
            FactRow older = allFacts.stream()
                    .filter(f -> f.documentVersionId() == resApril.documentVersionId())
                    .findFirst().orElseThrow();
            assertThat(older.isCurrent()).isFalse();
            assertThat(older.supersededByFactId()).isEqualTo(current.factId());
        }
    }

    @Test
    void newerNetWorthCertificateSupersedesOnlyThatSpecificPromoter() throws Exception {
        // Promoter A certificate (April): 50 Cr
        Path certA1 = writePdf("cert_a1.pdf", List.of("Net Worth Certificate - Sharma", "Date: 01 April 2026"));
        var resA1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                certA1, MANDATE, "NW Certificate - Ramesh Sharma", "v1", "certificate", "certificate"), dbPath, blobs);
        setDocumentDate(resA1.documentVersionId(), "2026-04-01");

        // Promoter B certificate (April): 20 Cr
        Path certB1 = writePdf("cert_b1.pdf", List.of("Net Worth Certificate - Verma", "Date: 01 April 2026"));
        var resB1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                certB1, MANDATE, "NW Certificate - Suresh Verma", "v1", "certificate", "certificate"), dbPath, blobs);
        setDocumentDate(resB1.documentVersionId(), "2026-04-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(resA1.parseRunId(), resA1.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("net_worth")
                            .ontologyPath("Legal > Promoter > NetWorth")
                            .subjectEntity("Ramesh Sharma")
                            .valueKind("money")
                            .valueNumber(50.0)
                            .unit("crore")
                            .currency("INR")
                            .extractionMethod("deterministic")
                            .build()
            ));
            factsRepo.replaceFacts(resB1.parseRunId(), resB1.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("net_worth")
                            .ontologyPath("Legal > Promoter > NetWorth")
                            .subjectEntity("Suresh Verma")
                            .valueKind("money")
                            .valueNumber(20.0)
                            .unit("crore")
                            .currency("INR")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        // Later Promoter A certificate (June): 55 Cr
        Path certA2 = writePdf("cert_a2.pdf", List.of("Net Worth Certificate - Sharma", "Date: 01 June 2026"));
        var resA2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                certA2, MANDATE, "NW Certificate - Ramesh Sharma - June", "v1", "certificate", "certificate"), dbPath, blobs);
        setDocumentDate(resA2.documentVersionId(), "2026-06-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(resA2.parseRunId(), resA2.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("net_worth")
                            .ontologyPath("Legal > Promoter > NetWorth")
                            .subjectEntity("Ramesh Sharma")
                            .valueKind("money")
                            .valueNumber(55.0)
                            .unit("crore")
                            .currency("INR")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
            FactSupersessionService.resolveSupersession(db.connection(), MANDATE);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());

            // Current facts: 2 facts (Sharma 55 Cr, Verma 20 Cr)
            List<FactRow> currentFacts = factsRepo.query(FactQuery.forMandate(MANDATE));
            assertThat(currentFacts).hasSize(2);

            FactRow sharmaCurrent = currentFacts.stream()
                    .filter(f -> f.subjectEntity().equals("Ramesh Sharma"))
                    .findFirst().orElseThrow();
            assertThat(sharmaCurrent.valueNumber()).isEqualTo(55.0);
            assertThat(sharmaCurrent.documentVersionId()).isEqualTo(resA2.documentVersionId());

            FactRow vermaCurrent = currentFacts.stream()
                    .filter(f -> f.subjectEntity().equals("Suresh Verma"))
                    .findFirst().orElseThrow();
            assertThat(vermaCurrent.valueNumber()).isEqualTo(20.0);
            assertThat(vermaCurrent.documentVersionId()).isEqualTo(resB1.documentVersionId());
            assertThat(vermaCurrent.isCurrent()).isTrue();

            // Older Sharma certificate is superseded
            List<FactRow> allFacts = factsRepo.query(FactQuery.forMandate(MANDATE).includingHistory());
            assertThat(allFacts).hasSize(3);
            FactRow sharmaOld = allFacts.stream()
                    .filter(f -> f.subjectEntity().equals("Ramesh Sharma")
                            && f.documentVersionId() == resA1.documentVersionId())
                    .findFirst().orElseThrow();
            assertThat(sharmaOld.isCurrent()).isFalse();
            assertThat(sharmaOld.supersededByFactId()).isEqualTo(sharmaCurrent.factId());
        }
    }

    @Test
    void unboundFactsFallbackTupleMatchesAndSupersedes() throws Exception {
        Path q1 = writePdf("q1.pdf", List.of("Quotation 1", "Date: 10 April 2026"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                q1, MANDATE, "Vendor Quotation - Solar", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res1.documentVersionId(), "2026-04-10");

        Path q2 = writePdf("q2.pdf", List.of("Quotation 2", "Date: 25 April 2026"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                q2, MANDATE, "Vendor Quotation - Solar", "v2", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res2.documentVersionId(), "2026-04-25");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            // Unbound facts: ontologyPath is null, field_key is solar_inverter_rate
            factsRepo.replaceFacts(res1.parseRunId(), res1.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("solar_inverter_rate")
                            .ontologyPath(null)
                            .valueKind("rate")
                            .valueNumber(45.0)
                            .unit("INR/watt")
                            .extractionMethod("deterministic")
                            .build()
            ));
            factsRepo.replaceFacts(res2.parseRunId(), res2.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("solar_inverter_rate")
                            .ontologyPath(null)
                            .valueKind("rate")
                            .valueNumber(42.0)
                            .unit("INR/watt")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
            FactSupersessionService.resolveSupersession(db.connection(), MANDATE);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());

            List<FactRow> currentFacts = factsRepo.query(FactQuery.forMandate(MANDATE));
            assertThat(currentFacts).hasSize(1);
            FactRow current = currentFacts.get(0);
            assertThat(current.valueNumber()).isEqualTo(42.0);
            assertThat(current.documentVersionId()).isEqualTo(res2.documentVersionId());

            List<FactRow> allFacts = factsRepo.query(FactQuery.forMandate(MANDATE).includingHistory());
            assertThat(allFacts).hasSize(2);
            FactRow older = allFacts.stream()
                    .filter(f -> f.documentVersionId() == res1.documentVersionId())
                    .findFirst().orElseThrow();
            assertThat(older.isCurrent()).isFalse();
            assertThat(older.supersededByFactId()).isEqualTo(current.factId());
        }
    }

    @Test
    void supersessionHistoryTraversalFindsChain() throws Exception {
        Path p1 = writePdf("p1.pdf", List.of("Quote 1", "Date: 01 Jan 2026"));
        var res1 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                p1, MANDATE, "BoP Quotation", "v1", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res1.documentVersionId(), "2026-01-01");

        Path p2 = writePdf("p2.pdf", List.of("Quote 2", "Date: 01 Feb 2026"));
        var res2 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                p2, MANDATE, "BoP Quotation", "v2", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res2.documentVersionId(), "2026-02-01");

        Path p3 = writePdf("p3.pdf", List.of("Quote 3", "Date: 01 Mar 2026"));
        var res3 = ingestService.ingest(new DocumentIngestService.IngestRequest(
                p3, MANDATE, "BoP Quotation", "v3", "quotation", "quotation"), dbPath, blobs);
        setDocumentDate(res3.documentVersionId(), "2026-03-01");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            factsRepo.replaceFacts(res1.parseRunId(), res1.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("bop_cost")
                            .ontologyPath("Technical > BoP > Cost")
                            .valueKind("money")
                            .valueNumber(100.0)
                            .unit("crore")
                            .extractionMethod("deterministic")
                            .build()
            ));
            factsRepo.replaceFacts(res2.parseRunId(), res2.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("bop_cost")
                            .ontologyPath("Technical > BoP > Cost")
                            .valueKind("money")
                            .valueNumber(120.0)
                            .unit("crore")
                            .extractionMethod("deterministic")
                            .build()
            ));
            factsRepo.replaceFacts(res3.parseRunId(), res3.documentVersionId(), List.of(
                    new FactRepository.FactWrite.Builder()
                            .fieldKey("bop_cost")
                            .ontologyPath("Technical > BoP > Cost")
                            .valueKind("money")
                            .valueNumber(130.0)
                            .unit("crore")
                            .extractionMethod("deterministic")
                            .build()
            ));
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            VersionPrecedenceService.resolvePrecedenceAndRelations(db.connection(), MANDATE);
            FactSupersessionService.resolveSupersession(db.connection(), MANDATE);
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository factsRepo = new FactRepository(db.connection());
            List<FactRow> currentFacts = factsRepo.query(FactQuery.forMandate(MANDATE));
            assertThat(currentFacts).hasSize(1);
            FactRow currentFact = currentFacts.get(0);
            assertThat(currentFact.valueNumber()).isEqualTo(130.0);

            // History traversal finds prior values in chain
            List<FactRow> history = factsRepo.findSupersessionHistory(currentFact.factId());
            assertThat(history).hasSize(2);
            assertThat(history.get(0).valueNumber()).isEqualTo(120.0);
            assertThat(history.get(1).valueNumber()).isEqualTo(100.0);
        }
    }

    // --- helpers ---

    private FactRow findByOntologyPath(List<FactRow> facts, String path) {
        return facts.stream()
                .filter(f -> path.equals(f.ontologyPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No fact found with ontologyPath: " + path));
    }

    private void setDocumentDate(long documentVersionId, String isoDate) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "UPDATE document_version SET document_date = ? WHERE document_version_id = ?")) {
            ps.setString(1, isoDate);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    private Path writePdf(String fileName, List<String> lines) throws Exception {
        Path path = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
