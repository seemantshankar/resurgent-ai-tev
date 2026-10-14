package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.induce.Generalizer;
import com.resurgent.tev.doc.induce.PackInducer;
import com.resurgent.tev.doc.induce.PackMiningClient;
import com.resurgent.tev.doc.induce.PackQualityGates;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T13: induce on a non-solar cluster (hotel hospitality quotation wording),
 * then extract + bind with the published pack — no analyst pack authoring.
 */
class InducedPackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void hotelQuotationInducesThenExtractsBinds() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(dbPath);
        Path pdf = Path.of("src/test/resources/docfixtures/golden_hotel_quotation.pdf");
        if (!pdf.toFile().exists()) {
            pdf = Path.of("docfixtures/golden_hotel_quotation.pdf");
        }

        long versionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            // schema applied
        }
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "Hotel Amenities Quote", "v1", "quotation", "quotation"),
                dbPath, blobs);
        versionId = ingested.documentVersionId();
        new DocumentLayoutService().layout(versionId, dbPath, blobs);

        // --- induce from non-solar wording (fixture mining keeps labels, not company values)
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackMiningClient mining = new PackMiningClient() {
            @Override
            public String id() {
                return "fixture-mining";
            }

            @Override
            public MiningResult mine(MiningRequest request) {
                return new MiningResult(
                        "quotation.hospitality_supply",
                        "quotation",
                        List.of("Hospitality Supply Quotation", "Quotation", "GST"),
                        List.of(
                                new MinedField("vendor_name", "identity > Legal Name", "identity",
                                        "text", true, null,
                                        List.of("From"),
                                        List.of("Vendor Name", "From"),
                                        List.of("d1", "d2"),
                                        List.of("Hotel Amenities India Ltd")),
                                new MinedField("gross_total", "economic > Project Cost > Quoted Gross",
                                        "economic", "money", true, null,
                                        List.of("Grand Total"),
                                        List.of("Grand Total", "Total"),
                                        List.of("d1", "d2"),
                                        List.of("Rs. 16,17,350")),
                                new MinedField("tax", "economic > Project Cost > Tax",
                                        "economic", "money", false, null,
                                        List.of("GST"),
                                        List.of("GST"),
                                        List.of("d1", "d2"),
                                        List.of("Rs. 2,39,850"))));
            }
        };

        PackInducer.InduceResult induced = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(
                        new PackInducer.InduceDocument("d1", "quotation",
                                "Hospitality Supply Quotation",
                                List.of("Hospitality Supply Quotation", "Grand Total"),
                                Set.of("Hotel Amenities India Ltd")),
                        new PackInducer.InduceDocument("d2", "quotation",
                                "Hospitality Supply Quotation",
                                List.of("Hospitality Supply Quotation", "Grand Total"),
                                Set.of("Hotel Amenities India Ltd"))),
                "hospitality",
                "quotation",
                "quotation.hospitality_supply",
                "quotation",
                mining,
                store,
                0.6,
                2,
                false));

        assertThat(induced.allGatesPassed()).isTrue();
        assertThat(induced.published()).isTrue();
        assertThat(induced.pack().fields()).extracting(PackField::key)
                .contains("vendor_name", "gross_total");
        // company/PAN-like tokens never become field keys
        assertThat(induced.pack().fields()).extracting(PackField::key)
                .noneMatch(k -> k.toLowerCase().contains("hotel")
                        || k.toLowerCase().contains("amenities"));

        // --- extract + bind with the published pack
        DocTypePack pack = induced.pack();
        FixtureExtractClient client = new FixtureExtractClient();
        client.loadSidecar(Path.of(
                "src/test/resources/docfixtures/golden_hotel_quotation.expected.json"));
        ExtractService.ExtractOutcome extract = new ExtractService().extract(
                dbPath, versionId, 1L, pack, client, "fixture-extract");
        assertThat(extract.factsTotal()).isPositive();

        new PackPathBinder().bind(dbPath, extract.parseRunId(), pack);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            List<FactRow> rows = new com.resurgent.tev.doc.db.FactRepository(db.connection())
                    .query(FactQuery.forMandate(1L).withVersion(versionId));
            assertThat(rows).isNotEmpty();
            FactRow gross = rows.stream()
                    .filter(r -> "gross_total".equals(r.fieldKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(gross.ontologyPath()).isNotBlank();
            assertThat(gross.evidence()).isNotEmpty();
        }
    }
}
