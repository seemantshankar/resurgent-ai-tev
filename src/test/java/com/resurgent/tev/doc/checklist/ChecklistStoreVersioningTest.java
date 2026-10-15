package com.resurgent.tev.doc.checklist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3-15 (#148) store tests: a learned checklist change is a NEW version
 * ({@code {code}@{version}.yaml}) that never overwrites history, the catalog
 * picks the highest semver per code, and an item's own note (why it changed)
 * round-trips through render/load into {@code checklist_item.note}.
 */
class ChecklistStoreVersioningTest {

    @TempDir
    Path tempDir;

    @Test
    void publishVersionedLandsBesideTheDraftAndCatalogPicksTheNewest() throws Exception {
        ChecklistStore store = new ChecklistStore(tempDir.resolve(ChecklistStore.DIRECTORY));
        ChecklistDefinition draft = industryDefinition("1.0.0", List.of(
                item("solar_ppa", "agreement.ppa", true, null),
                item("pvsyst_report", "report.pvsyst", true, null)));
        store.publish(draft);

        ChecklistDefinition learned = industryDefinition("1.1.0", List.of(
                item("solar_ppa", "agreement.ppa", true, null),
                item("pvsyst_report", "report.pvsyst", false,
                        "optional: not provided in 2 of the 3 solar mandates read"),
                observedItem("approval_evacuation", "approval.evacuation")));
        Path versioned = store.publishVersioned(learned);

        assertThat(versioned.getFileName().toString()).isEqualTo("in.solar_power@1.1.0.yaml");
        assertThat(Files.exists(tempDir.resolve(ChecklistStore.DIRECTORY)
                .resolve("in.solar_power.yaml"))).isTrue();

        Path dbPath = tempDir.resolve("m1.db");
        var catalog = ChecklistStore.loadCatalog(dbPath);
        ChecklistDefinition effective = catalog.get("in.solar_power");
        assertThat(effective.version()).isEqualTo("1.1.0");
        assertThat(effective.items()).extracting(ChecklistItemDef::key)
                .containsExactlyInAnyOrder("solar_ppa", "pvsyst_report", "approval_evacuation");
        assertThat(effective.items()).filteredOn(i -> i.key().equals("pvsyst_report"))
                .singleElement().satisfies(i -> {
                    assertThat(i.mandatory()).isFalse();
                    assertThat(i.note())
                            .isEqualTo("optional: not provided in 2 of the 3 solar mandates read");
                });
        assertThat(catalog).containsKey("in.spine");
    }

    @Test
    void publishVersionedNeverOverwritesAnExistingVersion() throws Exception {
        ChecklistStore store = new ChecklistStore(tempDir.resolve(ChecklistStore.DIRECTORY));
        ChecklistDefinition first = industryDefinition("1.1.0", List.of(
                item("solar_ppa", "agreement.ppa", true, null)));
        Path file = store.publishVersioned(first);
        String content = Files.readString(file);

        ChecklistDefinition different = industryDefinition("1.1.0", List.of(
                item("other_item", "report.pvsyst", true, null)));
        Path again = store.publishVersioned(different);

        assertThat(again).isEqualTo(file);
        assertThat(Files.readString(file)).isEqualTo(content);
    }

    @Test
    void itemNoteRoundTripsThroughRenderAndLoad() throws Exception {
        ChecklistStore store = new ChecklistStore(tempDir.resolve(ChecklistStore.DIRECTORY));
        ChecklistDefinition learned = industryDefinition("1.1.0", List.of(
                item("pvsyst_report", "report.pvsyst", false,
                        "optional: not provided in any of the 2 solar mandates read")));
        Path file = store.publishVersioned(learned);

        ChecklistDefinition loaded = ChecklistLoader.load(file);
        assertThat(loaded.items()).singleElement().satisfies(i ->
                assertThat(i.note())
                        .isEqualTo("optional: not provided in any of the 2 solar mandates read"));

        ChecklistDefinition noNotes = industryDefinition("1.2.0", List.of(
                item("pvsyst_report", "report.pvsyst", true, null)));
        assertThat(ChecklistStore.render(noNotes)).doesNotContain("    note:");
    }

    @Test
    void loaderReadsAnItemNoteFromYaml() throws Exception {
        String yaml = """
                code: test.solar_power
                version: 1.1.0
                scope: industry
                country: IN
                industries:
                  - solar_power
                items:
                  - key: pvsyst_report
                    label: PVsyst report
                    level: document
                    granularity: mandate
                    document_type: report.pvsyst
                    mandatory: false
                    basis:
                      kind: observed
                      text: seen in the client document sets of 2 of 3 mandates
                    origin: drafted
                    request: "Please share the {item}."
                    note: "optional: missing across the mandates read"
                """;
        ChecklistDefinition loaded = ChecklistLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test yaml");

        assertThat(loaded.items()).singleElement().satisfies(i -> {
            assertThat(i.mandatory()).isFalse();
            assertThat(i.note()).isEqualTo("optional: missing across the mandates read");
        });
    }

    private static ChecklistDefinition industryDefinition(String version,
            List<ChecklistItemDef> items) {
        return new ChecklistDefinition("in.solar_power", version, "industry", "IN",
                List.of("solar_power"), items, null);
    }

    private static ChecklistItemDef item(String key, String documentType, boolean mandatory,
            String note) {
        return new ChecklistItemDef(key, "Test " + key, "document", "mandate", null, documentType,
                null, null, mandatory, "practice", "bank practice", "drafted",
                "Please share the {item}.", note);
    }

    private static ChecklistItemDef observedItem(String key, String documentType) {
        return new ChecklistItemDef(key, "Test " + key, "document", "mandate", null, documentType,
                null, null, true, "observed",
                "observed in the client document sets of mandates 1, 2 and 3", "observed",
                "Please share the {item}.", null);
    }
}
