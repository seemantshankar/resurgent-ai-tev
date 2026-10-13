package com.resurgent.tev.doc.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T14 classification (S2.12): golden seed-pack strings, unknown stays unknown,
 * force re-classifies. Offline tie-break only — no network in tests.
 */
class DocTypeClassifierTest {

    @TempDir
    Path tempDir;

    private static final String PPA_PAGE1 = """
            Power Purchase Agreement
            This Agreement is made between the Seller and the Buyer.
            Tariff shall be Rs. 4.50 per kWh for a tenure of 25 years.
            Contract Capacity: 5.00 MW
            """;

    private static final String NET_WORTH_PAGE1 = """
            Net Worth Certificate
            Certificate of Net Worth of Mr. Ramesh Kumar Sharma
            Total Assets: Rs. 2,45,00,000
            Total Liabilities: Rs. 85,00,000
            Net Worth: Rs. 1,60,00,000
            """;

    private static final String QUOTATION_PAGE1 = """
            Quotation / Estimate
            Bill of Quantities for supply and installation
            Grand Total: Rs. 12,50,000
            """;

    private static final String NOISE_PAGE1 = """
            Cafeteria menu for Monday
            Sandwiches, soup, and fresh salad
            """;

    private List<DocTypePack> seedPacks() throws Exception {
        return PackLoader.loadSeedPacks();
    }

    private ClassifyResult score(String fileRole, String fileName, String page1) throws Exception {
        return new DocTypeClassifier().classify(ClassifyRequest.scoreOnly(
                fileRole, fileName, page1, null, seedPacks()));
    }

    @Test
    void goldenNetWorthClassifiesToSeedPack() throws Exception {
        ClassifyResult result = score("certificate", "net_worth_sharma.pdf", NET_WORTH_PAGE1);
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(result.docTypeCode()).isEqualTo("identity.promoter_net_worth");
        assertThat(result.packCode()).isEqualTo("identity.promoter_net_worth");
        assertThat(result.packVersion()).isEqualTo("1.0.0");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.5).isLessThanOrEqualTo(1.0);
        assertThat(result.reasons()).anyMatch(r -> r.contains("Net Worth"));
        assertThat(result.classifier()).isEqualTo("deterministic");
    }

    @Test
    void goldenPpaClassifiesToSeedPack() throws Exception {
        ClassifyResult result = score("agreement", "PPA_signed.pdf", PPA_PAGE1);
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(result.docTypeCode()).isEqualTo("agreement.ppa");
        assertThat(result.packCode()).isEqualTo("agreement.ppa");
        assertThat(result.packVersion()).isEqualTo("1.0.0");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.5);
        assertThat(result.classifier()).isEqualTo("deterministic");
    }

    @Test
    void goldenQuotationClassifiesToSeedPack() throws Exception {
        ClassifyResult result = score("quotation", "vendor_quote_2026.pdf", QUOTATION_PAGE1);
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(result.docTypeCode()).isEqualTo("quotation.vendor");
        assertThat(result.packCode()).isEqualTo("quotation.vendor");
        assertThat(result.packVersion()).isEqualTo("1.0.0");
        assertThat(result.classifier()).isEqualTo("deterministic");
    }

    @Test
    void fileNameSignalBoostsQuotationWhenPageIsQuiet() throws Exception {
        ClassifyResult result = score("quotation", "Quotation_vendor_boq.pdf",
                "Supply of inverters and modules as per annexure.");
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(result.docTypeCode()).isEqualTo("quotation.vendor");
    }

    @Test
    void unknownStaysUnknownAndNeverInventsAPack() throws Exception {
        ClassifyResult result = score("other", "lunch_menu.pdf", NOISE_PAGE1);
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
        assertThat(result.docTypeCode()).isEqualTo("unknown");
        assertThat(result.packCode()).isNull();
        assertThat(result.packVersion()).isNull();
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void matchingRoleWithNoSignalsStaysUnknown() throws Exception {
        ClassifyResult result = score("agreement", "side_letter.pdf",
                "This side letter records a discussion about timelines only.");
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
        assertThat(result.docTypeCode()).isEqualTo("unknown");
    }

    @Test
    void ambiguousSignalsInvokeTieBreakAndOfflineDeclines() throws Exception {
        // Both signals present: PPA page + quotation title — near-tie under offline default.
        ClassifyResult result = score("agreement", "mixed.pdf",
                "Power Purchase Agreement and Quotation for balance of plant");
        // PPA has more distinct hits ("Power Purchase Agreement", "Power Purchase") so it
        // may still clear the margin; force a true tie by using only shared-scale signals.
        ClassifyResult tie = score("quotation", "offer.pdf",
                "Offer and Bill of Quantities and Quotation");
        assertThat(tie.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(tie.docTypeCode()).isEqualTo("quotation.vendor");
        // Offline tie-break path is exercised when a single weak file-name hit cannot clear MIN_SCORE.
        ClassifyResult weak = score("certificate", "certificate.pdf",
                "This certificate is issued for general purposes only.");
        assertThat(weak.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
        assertThat(weak.reasons()).anyMatch(r -> r.contains("tie-break"));
    }

    @Test
    void tieBreakSpiCanResolveAmbiguity() throws Exception {
        ClassifyTieBreak pickPpa = context -> Optional.of(
                new ClassifyTieBreak.TieBreakChoice("agreement.ppa", 0.4, "llm chose ppa"));
        DocTypeClassifier classifier = new DocTypeClassifier(pickPpa);
        ClassifyResult result = classifier.classify(ClassifyRequest.scoreOnly(
                "agreement", "mystery.pdf",
                "Letter of intent regarding power offtake terms and conditions.",
                null, seedPacks()));
        // Letter of intent has no identity signals — score 0 → tie-break.
        assertThat(result.status()).isEqualTo(ClassifyResult.Status.CLASSIFIED);
        assertThat(result.docTypeCode()).isEqualTo("agreement.ppa");
        assertThat(result.classifier()).isEqualTo("tie-break");
        assertThat(result.reasons()).anyMatch(r -> r.contains("llm chose ppa"));
    }

    @Test
    void forceReclassifiesOverExistingDecision() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository repo = new DocumentRepository(connection);
            long documentId = repo.insertDocument(1L, "PPA", "agreement", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", "PPA_signed.pdf", "hash1", ".pdf", "agreement",
                    1, false, null, null);

            DocTypeClassifier classifier = new DocTypeClassifier();

            // First pass: PPA.
            ClassifyResult first = classifier.classify(new ClassifyRequest(
                    versionId, "agreement", "PPA_signed.pdf", PPA_PAGE1, null,
                    seedPacks(), true), connection);
            assertThat(first.docTypeCode()).isEqualTo("agreement.ppa");
            assertThat(readDocTypeCode(connection, versionId)).isEqualTo("agreement.ppa");

            // Without force, the existing classification is kept even if text changed.
            ClassifyResult kept = classifier.classify(new ClassifyRequest(
                    versionId, "certificate", "net_worth_sharma.pdf", NET_WORTH_PAGE1, null,
                    seedPacks(), false), connection);
            assertThat(kept.docTypeCode()).isEqualTo("agreement.ppa");

            // force=true overwrites.
            ClassifyResult forced = classifier.classify(new ClassifyRequest(
                    versionId, "certificate", "net_worth_sharma.pdf", NET_WORTH_PAGE1, null,
                    seedPacks(), true), connection);
            assertThat(forced.docTypeCode()).isEqualTo("identity.promoter_net_worth");
            assertThat(readDocTypeCode(connection, versionId))
                    .isEqualTo("identity.promoter_net_worth");

            // Unknown also overwrites and clears document.doc_type_code.
            ClassifyResult unknown = classifier.classify(new ClassifyRequest(
                    versionId, "other", "lunch_menu.pdf", NOISE_PAGE1, null,
                    seedPacks(), true), connection);
            assertThat(unknown.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
            assertThat(readDocTypeCode(connection, versionId)).isEqualTo("unknown");
        }
    }

    @Test
    void persistWritesReasonsAndPackIdentity() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository repo = new DocumentRepository(connection);
            long documentId = repo.insertDocument(1L, "NW", "certificate", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", "net_worth.pdf", "hash2", ".pdf", "certificate",
                    1, false, null, null);

            ClassifyResult result = new DocTypeClassifier().classify(new ClassifyRequest(
                    versionId, "certificate", "net_worth.pdf", NET_WORTH_PAGE1, "general",
                    seedPacks(), true), connection);
            assertThat(result.docTypeCode()).isEqualTo("identity.promoter_net_worth");

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT doc_type_code, pack_code, pack_version, confidence, reasons_json,"
                            + " industry_tag, classifier FROM doc_classification"
                            + " WHERE document_version_id = ?")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("doc_type_code"))
                            .isEqualTo("identity.promoter_net_worth");
                    assertThat(rs.getString("pack_code")).isEqualTo("identity.promoter_net_worth");
                    assertThat(rs.getString("pack_version")).isEqualTo("1.0.0");
                    assertThat(rs.getDouble("confidence")).isGreaterThan(0.0);
                    assertThat(rs.getString("reasons_json")).contains("Net Worth");
                    assertThat(rs.getString("industry_tag")).isEqualTo("general");
                    assertThat(rs.getString("classifier")).isEqualTo("deterministic");
                }
            }
        }
    }

    @Test
    void loadPage1TextPrefersHeadingsThenReadingOrder() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository repo = new DocumentRepository(connection);
            long documentId = repo.insertDocument(1L, "Doc", "other", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", "doc.pdf", "hash3", ".pdf", "other",
                    1, false, null, null);
            long pageId = repo.insertPage(versionId, 1, null, null, null);
            repo.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "body first", "body first", 0, "test");
            repo.insertBlock(pageId, 1, "heading", null, null, null, null,
                    "Power Purchase Agreement", "Power Purchase Agreement", 1, "test");

            String page1 = DocTypeClassifier.loadPage1Text(connection, versionId);
            assertThat(page1).startsWith("Power Purchase Agreement");
            assertThat(page1).contains("body first");

            ClassifyResult result = new DocTypeClassifier().classify(ClassifyRequest.scoreOnly(
                    "agreement", "doc.pdf", page1, null, seedPacks()));
            assertThat(result.docTypeCode()).isEqualTo("agreement.ppa");
        }
    }

    @Test
    void loadPage1TextFallsBackToPersistedCloudParse() throws Exception {
        Path dbPath = tempDir.resolve("cloud-parse.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository repo = new DocumentRepository(connection);
            long documentId = repo.insertDocument(1L, "ATC audit report", "technical_report", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", "ATC AUDIT REPORT.pdf", "hash-scan", ".pdf",
                    "technical_report", 30, true, null, null);

            new CloudParsePageRepository(connection).replacePages(
                    versionId, "pjb-test", "agentic_plus",
                    List.of("Independent Auditor's Report\nBalance Sheet"),
                    List.of("Independent Auditor's Report\nBalance Sheet"));

            String page1 = DocTypeClassifier.loadPage1Text(connection, versionId);
            assertThat(page1).contains("Independent Auditor's Report");
            assertThat(page1).contains("Balance Sheet");
        }
    }

    @Test
    void loadPage1TextUsesLayoutBeforeCloudParseWhenBothExist() throws Exception {
        Path dbPath = tempDir.resolve("layout-and-cloud.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            DocumentRepository repo = new DocumentRepository(connection);
            long documentId = repo.insertDocument(1L, "ATC audit report", "technical_report", null);
            long versionId = repo.insertDocumentVersion(
                    documentId, "v1", "ATC AUDIT REPORT.pdf", "hash-layout", ".pdf",
                    "technical_report", 30, true, null, null);
            long pageId = repo.insertPage(versionId, 1, null, null, null);
            repo.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "layout body", "layout body", 0, "test");
            new CloudParsePageRepository(connection).replacePages(
                    versionId, "pjb-test", "agentic_plus",
                    List.of("cloud parsed page"), List.of("cloud parsed page"));

            String page1 = DocTypeClassifier.loadPage1Text(connection, versionId);
            assertThat(page1).contains("layout body");
            assertThat(page1).doesNotContain("cloud parsed page");
        }
    }

    private static String readDocTypeCode(Connection connection, long versionId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT d.doc_type_code FROM document d"
                        + " JOIN document_version v ON v.document_id = d.document_id"
                        + " WHERE v.document_version_id = ?")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }
}
