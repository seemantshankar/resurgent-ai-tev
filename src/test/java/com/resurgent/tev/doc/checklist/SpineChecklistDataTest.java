package com.resurgent.tev.doc.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Golden M3-10 data suite (issue #143): the checked-in India spine checklist is
 * versioned seed data — every item must carry a named regulation or bank practice
 * basis, and a draft item without one is rejected at load time.
 */
class SpineChecklistDataTest {

    @Test
    void seedSpineEveryItemCarriesARegulationOrPracticeBasis() throws Exception {
        ChecklistDefinition spine = ChecklistLoader.loadSeedSpine();

        assertThat(spine.code()).isEqualTo("in.spine");
        assertThat(spine.scope()).isEqualTo("spine");
        assertThat(spine.country()).isEqualTo("IN");
        assertThat(spine.version()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(spine.items()).isNotEmpty();

        assertThat(spine.items()).allSatisfy(item -> {
            assertThat(item.key()).matches("[a-z0-9_]+");
            assertThat(item.label()).isNotBlank();
            assertThat(item.basisKind()).isIn("regulation", "practice", "observed");
            assertThat(item.basisText()).isNotBlank();
            assertThat(item.clientRequest()).isNotBlank();
            assertThat(item.level()).isIn("document", "data_point");
            assertThat(item.granularity()).isIn("mandate", "entity", "site");
        });

        // The spine content #143 names: borrower identity and registration documents,
        // promoter net worth, financials, loan request.
        assertThat(spine.items()).extracting(ChecklistItemDef::key)
                .contains("certificate_of_incorporation", "moa_aoa", "pan_card", "gst_certificate",
                        "promoter_net_worth_certificate", "promoter_net_worth_value",
                        "audited_financials", "loan_request");

        // Both levels are present, and the net-worth certificate is expected
        // once per promoter entity.
        assertThat(spine.items()).anySatisfy(item ->
                assertThat(item.level()).isEqualTo("document"));
        assertThat(spine.items()).anySatisfy(item ->
                assertThat(item.level()).isEqualTo("data_point"));
        assertThat(spine.items()).anySatisfy(item -> {
            assertThat(item.granularity()).isEqualTo("entity");
            assertThat(item.entityRole()).isEqualTo("promoter");
        });
    }

    @Test
    void draftItemWithoutBasisIsRejected() {
        String yaml = """
                code: test.spine
                version: 0.1.0
                scope: spine
                country: IN
                items:
                  - key: invented_requirement
                    label: Invented requirement
                    level: document
                    granularity: mandate
                    document_type: registration.pan
                    mandatory: true
                    basis:
                      kind: ""
                      text: ""
                    request: "Please share it."
                """;
        assertThatThrownBy(() -> ChecklistLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test yaml"))
                .isInstanceOf(ChecklistLoader.ChecklistValidationException.class)
                .hasMessageContaining("invented_requirement")
                .hasMessageContaining("basis");
    }

    @Test
    void draftItemWithoutBasisSectionIsRejected() {
        String yaml = """
                code: test.spine
                version: 0.1.0
                scope: spine
                country: IN
                items:
                  - key: no_basis_item
                    label: No basis item
                    level: document
                    granularity: mandate
                    document_type: registration.pan
                    mandatory: true
                    request: "Please share it."
                """;
        assertThatThrownBy(() -> ChecklistLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test yaml"))
                .isInstanceOf(ChecklistLoader.ChecklistValidationException.class)
                .hasMessageContaining("no_basis_item")
                .hasMessageContaining("basis");
    }
}
