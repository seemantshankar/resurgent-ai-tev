package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Versioned pack store: semver bumps on re-induction, old packs stay readable. */
class PackStoreTest {

    @TempDir
    Path tempDir;

    private DocTypePack pack(String version, String... requiredKeys) {
        List<PackField> fields = new java.util.ArrayList<>();
        fields.add(PackField.required("subject_name",
                "identity > Legal Name", "identity", ValueKind.TEXT));
        for (String key : requiredKeys) {
            fields.add(PackField.required(key,
                    "economic > Promoter Net Worth > Net Worth", "economic", ValueKind.MONEY));
        }
        fields.add(PackField.optional("issuer_name",
                "frame > Signature Block > Name", "frame", ValueKind.TEXT));
        return new DocTypePack(
                "identity.promoter_net_worth",
                version,
                "certificate",
                "certificate",
                List.of("general"),
                List.of("Net Worth"),
                fields,
                List.of(),
                List.of(),
                List.of("financial_viability"));
    }

    private PackStore.PublishRequest request(DocTypePack pack) {
        return new PackStore.PublishRequest(pack, "general", null, List.of("m1"), "run-1");
    }

    @Test
    void firstPublishIsOneZeroZero() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackStore.PublishedPack published = store.publish(request(pack("0.0.0", "net_worth")));

        assertThat(published.version()).isEqualTo("1.0.0");
        assertThat(published.yamlPath().getFileName().toString())
                .isEqualTo("identity.promoter_net_worth@1.0.0.yaml");
        assertThat(published.yamlPath()).exists();
        assertThat(store.registryPath()).exists();

        Optional<DocTypePack> loaded = store.latest("general", "identity.promoter_net_worth");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo("1.0.0");
        assertThat(loaded.get().fields()).extracting(PackField::key)
                .contains("subject_name", "net_worth", "issuer_name");
    }

    @Test
    void reinductionWithSameRequiredFieldsBumpsMinor() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        store.publish(request(pack("0.0.0", "net_worth")));

        // same required keys, different optional prompt/synonyms → minor
        DocTypePack v2 = pack("1.0.0", "net_worth");
        PackStore.PublishedPack again = store.publish(request(v2));

        assertThat(again.version()).isEqualTo("1.1.0");
        assertThat(store.entries()).hasSize(2);

        // old pack stays readable
        Optional<DocTypePack> v1 = store.load("identity.promoter_net_worth", "1.0.0");
        assertThat(v1).isPresent();
        assertThat(v1.get().version()).isEqualTo("1.0.0");
        Optional<DocTypePack> latest = store.latest("general", "identity.promoter_net_worth");
        assertThat(latest.get().version()).isEqualTo("1.1.0");
    }

    @Test
    void reinductionWithChangedRequiredFieldsBumpsMajor() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        store.publish(request(pack("0.0.0", "net_worth")));

        // required set changes (adds assets_total) → major
        DocTypePack v2 = pack("1.0.0", "net_worth", "assets_total");
        PackStore.PublishedPack again = store.publish(request(v2));

        assertThat(again.version()).isEqualTo("2.0.0");
        assertThat(store.load("identity.promoter_net_worth", "1.0.0")).isPresent();
        assertThat(store.load("identity.promoter_net_worth", "2.0.0")).isPresent();
        assertThat(store.latest("general", "identity.promoter_net_worth").get().version())
                .isEqualTo("2.0.0");
    }

    @Test
    void nextVersionHelperMatchesPublish() {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        assertThat(store.nextVersion("general", "identity.promoter_net_worth", true))
                .isEqualTo("1.0.0");
        assertThat(store.nextVersion("general", "identity.promoter_net_worth", false))
                .isEqualTo("1.0.0");
    }

    @Test
    void industryTagsAreIsolated() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        store.publish(request(pack("0.0.0", "net_worth")));
        PackStore.PublishedPack hotel = store.publish(new PackStore.PublishRequest(
                pack("0.0.0", "net_worth"), "hotel", null, List.of(), "run-2"));

        assertThat(hotel.version()).isEqualTo("1.0.0");
        assertThat(store.latest("general", "identity.promoter_net_worth").get().version())
                .isEqualTo("1.0.0");
        assertThat(store.latest("hotel", "identity.promoter_net_worth").get().version())
                .isEqualTo("1.0.0");
    }

    @Test
    void yamlRoundTripsThroughPackLoader() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackStore.PublishedPack published = store.publish(request(pack("0.0.0", "net_worth")));

        byte[] a = PackYamlWriter.writeBytes(published.pack());
        var reloaded = com.resurgent.tev.doc.pack.PackLoader.load(published.yamlPath());
        byte[] b = PackYamlWriter.writeBytes(reloaded);
        assertThat(a).isEqualTo(b);
    }
}
