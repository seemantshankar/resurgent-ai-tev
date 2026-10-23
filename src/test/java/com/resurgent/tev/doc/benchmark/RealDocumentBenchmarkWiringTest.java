package com.resurgent.tev.doc.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline guard for the live-benchmark wiring (no client documents, no network):
 * the benchmark must make induced packs visible through the same pack store
 * {@code {db-parent}/packs} that classify/extract/bind/assess read in production,
 * and must not let the ingest-default {@code agreement} role filter them out —
 * the divergences that left every Bhagyanager sample document unclassified or
 * unextracted in the first live runs.
 */
class RealDocumentBenchmarkWiringTest {

    private static final String SYNTH_PACK = """
            code: sample.certificate
            version: 1.0.0
            family: certificate
            file_role: certificate
            industry_tags: [general]
            identity_signals:
              any_of:
                - "Sample Certificate"
            fields:
              - key: legal_name
                path: identity > Legal Name
                path_root: identity
                value_kind: text
                required: true
                prompt_hint: "Legal name as printed"
            """;

    @Test void seededPackStoreMakesInducedPacksVisibleAndUnlabeledRoleKeepsThemCandidates()
            throws Exception {
        Path root = Files.createTempDirectory("tev-bench-wiring");
        Path outDir = root.resolve("mandate-out");
        Files.createDirectories(outDir);

        Path store = root.resolve("earlier-run/packs");
        Files.createDirectories(store.resolve("general"));
        Files.writeString(store.resolve("general/sample.certificate@1.0.0.yaml"), SYNTH_PACK);
        String hash = PackStore.sha256Hex(SYNTH_PACK.getBytes(StandardCharsets.UTF_8));
        Files.writeString(store.resolve("registry.json"), """
                { "entries" : [ {
                    "code" : "sample.certificate",
                    "version" : "1.0.0",
                    "family" : "certificate",
                    "fileRole" : "certificate",
                    "industryTag" : "general",
                    "fileHash" : "%s",
                    "yamlPath" : "general/sample.certificate@1.0.0.yaml",
                    "status" : "published"
                } ] }""".formatted(hash));

        // packdir may point at the industry subfolder; the store root is inferred
        String previous = System.setProperty("tev.benchmark.packdir",
                store.resolve("general").toString());
        try {
            RealDocumentBenchmarkTest.seedPackStore(outDir);
            assertTrue(Files.exists(outDir.resolve("packs/registry.json")),
                    "pack store must be seeded next to the mandate database");

            List<DocTypePack> packs = RealDocumentBenchmarkTest.candidatePacks(
                    outDir.resolve("workspace.db"));
            assertTrue(packs.stream().anyMatch(p -> p.code().equals("agreement.ppa")),
                    "seed packs must stay candidates");
            assertTrue(packs.stream().anyMatch(p -> p.code().equals("sample.certificate")),
                    "induced pack from the seeded store must be a candidate");

            String page1 = "Sample Certificate of the Department\n"
                    + "Legal Name: Acme Industries Pvt Ltd";
            ClassifyResult unlabeled = new DocTypeClassifier().classify(new ClassifyRequest(
                    -1, "", "cert.pdf", page1, "general", packs, true));
            assertEquals("sample.certificate", unlabeled.docTypeCode(),
                    "empty role must keep every pack a candidate");

            ClassifyResult wrongRole = new DocTypeClassifier().classify(new ClassifyRequest(
                    -1, "agreement", "cert.pdf", page1, "general", packs, true));
            assertEquals("sample.certificate", wrongRole.docTypeCode(),
                    "a mismatched ingest role must not hide a pack whose identity signals match");
        } finally {
            if (previous == null) {
                System.clearProperty("tev.benchmark.packdir");
            } else {
                System.setProperty("tev.benchmark.packdir", previous);
            }
        }
    }
}
