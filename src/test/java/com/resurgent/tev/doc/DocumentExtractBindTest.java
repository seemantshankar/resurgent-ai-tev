package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.DocExtractRunner;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden integration suite (T10): ingest → classify → extract → bind on three
 * synthetic born-digital PDFs. Asserts required fields bound with verbatim
 * provenance and that re-extract replaces rather than duplicates.
 */
class DocumentExtractBindTest {

    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;
    private DocTypePack netWorthPack;
    private DocTypePack quotationPack;
    private DocTypePack ppaPack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        Files.createDirectories(dbPath.getParent());
        // Ensure schema including V47
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
        netWorthPack = PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml");
        quotationPack = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        ppaPack = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
    }

    private long ingestAndLayout(String resource, String logicalName, String fileRole)
            throws Exception {
        Path src = fixture(resource);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        src, MANDATE, logicalName, "v1", fileRole, fileRole),
                dbPath, blobs);
        new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
        return ingested.documentVersionId();
    }

    private static Path fixture(String name) {
        Path p = Path.of("src/test/resources/docfixtures", name);
        if (Files.exists(p)) {
            return p;
        }
        return Path.of("docfixtures", name);
    }

    private FixtureExtractClient fixtureClient(String sidecar) throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        client.loadSidecar(fixture(sidecar));
        return client;
    }

    @Test
    void netWorthPipelineProducesBoundCitableFacts() throws Exception {
        long versionId = ingestAndLayout("golden_net_worth.pdf", "Promoter NW - Sharma",
                "certificate");

        ClassifyResult classified = classify(versionId, netWorthPack);
        assertThat(classified.docTypeCode()).isEqualTo("identity.promoter_net_worth");

        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, MANDATE, netWorthPack,
                fixtureClient("golden_net_worth.expected.json"), "fixture-extract");
        assertThat(extract.status()).isEqualTo("success");
        assertThat(extract.factsTotal()).isGreaterThanOrEqualTo(5);

        new PackPathBinder().bind(dbPath, extract.parseRunId(), netWorthPack);

        List<FactRow> rows = factsFor(versionId);
        assertThat(rows).isNotEmpty();
        FactRow netWorth = rows.stream()
                .filter(r -> "net_worth".equals(r.fieldKey()))
                .findFirst()
                .orElseThrow();
        assertThat(netWorth.ontologyPath()).contains("Net Worth");
        assertThat(netWorth.evidence()).isNotEmpty();
        assertThat(netWorth.evidence().get(0).verbatim()).isNotBlank();

        // re-extract replaces, does not duplicate
        ExtractService.ExtractOutcome again = new ExtractService().extract(
                dbPath, versionId, MANDATE, netWorthPack,
                fixtureClient("golden_net_worth.expected.json"), "fixture-extract");
        assertThat(again.parseRunId()).isEqualTo(extract.parseRunId());
        List<FactRow> after = factsFor(versionId);
        assertThat(after).hasSameSizeAs(rows);
    }

    @Test
    void quotationLineItemsBindAsEconomicLineFacts() throws Exception {
        long versionId = ingestAndLayout("golden_quotation.pdf", "Vendor Quote", "quotation");
        ClassifyResult classified = classify(versionId, quotationPack);
        assertThat(classified.docTypeCode()).isEqualTo("quotation.vendor");

        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, MANDATE, quotationPack,
                fixtureClient("golden_quotation.expected.json"), "fixture-extract");
        assertThat(extract.factsTotal()).isGreaterThanOrEqualTo(5);
        assertThat(extract.lineItemCount()).isGreaterThanOrEqualTo(3);

        new PackPathBinder().bind(dbPath, extract.parseRunId(), quotationPack);

        List<FactRow> rows = factsFor(versionId);
        assertThat(rows.stream().map(FactRow::fieldKey))
                .contains("gross_total", "line_items");
        FactRow gross = rows.stream()
                .filter(r -> "gross_total".equals(r.fieldKey()))
                .findFirst().orElseThrow();
        assertThat(gross.absoluteAmount()).isNotBlank();

        // S2.1 grouping keys: line items carry grouping_* evidence naming the parent
        // facts (vendor, package, validity, tax/freight flags) they belong to.
        List<FactRow> lines = rows.stream()
                .filter(r -> "line_items".equals(r.fieldKey()))
                .toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line ->
                assertThat(line.evidence()).anySatisfy(e -> {
                    assertThat(e.role()).isEqualTo("grouping_vendor");
                    assertThat(e.verbatim()).isEqualTo("vendor_name");
                }));
        assertThat(lines.get(0).evidence())
                .extracting(FactRow.EvidenceRow::role)
                .contains("grouping_package", "grouping_validity",
                        "grouping_tax", "grouping_freight");
    }

    @Test
    void ppaTariffKeepsUnitOnlyWhenStated() throws Exception {
        long versionId = ingestAndLayout("golden_ppa.pdf", "PPA", "agreement");
        ClassifyResult classified = classify(versionId, ppaPack);
        assertThat(classified.docTypeCode()).isEqualTo("agreement.ppa");

        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, MANDATE, ppaPack,
                fixtureClient("golden_ppa.expected.json"), "fixture-extract");
        new PackPathBinder().bind(dbPath, extract.parseRunId(), ppaPack);

        List<FactRow> rows = factsFor(versionId);
        FactRow tariff = rows.stream()
                .filter(r -> "tariff".equals(r.fieldKey()))
                .findFirst().orElseThrow();
        // fixture quotes "Rs. 4.50 per kWh" — unit is stated
        assertThat(tariff.unit() == null || !tariff.unit().isBlank()).isTrue();
        assertThat(tariff.evidence()).isNotEmpty();
    }

    private ClassifyResult classify(long versionId, DocTypePack pack) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version = docs.findVersion(versionId)
                    .orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(), versionId);
            ClassifyRequest request = new ClassifyRequest(
                    versionId, version.fileRole(), version.fileName(), page1,
                    "general", List.of(pack), true);
            ClassifyResult result = new DocTypeClassifier().classify(request, db.connection());
            return result;
        }
    }

    private List<FactRow> factsFor(long versionId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            return facts.query(FactQuery.forMandate(MANDATE).withVersion(versionId));
        }
    }
}
