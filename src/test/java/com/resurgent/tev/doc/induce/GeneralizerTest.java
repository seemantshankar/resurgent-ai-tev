package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.pack.PackField;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** S2.11 generalization filters: label-not-value, consensus, spine-first, nomenclature. */
class GeneralizerTest {

    private final Generalizer generalizer = new Generalizer();

    private static PackMiningClient.MinedField field(
            String key, String path, String pathRoot, String valueKind,
            boolean required, List<String> docIds, List<String> labels, List<String> sampleValues) {
        return new PackMiningClient.MinedField(
                key, path, pathRoot, valueKind, required, null, List.of(), labels, docIds, sampleValues);
    }

    @Test
    void stripsCompanyAndPanTokensFromKeysAndPaths() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "certificate.net_worth",
                "certificate",
                List.of("Net Worth Certificate"),
                List.of(
                        field("Bhagyanagar_Solar_Net_Worth",
                                "Bhagyanagar Solar > Net Worth",
                                "economic",
                                "money",
                                true,
                                List.of("d1", "d2"),
                                List.of("Net Worth", "Net Worth of Bhagyanagar Solar Pvt Ltd"),
                                List.of("₹ 1,23,45,678")),
                        field("ABCDE1234F",
                                "ABCDE1234F",
                                "identity",
                                "text",
                                true,
                                List.of("d1", "d2"),
                                List.of(),
                                List.of("ABCDE1234F")),
                        field("pan_of_subject",
                                "PAN of Bhagyanagar Solar",
                                "identity",
                                "text",
                                false,
                                List.of("d1"),
                                List.of("PAN"),
                                List.of("ABCDE1234F"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 2, Generalizer.Config.defaults(Set.of("Bhagyanagar Solar")));

        List<String> keys = result.packFields().stream().map(PackField::key).toList();
        assertThat(keys).contains("net_worth");
        assertThat(keys).doesNotContain("Bhagyanagar_Solar_Net_Worth", "ABCDE1234F");
        assertThat(result.rewritten()).anyMatch(r -> r.contains("Bhagyanagar_Solar_Net_Worth"));
        assertThat(result.dropped()).anyMatch(d -> d.contains("ABCDE1234F"));

        PackField netWorth = result.packFields().stream()
                .filter(f -> f.key().equals("net_worth"))
                .findFirst()
                .orElseThrow();
        assertThat(netWorth.path()).isEqualTo("economic > Promoter Net Worth > Net Worth");
        assertThat(netWorth.pathRoot()).isEqualTo("economic");
        assertThat(netWorth.valueKind().wireName()).isEqualTo("money");
        assertThat(netWorth.key()).doesNotContainIgnoringCase("bhagyanagar");
        assertThat(netWorth.key()).doesNotMatch("[A-Z]{5}[0-9]{4}[A-Z]");
    }

    @Test
    void crossDocumentConsensusDropsOneOffCompanyFacts() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "agreement.ppa",
                "agreement",
                List.of("Power Purchase Agreement"),
                List.of(
                        // appears in both docs → kept, required
                        field("tariff", "Commercial > Offtake > Tariff", "commercial", "money",
                                true, List.of("d1", "d2"), List.of("Tariff"), List.of("4.50")),
                        // one-off company fact → never enters the pack
                        field("bhagyanagar_specific_concession",
                                "Concession for Bhagyanagar",
                                "regulatory",
                                "text",
                                true,
                                List.of("d1"),
                                List.of("Special concession"),
                                List.of("XYZ"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 2, Generalizer.Config.defaults(Set.of("Bhagyanagar")));

        assertThat(result.packFields()).extracting(PackField::key)
                .containsExactly("tariff");
        assertThat(result.dropped())
                .anyMatch(d -> d.contains("bhagyanagar_specific_concession") || d.contains("one-off"));
        assertThat(result.packFields().stream().filter(PackField::required)).hasSize(1);
    }

    @Test
    void consensusRequiresAtLeastNAndSingleDocMapsSpineLabels() {
        // multi-doc: field in 1 of 2 docs is dropped (need ≥ 2)
        PackMiningClient.MiningResult multi = new PackMiningClient.MiningResult(
                "quotation.vendor",
                "quotation",
                List.of("Quotation"),
                List.of(
                        field("gross_total", "economic > Project Cost > Quoted Gross", "economic",
                                "money", true, List.of("d1", "d2"), List.of("Grand Total"), List.of("1,00,000")),
                        field("payment_terms", "commercial > Offtake term > Payment", "commercial",
                                "text", false, List.of("d1"), List.of("Payment Terms"), List.of("30 days"))));

        Generalizer.Result multiResult = generalizer.generalize(
                multi, 2, new Generalizer.Config(2, Set.of(), "solar_power"));
        assertThat(multiResult.packFields()).extracting(PackField::key)
                .containsExactly("gross_total");
        assertThat(multiResult.dropped()).anyMatch(d -> d.contains("payment_terms"));

        // single-doc cluster: spine labels stay required; an evidenced non-spine
        // leaf under a known root is kept and demoted to optional
        PackMiningClient.MiningResult single = new PackMiningClient.MiningResult(
                "identity.promoter_net_worth",
                "certificate",
                List.of("Net Worth Certificate"),
                List.of(
                        field("net_worth", "economic > Promoter Net Worth > Net Worth", "economic",
                                "money", true, List.of("d1"), List.of("Net Worth"), List.of("5 Cr")),
                        field("favourite_colour", "frame > Misc > Colour", "frame",
                                "text", true, List.of("d1"), List.of("Favourite Colour"), List.of("blue"))));

        Generalizer.Result singleResult = generalizer.generalize(
                single, 1, Generalizer.Config.defaults(Set.of()));
        assertThat(singleResult.packFields()).extracting(PackField::key)
                .containsExactly("net_worth", "favourite_colour");
        assertThat(singleResult.packFields().get(0).required()).isTrue();
        assertThat(singleResult.packFields().get(1).required()).isFalse();
        assertThat(singleResult.decisions()).anySatisfy(note -> {
            assertThat(note.candidateKey()).isEqualTo("favourite_colour");
            assertThat(note.outcome()).isEqualTo("demoted_optional");
        });
    }

    @Test
    void spineFirstMapsKnownLeavesAndAllowsIndustryLeavesUnderKnownRoots() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "hotel.folio",
                "folio",
                List.of("Hotel Folio"),
                List.of(
                        field("cod", "Commercial Operation Date", "technical", "date",
                                true, List.of("d1", "d2"), List.of("COD"), List.of("2027-01-01")),
                        field("project_cost", "Total Project Cost", "economic", "money",
                                true, List.of("d1", "d2"), List.of("Project Cost"), List.of("10 Cr")),
                        field("elevator_cost", "Elevator / Lift", "economic", "money",
                                true, List.of("d1", "d2"), List.of("Elevator"), List.of("50 L")),
                        field("mystery", "Nowhere > Odd", "unknown_root", "text",
                                true, List.of("d1", "d2"), List.of("Mystery"), List.of("x"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 2, Generalizer.Config.defaults(Set.of()));

        assertThat(result.packFields()).extracting(PackField::key)
                .contains("cod", "project_cost", "elevator_cost")
                .doesNotContain("mystery");

        PackField cod = result.packFields().stream()
                .filter(f -> f.key().equals("cod")).findFirst().orElseThrow();
        assertThat(cod.path()).isEqualTo("technical > COD");
        assertThat(cod.pathRoot()).isEqualTo("technical");

        PackField cost = result.packFields().stream()
                .filter(f -> f.key().equals("project_cost")).findFirst().orElseThrow();
        assertThat(cost.path()).isEqualTo("economic > Project Cost");

        PackField elevator = result.packFields().stream()
                .filter(f -> f.key().equals("elevator_cost")).findFirst().orElseThrow();
        assertThat(elevator.pathRoot()).isEqualTo("economic");
        assertThat(elevator.path()).startsWith("economic >");
        assertThat(result.notes()).anyMatch(n -> n.contains("industry leaf"));
    }

    @Test
    void nomenclaturePopulatesIdentitySignalsAndSynonyms() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "identity.promoter_net_worth",
                "certificate",
                List.of("Net Worth Certificate", "Certificate of Net Worth"),
                List.of(
                        new PackMiningClient.MinedField(
                                "net_worth",
                                "economic > Promoter Net Worth > Net Worth",
                                "economic",
                                "money",
                                true,
                                "Net worth of the subject",
                                List.of("NW"),
                                List.of("Net Worth", "NW as on", "परिसंपत्ति"),
                                List.of("d1", "d2"),
                                List.of("₹ 1 Cr"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 2, Generalizer.Config.defaults(Set.of("Bhagyanagar Solar")));

        assertThat(result.identitySignals())
                .contains("Net Worth Certificate", "Certificate of Net Worth");
        assertThat(result.identitySignals())
                .noneMatch(s -> s.toLowerCase().contains("bhagyanagar"));

        PackField nw = result.packFields().stream()
                .filter(f -> f.key().equals("net_worth")).findFirst().orElseThrow();
        assertThat(nw.synonyms()).contains("NW as on", "परिसंपत्ति", "NW");
        assertThat(nw.promptHint()).isEqualTo("Net worth of the subject");
    }

    @Test
    void optionalLlmFieldsStayOptionalWhenConsensusMet() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "identity.promoter_net_worth",
                "certificate",
                List.of("Net Worth"),
                List.of(
                        new PackMiningClient.MinedField(
                                "subject_name",
                                "identity > Legal Name",
                                "identity",
                                "text",
                                true,
                                null,
                                List.of(),
                                List.of("Name of the Member"),
                                List.of("d1", "d2"),
                                List.of("Mr. Ramesh Sharma")),
                        new PackMiningClient.MinedField(
                                "issuer_membership_no",
                                "frame > Signature Block > Membership",
                                "frame",
                                "text",
                                false,
                                null,
                                List.of(),
                                List.of("M. No"),
                                List.of("d1", "d2"),
                                List.of("123456"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 2, Generalizer.Config.defaults(Set.of("Ramesh Sharma")));

        assertThat(result.packFields()).extracting(PackField::key)
                .contains("subject_name", "issuer_membership_no");
        assertThat(result.packFields().stream()
                .filter(f -> f.key().equals("subject_name")).findFirst().orElseThrow()
                .required()).isTrue();
        assertThat(result.packFields().stream()
                .filter(f -> f.key().equals("issuer_membership_no")).findFirst().orElseThrow()
                .required()).isFalse();
    }
}
