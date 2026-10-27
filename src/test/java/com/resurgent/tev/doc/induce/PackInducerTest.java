package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.doc.pack.PackField;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pack induction + quality gates: bad packs reject, dry-run never publishes. */
class PackInducerTest {

    @TempDir
    Path tempDir;

    private static PackInducer.InduceDocument doc(String id, String fileRole, String title) {
        return new PackInducer.InduceDocument(
                id, fileRole, title,
                List.of(title, "Tariff: Rs. 4.50 per kWh"),
                Set.of("Bhagyanagar Solar Pvt Ltd"));
    }

    private static PackMiningClient.MinedField field(
            String key, String path, String pathRoot, String kind,
            boolean required, List<String> docIds, List<String> labels) {
        return field(key, path, pathRoot, kind, required, "hint for " + key, docIds, labels);
    }

    private static PackMiningClient.MinedField field(
            String key, String path, String pathRoot, String kind,
            boolean required, String hint, List<String> docIds, List<String> labels) {
        return new PackMiningClient.MinedField(
                key, path, pathRoot, kind, required,
                hint, List.of(), labels, docIds, List.of("sample"));
    }

    private PackMiningClient goodMining() {
        return new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "agreement.ppa",
                "agreement",
                List.of("Power Purchase Agreement"),
                List.of(
                        field("tariff", "commercial > Offtake > Tariff", "commercial", "money",
                                true, List.of("d1", "d2"), List.of("Tariff", "PPA rate")),
                        field("tenor", "commercial > Offtake > Tenor", "commercial", "duration",
                                true, List.of("d1", "d2"), List.of("Tenor", "Term")),
                        field("cod", "technical > COD", "technical", "date",
                                true, List.of("d1", "d2"), List.of("COD")))));
    }

    @Test
    void inducePublishesWhenAllGatesPass() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "agreement", "Power Purchase Agreement"),
                        doc("d2", "agreement", "Power Purchase Agreement")),
                "solar_power",
                "agreement",
                "agreement.ppa",
                "agreement",
                goodMining(),
                store,
                0.6,
                2,
                false);

        PackInducer.InduceResult result = new PackInducer().induce(request);

        assertThat(result.published()).isTrue();
        assertThat(result.allGatesPassed()).isTrue();
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.publishedPath()).contains("agreement.ppa@1.0.0.yaml");
        assertThat(result.gates()).allSatisfy(g -> assertThat(g.passed()).isTrue());
        assertThat(result.pack().fields()).extracting(PackField::key)
                .contains("tariff", "tenor", "cod");
        assertThat(result.proposedYaml()).contains("code: agreement.ppa");

        // published YAML is readable via PackStore
        assertThat(store.latest("solar_power", "agreement.ppa")).isPresent();
    }

    @Test
    void rejectsPackWithBareTotalInstruction() throws Exception {
        PackMiningClient vagueTotal = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "quotation.vendor",
                "quotation",
                List.of("Quotation"),
                List.of(
                        field("vendor_name", "identity > Legal Name", "identity", "text",
                                true, List.of("d1"), List.of("Vendor")),
                        field("gross_total", "economic > Project Cost > Quoted Gross", "economic",
                                "money", true, "Total", List.of("d1"), List.of("Total")),
                        field("validity_date", "commercial > Offtake term > Validity", "commercial",
                                "date", true, List.of("d1"), List.of("Valid until")))));
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "quotation", "Quotation")),
                "general",
                "quotation",
                "quotation.vendor",
                "quotation",
                vagueTotal,
                new PackStore(tempDir.resolve("packs-vague")),
                0.6,
                1,
                false);
        PackInducer.InduceResult result = new PackInducer().induce(request);
        assertThat(result.published()).isFalse();
        assertThat(result.gates()).anySatisfy(g -> {
            assertThat(g.name()).isEqualTo("row_disambiguation");
            assertThat(g.passed()).isFalse();
        });
    }

    @Test
    void dryRunDoesNotPublish() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "agreement", "Power Purchase Agreement"),
                        doc("d2", "agreement", "Power Purchase Agreement")),
                "solar_power",
                "agreement",
                "agreement.ppa",
                "agreement",
                goodMining(),
                store,
                0.6,
                2,
                true);

        PackInducer.InduceResult result = new PackInducer().induce(request);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.published()).isFalse();
        assertThat(result.publishedPath()).isNull();
        assertThat(result.proposedYaml()).contains("code: agreement.ppa");
        assertThat(store.entries()).isEmpty();
        assertThat(store.latest("solar_power", "agreement.ppa")).isEmpty();
    }

    @Test
    void gatesRejectZeroRequiredCoverage() throws Exception {
        // every field only "resolves" on no document → zero coverage
        PackMiningClient mining = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "agreement.ppa",
                "agreement",
                List.of("PPA"),
                List.of(
                        new PackMiningClient.MinedField(
                                "tariff", "commercial > Offtake > Tariff", "commercial", "money",
                                true, null, List.of(), List.of("Tariff"), List.of(), List.of()))));

        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "agreement", "Power Purchase Agreement"),
                        doc("d2", "agreement", "Power Purchase Agreement")),
                "solar_power", "agreement", "agreement.ppa", "agreement",
                mining, store, 0.6, 2, false);

        PackInducer.InduceResult result = new PackInducer().induce(request);

        assertThat(result.published()).isFalse();
        assertThat(result.allGatesPassed()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(r -> r.contains("coverage"));
        assertThat(store.entries()).isEmpty();
    }

    @Test
    void gatesRejectEntityTokensInFieldKeys() throws Exception {
        PackMiningClient mining = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "agreement.ppa",
                "agreement",
                List.of("PPA"),
                List.of(
                        field("bhagyanagar_solar_net_worth_of_concession",
                                "economic > Promoter Net Worth > Net Worth",
                                "economic",
                                "money",
                                true,
                                List.of("d1", "d2"),
                                List.of("Bhagyanagar Net Worth")),
                        field("tariff", "commercial > Offtake > Tariff", "commercial", "money",
                                true, List.of("d1", "d2"), List.of("Tariff")))));

        // force a dirty key through generalizer by supplying an entity-free mining key
        // that still embeds the sample company name after normalize
        PackMiningClient dirty = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "agreement.ppa",
                "agreement",
                List.of("PPA"),
                List.of(
                        new PackMiningClient.MinedField(
                                "bhagyanagar",
                                "economic > Promoter Net Worth > Net Worth",
                                "economic",
                                "money",
                                true,
                                null,
                                List.of(),
                                List.of("Bhagyanagar"),
                                List.of("d1", "d2"),
                                List.of("1 Cr")),
                        field("tariff", "commercial > Offtake > Tariff", "commercial", "money",
                                true, List.of("d1", "d2"), List.of("Tariff")))));

        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "agreement", "Power Purchase Agreement"),
                        doc("d2", "agreement", "Power Purchase Agreement")),
                "solar_power", "agreement", "agreement.ppa", "agreement",
                dirty, store, 0.6, 2, true);

        PackInducer.InduceResult result = new PackInducer().induce(request);

        // entity-only key is dropped by Generalizer; remaining pack must not contain it
        assertThat(result.droppedFields())
                .anyMatch(d -> d.toLowerCase().contains("bhagyanagar"));
        if (result.pack() != null) {
            assertThat(result.pack().fields()).extracting(PackField::key)
                    .noneMatch(k -> k.toLowerCase().contains("bhagyanagar"));
        }
    }

    @Test
    void emptyCorpusIsRejected() {
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(),
                "solar_power",
                null,
                null,
                null,
                goodMining(),
                new PackStore(tempDir.resolve("packs")),
                0.6,
                2,
                true);

        assertThatThrownBy(() -> new PackInducer().induce(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clustersDocumentsByTypeSignals() {
        List<PackInducer.InduceDocument> docs = List.of(
                new PackInducer.InduceDocument("a1", "quotation", "Vendor Quotation", List.of(), Set.of()),
                new PackInducer.InduceDocument("a2", "quotation", "Vendor Quotation", List.of(), Set.of()),
                new PackInducer.InduceDocument("b1", "agreement", "PPA", List.of(), Set.of()));

        List<List<PackInducer.InduceDocument>> clusters = PackInducer.clusterByTypeSignals(docs);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0)).extracting(PackInducer.InduceDocument::id)
                .containsExactly("a1", "a2");
        assertThat(clusters.get(1)).extracting(PackInducer.InduceDocument::id)
                .containsExactly("b1");
    }

    @Test
    void induceRejectsTableColumnMissingValueKind() throws Exception {
        PackMiningClient mining = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "quotation.vendor_supply",
                "quotation",
                List.of("Quotation"),
                List.of(
                        field("vendor_name", "identity > Legal Name", "identity", "text",
                                true, List.of("d1"), List.of("Vendor")),
                        field("gross_total", "economic > Project Cost > Quoted Gross", "economic",
                                "money", true, "Final grand total row including tax",
                                List.of("d1"), List.of("Grand Total"))),
                List.of(new PackMiningClient.MinedTable(
                        "line_items",
                        "economic > Project Cost > Line Item",
                        "economic",
                        "description",
                        null,
                        null,
                        null,
                        List.of(
                                new PackMiningClient.MinedColumn("description", "text"),
                                new PackMiningClient.MinedColumn("amount", null)),
                        List.of("d1"),
                        true)),
                List.of(),
                List.of(),
                null,
                null,
                null));

        PackInducer.InduceResult result = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(doc("d1", "quotation", "Quotation")),
                "general",
                "quotation",
                "quotation.vendor_supply",
                "quotation",
                mining,
                new PackStore(tempDir.resolve("packs-missing-col-kind")),
                0.6,
                1,
                false));

        assertThat(result.published()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.amount") && reason.contains("value_kind"));
    }

    @Test
    void induceRejectsFieldMissingValueKind() throws Exception {
        PackMiningClient mining = new OfflinePackMiningClient(new PackMiningClient.MiningResult(
                "quotation.vendor_supply",
                "quotation",
                List.of("Quotation"),
                List.of(
                        field("vendor_name", "identity > Legal Name", "identity", "",
                                true, List.of("d1"), List.of("Vendor")),
                        field("gross_total", "economic > Project Cost > Quoted Gross", "economic",
                                "money", true, "Final grand total row including tax",
                                List.of("d1"), List.of("Grand Total")))));

        PackInducer.InduceResult result = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(doc("d1", "quotation", "Quotation")),
                "general",
                "quotation",
                "quotation.vendor_supply",
                "quotation",
                mining,
                new PackStore(tempDir.resolve("packs-missing-field-kind")),
                0.6,
                1,
                false));

        assertThat(result.published()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(reason ->
                reason.contains("vendor_name") && reason.contains("value_kind"));
    }

    @Test
    void publishWithoutStoreRequiresDryRun() {
        PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                List.of(doc("d1", "agreement", "Power Purchase Agreement"),
                        doc("d2", "agreement", "Power Purchase Agreement")),
                "solar_power", "agreement", "agreement.ppa", "agreement",
                goodMining(), null, 0.6, 2, false);

        assertThatThrownBy(() -> new PackInducer().induce(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PackStore");
    }
}
