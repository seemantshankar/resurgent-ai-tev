package com.resurgent.tev.doc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.TevDocMain;
import com.resurgent.tev.doc.checklist.ChecklistLoader;
import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Publish learned packs onto named workspaces and, when named, the platform
 * registry (issue #190). Operator-visible command outcomes only.
 */
class PublishLearnedPacksTest {

    private static final long MANDATE = 1L;

    private static final List<String> AGREEMENT = List.of(
            "Power Purchase Agreement",
            "Tariff: Rs 4.50 per kWh",
            "Tenor: 25 years",
            "COD: 01 April 2027",
            "Registered office: Jaipur, India");

    private static final List<String> HYDROGEN = List.of(
            "Green Hydrogen Offtake Certificate",
            "PLF: 22 percent",
            "CUF: 21 percent",
            "Registered office: Jaipur, India",
            "Project: green hydrogen");

    private static final List<String> LAND = List.of(
            "Land Survey Note",
            "Land area: 40 acres",
            "Yield: 1800 kWh per kW",
            "Registered office: Jaipur, India",
            "Project: green hydrogen");

    @TempDir
    Path tempDir;

    @Test
    void passingInductionAcrossMandatesPublishesOnePackIntoEachWorkspace() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        long firstVersion = ingest(first, "ppa-a.pdf", AGREEMENT);
        long secondVersion = ingest(second, "ppa-b.pdf", AGREEMENT);

        CommandResult induced = command("induce", "--industry", "solar_power",
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        List<String> published = publishedPacks(induced.out());
        assertThat(published).as(induced.out()).hasSize(1);

        assertThat(extract(first, firstVersion, codeOf(published.get(0))).out())
                .contains("Extracted with pack " + published.get(0));
        assertThat(extract(second, secondVersion, codeOf(published.get(0))).out())
                .contains("Extracted with pack " + published.get(0));
    }

    @Test
    void namedRegistryShowsThePackRegistration() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        ingest(first, "ppa-a.pdf", AGREEMENT);
        ingest(second, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("platform.db");

        CommandResult induced = command("induce", "--industry", "solar_power",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        assertThat(Files.isRegularFile(registry)).isTrue();
        String identity = publishedPacks(induced.out()).get(0);
        String hash = sha256(packFile(first, "solar_power", identity));
        assertThat(induced.out()).contains("registered pack " + identity);
        assertThat(induced.out()).contains("industry solar_power");
        assertThat(induced.out()).contains("hash " + hash);
        assertThat(induced.out()).contains(first.db().toAbsolutePath().normalize().toString());
        assertThat(induced.out()).contains(second.db().toAbsolutePath().normalize().toString());
    }

    @Test
    void failedGatesLeaveEveryWorkspaceOnTheVersionItHad() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        long firstVersion = ingest(first, "ppa-a.pdf", AGREEMENT);
        long secondVersion = ingest(second, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("platform.db");
        CommandResult induced = command("induce", "--industry", "solar_power",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        String identity = publishedPacks(induced.out()).get(0);
        String before = sha256(packFile(first, "solar_power", identity));

        ingest(first, "menu-a.pdf", List.of("Cafeteria Menu", "Tuesday special is lentil soup"));
        ingest(second, "menu-b.pdf", List.of("Cafeteria Menu", "Tuesday special is lentil soup"));
        CommandResult failed = command("induce", "--industry", "solar_power",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(failed.exit()).as(failed.err()).isZero();
        assertThat(failed.out()).doesNotContain("published pack");
        assertThat(failed.out()).doesNotContain("registered pack");
        assertThat(sha256(packFile(first, "solar_power", identity))).isEqualTo(before);
        assertThat(extract(first, firstVersion, codeOf(identity)).out())
                .contains("Extracted with pack " + identity);
        assertThat(extract(second, secondVersion, codeOf(identity)).out())
                .contains("Extracted with pack " + identity);
    }

    @Test
    void inductionWithoutARegistryCopiesAndDoesNotCreateOne() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        long firstVersion = ingest(first, "ppa-a.pdf", AGREEMENT);
        long secondVersion = ingest(second, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("not-created.db");

        CommandResult induced = command("induce", "--industry", "solar_power",
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        String identity = publishedPacks(induced.out()).get(0);
        assertThat(Files.exists(registry)).isFalse();
        assertThat(induced.out()).doesNotContain("registered pack");
        assertThat(extract(first, firstVersion, codeOf(identity)).out())
                .contains("Extracted with pack " + identity);
        assertThat(extract(second, secondVersion, codeOf(identity)).out())
                .contains("Extracted with pack " + identity);
    }

    @Test
    void laterMandateCopiesTheHighestVersionAndThenWorksWithoutTheRegistry() throws Exception {
        Workspace first = workspace("source-a");
        Workspace second = workspace("source-b");
        ingest(first, "ppa-a.pdf", AGREEMENT);
        ingest(second, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("platform.db");
        List<String> induce = new ArrayList<>(List.of("induce", "--industry", "solar_power",
                "--doc-type", "agreement.ppa", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString()));
        CommandResult firstPass = command(induce.toArray(String[]::new));
        assertThat(firstPass.exit()).as(firstPass.err()).isZero();
        CommandResult secondPass = command(induce.toArray(String[]::new));
        assertThat(secondPass.exit()).as(secondPass.err()).isZero();
        String identity = publishedPacks(secondPass.out()).get(0);
        assertThat(identity).isNotEqualTo("agreement.ppa@1.0.0");
        assertThat(secondPass.out()).contains("registered pack " + identity);

        Workspace later = workspace("later");
        long version = ingest(later, "ppa-later.pdf", AGREEMENT);
        CommandResult classified = command("classify",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", later.db().toString(),
                "--platform", registry.toString(),
                "--no-auto-induce");
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack " + identity);
        assertThat(Files.isRegularFile(packFile(later, "solar_power", identity))).isTrue();

        Files.delete(registry);
        assertThat(command("classify",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", later.db().toString(),
                "--force",
                "--no-auto-induce").out()).contains("pack " + identity);
        assertThat(extract(later, version, "agreement.ppa").out())
                .contains("Extracted with pack " + identity);
        CommandResult assessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", later.db().toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(assessed.out()).contains("assess_checklist");
    }

    @Test
    void passingChecklistInductionRegistersOneVersionAndStacksOnTheSpine() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        long firstVersion = ingest(first, "hydrogen-a.pdf", HYDROGEN);
        long secondVersion = ingest(second, "hydrogen-b.pdf", HYDROGEN);
        Path registry = tempDir.resolve("platform.db");
        CommandResult induced = command("induce", "--industry", "green_hydrogen",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        String pack = publishedPacks(induced.out()).get(0);
        assertThat(classify(first, firstVersion, null).out()).contains("pack " + pack);
        assertThat(classify(second, secondVersion, null).out()).contains("pack " + pack);
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", first.db().toString())
                .exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", second.db().toString())
                .exit()).isZero();

        CommandResult learned = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(learned.exit()).as(learned.err()).isZero();
        String checklist = registeredChecklist(learned.out());
        Path checklistFile = checklistFile(first, checklist);
        assertThat(learned.out()).contains("registered checklist " + checklist);
        assertThat(learned.out()).contains("country IN");
        assertThat(learned.out()).contains("industry green_hydrogen");
        assertThat(learned.out()).contains("hash " + sha256(checklistFile));
        assertThat(learned.out()).contains(first.db().toAbsolutePath().normalize().toString());
        assertThat(learned.out()).contains(second.db().toAbsolutePath().normalize().toString());
        assertThat(learned.out()).doesNotContain("registered checklist in.spine");
        assertThat(sha256(checklistFile(second, checklist))).isEqualTo(sha256(checklistFile));

        String before = sha256(checklistFile);
        CommandResult again = command("assess", "--mandate", Long.toString(MANDATE),
                "--db", first.db().toString(), "--platform", registry.toString());
        assertThat(again.exit()).as(again.err()).isZero();
        assertThat(sha256(checklistFile)).isEqualTo(before);
        CommandResult qa = command("qa", "--mandate", Long.toString(MANDATE),
                "--db", first.db().toString(), "--json");
        assertThat(qa.exit()).as(qa.err()).isZero();
        assertThat(qa.out()).contains("\"item_key\" : \"certificate_of_incorporation\"")
                .contains("\"checklist_version\" : \"1.0.0\"")
                .contains("\"item_key\" : \"" + itemKey(codeOf(pack)) + "\"")
                .contains("\"checklist_version\" : \"" + versionOf(checklist) + "\"");
    }

    @Test
    void laterMandateCopiesThePublishedChecklistAndKeepsItWhenTheRegistryIsGone() throws Exception {
        Workspace first = workspace("source-a");
        Workspace second = workspace("source-b");
        long firstVersion = ingest(first, "hydrogen-a.pdf", HYDROGEN);
        long secondVersion = ingest(second, "hydrogen-b.pdf", HYDROGEN);
        Path registry = tempDir.resolve("platform.db");
        CommandResult induced = command("induce", "--industry", "green_hydrogen",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        String pack = publishedPacks(induced.out()).get(0);
        assertThat(classify(first, firstVersion, null).exit()).isZero();
        assertThat(classify(second, secondVersion, null).exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", first.db().toString()).exit())
                .isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", second.db().toString()).exit())
                .isZero();
        CommandResult learned = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(learned.exit()).as(learned.err()).isZero();
        String checklist = registeredChecklist(learned.out());

        Workspace later = workspace("later");
        long version = ingest(later, "hydrogen-later.pdf", HYDROGEN);
        CommandResult assessed = command("assess", "--mandate", Long.toString(MANDATE),
                "--db", later.db().toString(), "--platform", registry.toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(Files.isRegularFile(checklistFile(later, checklist))).isTrue();
        CommandResult qa = command("qa", "--mandate", Long.toString(MANDATE),
                "--db", later.db().toString(), "--json");
        assertThat(qa.out()).contains("\"item_key\" : \"certificate_of_incorporation\"")
                .contains("\"item_key\" : \"" + itemKey(codeOf(pack)) + "\"")
                .contains("\"checklist_version\" : \"" + versionOf(checklist) + "\"");

        Files.delete(registry);
        CommandResult offline = command("assess", "--mandate", Long.toString(MANDATE),
                "--db", later.db().toString());
        assertThat(offline.exit()).as(offline.err()).isZero();
        assertThat(command("classify", "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version), "--db", later.db().toString(),
                "--no-auto-induce").out()).contains("pack " + pack);
        assertThat(command("qa", "--mandate", Long.toString(MANDATE),
                "--db", later.db().toString(), "--json").out())
                .contains("\"item_key\" : \"" + itemKey(codeOf(pack)) + "\"");
    }

    @Test
    void firstIndustryPublishesPackAndChecklistWithoutASeed() throws Exception {
        Workspace only = workspace("first");
        long version = ingest(only, "hydrogen.pdf", HYDROGEN);
        Path registry = tempDir.resolve("platform.db");
        CommandResult induced = command("induce", "--industry", "green_hydrogen",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", only.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        String pack = publishedPacks(induced.out()).get(0);
        assertThat(induced.out()).contains("registered pack " + pack);
        assertThat(PackLoader.loadSeedPacks()).extracting(p -> p.code()).doesNotContain(codeOf(pack));
        assertThat(ChecklistLoader.loadSeedSpine().code()).isEqualTo("in.spine");
        assertThat(classify(only, version, null).out()).contains("pack " + pack);
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", only.db().toString()).exit())
                .isZero();
        CommandResult learned = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", only.db().toString());
        assertThat(learned.exit()).as(learned.err()).isZero();
        String checklist = registeredChecklist(learned.out());
        assertThat(checklist).startsWith("in.green_hydrogen@");
        assertThat(learned.out()).doesNotContain("registered checklist in.spine");
        assertThat(Files.isRegularFile(checklistFile(only, checklist))).isTrue();
        assertThat(ChecklistLoader.loadSeedSpine().version()).isEqualTo("1.0.0");
    }

    @Test
    void newerLocalPackAndChecklistAreKeptAndExistingFilesAreNotRewritten() throws Exception {
        Workspace sourceA = workspace("source-a");
        Workspace sourceB = workspace("source-b");
        ingest(sourceA, "ppa-a.pdf", AGREEMENT);
        ingest(sourceB, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("platform.db");
        CommandResult olderPass = command("induce", "--industry", "solar_power",
                "--doc-type", "agreement.ppa", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", sourceA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", sourceB.db().toString());
        assertThat(olderPass.exit()).as(olderPass.err()).isZero();
        String older = publishedPacks(olderPass.out()).get(0);

        Workspace local = workspace("local");
        Workspace partner = workspace("partner");
        long localVersion = ingest(local, "ppa-local.pdf", AGREEMENT);
        ingest(partner, "ppa-partner.pdf", AGREEMENT);
        List<String> localInduce = new ArrayList<>(List.of("induce", "--industry", "solar_power",
                "--doc-type", "agreement.ppa",
                "--mandate", Long.toString(MANDATE), "--db", local.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", partner.db().toString()));
        assertThat(command(localInduce.toArray(String[]::new)).exit()).isZero();
        CommandResult newerPass = command(localInduce.toArray(String[]::new));
        assertThat(newerPass.exit()).as(newerPass.err()).isZero();
        String newer = publishedPacks(newerPass.out()).get(0);
        assertThat(PackStore.compareVersions(versionOf(newer), versionOf(older))).isPositive();
        Path newerFile = packFile(local, "solar_power", newer);
        Path olderFile = packFile(local, "solar_power", older);
        String newerHash = sha256(newerFile);
        String olderHash = Files.isRegularFile(olderFile) ? sha256(olderFile) : null;
        CommandResult classified = classify(local, localVersion, registry);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack " + newer);
        assertThat(classified.out()).doesNotContain("pack " + older);
        assertThat(sha256(newerFile)).isEqualTo(newerHash);
        if (olderHash != null) {
            assertThat(sha256(olderFile)).isEqualTo(olderHash);
        }

        Workspace hydrogenA = workspace("hydrogen-a");
        Workspace hydrogenB = workspace("hydrogen-b");
        long hydrogenAVersion = ingest(hydrogenA, "hydrogen-a.pdf", HYDROGEN);
        long hydrogenBVersion = ingest(hydrogenB, "hydrogen-b.pdf", HYDROGEN);
        CommandResult hydrogenPacks = command("induce", "--industry", "green_hydrogen",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenB.db().toString());
        assertThat(hydrogenPacks.exit()).as(hydrogenPacks.err()).isZero();
        assertThat(classify(hydrogenA, hydrogenAVersion, null).exit()).isZero();
        assertThat(classify(hydrogenB, hydrogenBVersion, null).exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", hydrogenA.db().toString())
                .exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", hydrogenB.db().toString())
                .exit()).isZero();
        CommandResult firstChecklist = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenB.db().toString());
        assertThat(firstChecklist.exit()).as(firstChecklist.err()).isZero();
        String publishedChecklist = registeredChecklist(firstChecklist.out());
        long landA = ingest(hydrogenA, "land-a.pdf", LAND);
        long landB = ingest(hydrogenB, "land-b.pdf", LAND);
        CommandResult landPacks = command("induce", "--industry", "green_hydrogen",
                "--doc-type", "land.survey",
                "--mandate", Long.toString(MANDATE), "--db", hydrogenA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenB.db().toString());
        assertThat(landPacks.exit()).as(landPacks.err()).isZero();
        assertThat(classify(hydrogenA, landA, null).out()).contains("pack land.survey@");
        assertThat(classify(hydrogenB, landB, null).out()).contains("pack land.survey@");
        CommandResult bumped = command("induce-checklists",
                "--mandate", Long.toString(MANDATE), "--db", hydrogenA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", hydrogenB.db().toString());
        assertThat(bumped.exit()).as(bumped.err()).isZero();
        assertThat(bumped.out()).contains("learned in.green_hydrogen@");
        assertThat(bumped.out()).doesNotContain("registered checklist");
        String localChecklist = learnedChecklist(bumped.out());
        assertThat(PackStore.compareVersions(versionOf(localChecklist), versionOf(publishedChecklist)))
                .isPositive();
        Path publishedFile = checklistFile(hydrogenA, publishedChecklist);
        Path localFile = checklistFile(hydrogenA, localChecklist);
        String publishedHash = sha256(publishedFile);
        String localHash = sha256(localFile);
        CommandResult reassessed = command("assess", "--mandate", Long.toString(MANDATE),
                "--db", hydrogenA.db().toString(), "--platform", registry.toString());
        assertThat(reassessed.exit()).as(reassessed.err()).isZero();
        assertThat(sha256(publishedFile)).isEqualTo(publishedHash);
        assertThat(sha256(localFile)).isEqualTo(localHash);
        assertThat(command("qa", "--mandate", Long.toString(MANDATE),
                "--db", hydrogenA.db().toString(), "--json").out())
                .contains("\"item_key\" : \"land_survey\"")
                .contains("\"checklist_version\" : \"" + versionOf(localChecklist) + "\"");
    }

    @Test
    void aCommandPointedAtANewRegistryRecordsTheMandate() throws Exception {
        Workspace workspace = workspace("record");
        long version = ingest(workspace, "ppa.pdf", AGREEMENT);
        rememberIndustry(workspace.db(), "solar_power");
        Path absent = tempDir.resolve("classify-without-platform.db");

        CommandResult plain = classify(workspace, version, null);
        assertThat(plain.exit()).as(plain.err()).isZero();
        assertThat(Files.exists(absent)).isFalse();

        Path classifiedRegistry = tempDir.resolve("from-classify.db");
        CommandResult classified = classify(workspace, version, classifiedRegistry);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(Files.isRegularFile(classifiedRegistry)).isTrue();
        forgetIndustry(workspace.db());
        assertThat(policy(workspace, classifiedRegistry).out())
                .contains("mandate 1 path " + workspace.db().toAbsolutePath().normalize()
                        + " industry solar_power status open");

        Path extractedRegistry = tempDir.resolve("from-extract.db");
        rememberIndustry(workspace.db(), "solar_power");
        CommandResult extracted = command("extract",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", workspace.db().toString(),
                "--pack", "agreement.ppa",
                "--extractor", "fixture",
                "--no-parse",
                "--no-auto-induce",
                "--platform", extractedRegistry.toString());
        assertThat(extracted.exit()).as(extracted.err()).isZero();
        assertThat(Files.isRegularFile(extractedRegistry)).isTrue();
        forgetIndustry(workspace.db());
        assertThat(policy(workspace, extractedRegistry).out())
                .contains("mandate 1 path " + workspace.db().toAbsolutePath().normalize()
                        + " industry solar_power status open");

        Workspace detected = workspace("detected");
        ingest(detected, "solar.pdf", List.of(
                "Solar capacity note",
                "Capacity: 10 MW",
                "Registered office: Jaipur, India"));
        Path assessedRegistry = tempDir.resolve("from-assess.db");
        CommandResult assessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", detected.db().toString(),
                "--platform", assessedRegistry.toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(Files.isRegularFile(assessedRegistry)).isTrue();
        forgetIndustry(detected.db());
        assertThat(policy(detected, assessedRegistry).out())
                .contains("mandate 1 path " + detected.db().toAbsolutePath().normalize())
                .contains("industry solar power")
                .contains("status open");
    }

    @Test
    void aFailedInductionStillRecordsTheMandateWhenNothingIsPublished() throws Exception {
        Workspace first = workspace("fail-a");
        Workspace second = workspace("fail-b");
        ingest(first, "ppa-a.pdf", AGREEMENT);
        ingest(second, "ppa-b.pdf", AGREEMENT);
        CommandResult induced = command("induce", "--industry", "solar_power",
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        ingest(first, "menu-a.pdf", List.of("Cafeteria Menu", "Tuesday special is lentil soup"));
        ingest(second, "menu-b.pdf", List.of("Cafeteria Menu", "Tuesday special is lentil soup"));
        rememberIndustry(first.db(), "solar_power");
        rememberIndustry(second.db(), "solar_power");
        Path registry = tempDir.resolve("failed-induce.db");

        CommandResult failed = command("induce", "--industry", "solar_power",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(failed.exit()).as(failed.err()).isZero();
        assertThat(failed.out()).doesNotContain("published pack");
        assertThat(failed.out()).doesNotContain("registered pack");
        assertThat(Files.isRegularFile(registry)).isTrue();
        forgetIndustry(first.db());
        forgetIndustry(second.db());
        assertThat(policy(first, registry).out())
                .contains("mandate 1 path " + first.db().toAbsolutePath().normalize()
                        + " industry solar_power status open");
        assertThat(policy(second, registry).out())
                .contains("mandate 1 path " + second.db().toAbsolutePath().normalize()
                        + " industry solar_power status open");
    }

    @Test
    void aLaterMandateLoadsItsOwnIndustryPackNotAHigherPackFromAnotherIndustry() throws Exception {
        Workspace solarA = workspace("solar-a");
        Workspace solarB = workspace("solar-b");
        ingest(solarA, "ppa-a.pdf", AGREEMENT);
        ingest(solarB, "ppa-b.pdf", AGREEMENT);
        Path registry = tempDir.resolve("platform.db");
        String[] solar = {"induce", "--industry", "solar_power", "--doc-type", "agreement.ppa",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", solarA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", solarB.db().toString()};
        assertThat(command(solar).exit()).isZero();
        CommandResult solarPass = command(solar);
        assertThat(solarPass.exit()).as(solarPass.err()).isZero();
        String solarPack = publishedPacks(solarPass.out()).get(0);

        Workspace hotelA = workspace("hotel-a");
        Workspace hotelB = workspace("hotel-b");
        ingest(hotelA, "ppa-c.pdf", AGREEMENT);
        ingest(hotelB, "ppa-d.pdf", AGREEMENT);
        String[] hotels = {"induce", "--industry", "hotels", "--doc-type", "agreement.ppa",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", hotelA.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", hotelB.db().toString()};
        assertThat(command(hotels).exit()).isZero();
        assertThat(command(hotels).exit()).isZero();
        CommandResult hotelPass = command(hotels);
        assertThat(hotelPass.exit()).as(hotelPass.err()).isZero();
        String hotelPack = publishedPacks(hotelPass.out()).get(0);
        assertThat(codeOf(hotelPack)).isEqualTo(codeOf(solarPack));
        assertThat(PackStore.compareVersions(versionOf(hotelPack), versionOf(solarPack))).isPositive();

        Workspace later = workspace("later-solar");
        long version = ingest(later, "ppa-later.pdf", AGREEMENT);
        rememberIndustry(later.db(), "solar_power");
        CommandResult classified = classify(later, version, registry);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack " + solarPack);
        assertThat(classified.out()).doesNotContain("pack " + hotelPack);
        assertThat(Files.isRegularFile(packFile(later, "solar_power", solarPack))).isTrue();
        assertThat(Files.exists(packFile(later, "hotels", hotelPack))).isFalse();
    }

    @Test
    void anOlderLocalPackDoesNotHideANewerSeed() throws Exception {
        Workspace workspace = workspace("older-local");
        DocTypePack seed = PackLoader.loadClasspath("docpacks/quotation.vendor.yaml");
        new PackStore(workspace.db().getParent().resolve("packs")).installCopy(
                new PackStore.PublishRequest(seed, "general", null, List.of(), "older-local"),
                "0.9.0");
        Path older = workspace.db().getParent().resolve("packs").resolve("general")
                .resolve("quotation.vendor@0.9.0.yaml");
        assertThat(older).exists();
        String olderHash = sha256(older);
        long version = ingestQuotation(workspace);

        CommandResult classified = classify(workspace, version, null);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack quotation.vendor@1.0.0");
        assertThat(classified.out()).doesNotContain("pack quotation.vendor@0.9.0");
        CommandResult extracted = command("extract",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", workspace.db().toString(),
                "--pack", "quotation.vendor",
                "--extractor", "fixture",
                "--no-parse",
                "--no-auto-induce");
        assertThat(extracted.exit()).as(extracted.err()).isZero();
        assertThat(extracted.out()).contains("Extracted with pack quotation.vendor@1.0.0");
        assertThat(extracted.out()).doesNotContain("quotation.vendor@0.9.0");
        CommandResult assessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", workspace.db().toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(sha256(older)).isEqualTo(olderHash);
    }

    @Test
    void softLeavesAliasesAndOpenClauseTypesStayInTheMandateWorkspace() throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        ingest(first, "ppa-a.pdf", AGREEMENT);
        ingest(second, "ppa-b.pdf", AGREEMENT);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(first.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            repo.insertNomenclatureNode(new NomenclatureNode(
                    "economic > Private Leaf", "Private Leaf", "economic",
                    NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, MANDATE),
                    Timestamps.now());
            repo.insertNomenclatureAlias(new NomenclatureAlias(
                    "client wording", "economic > Private Leaf"),
                    NomenclatureNode.LAYER_MANDATE_SOFT, null, MANDATE, Timestamps.now());
            new ClauseRepository(db.connection()).findOrInsertProposedType(
                    "private_open_clause", "Private open clause", "covenant");
        }
        Path registry = tempDir.resolve("platform.db");
        CommandResult induced = command("induce", "--industry", "solar_power",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(induced.exit()).as(induced.err()).isZero();
        assertThat(publishedPacks(induced.out())).hasSize(1);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(first.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            assertThat(repo.selectNomenclatureOverlay(MANDATE))
                    .extracting(NomenclatureNode::path).contains("economic > Private Leaf");
            assertThat(repo.selectNomenclatureAliases(NomenclatureNode.LAYER_MANDATE_SOFT, null, MANDATE))
                    .extracting(NomenclatureAlias::aliasText).contains("client wording");
            assertThat(openClauseCount(db.connection(), "private_open_clause")).isEqualTo(1);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(second.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            assertThat(repo.selectNomenclatureOverlay(MANDATE)).isEmpty();
            assertThat(repo.selectNomenclatureAliases(NomenclatureNode.LAYER_MANDATE_SOFT, null, MANDATE))
                    .isEmpty();
            assertThat(openClauseCount(db.connection(), "private_open_clause")).isZero();
        }
    }

    private static void rememberIndustry(Path db, String key) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                PreparedStatement ps = workspace.connection().prepareStatement(
                        "INSERT INTO mandate_industry_record"
                                + " (mandate_id, industry, industry_key, is_new_industry, reason, created_at)"
                                + " VALUES (?, ?, ?, 0, ?, ?)")) {
            ps.setLong(1, MANDATE);
            ps.setString(2, key);
            ps.setString(3, key);
            ps.setString(4, "already detected");
            ps.setString(5, "2026-10-26T00:00:00Z");
            ps.executeUpdate();
        }
    }

    private static void forgetIndustry(Path db) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                PreparedStatement ps = workspace.connection().prepareStatement(
                        "DELETE FROM mandate_industry_record WHERE mandate_id = ?")) {
            ps.setLong(1, MANDATE);
            ps.executeUpdate();
        }
    }

    private CommandResult policy(Workspace workspace, Path registry) {
        return command("policy",
                "--mandate", Long.toString(MANDATE),
                "--db", workspace.db().toString(),
                "--platform", registry.toString());
    }

    private long ingestQuotation(Workspace workspace) throws Exception {
        Path pdf = workspace.db().getParent().resolve("quotation.pdf");
        writePdf(pdf, List.of(
                "Quotation for the supply of project equipment",
                "Grand Total Rs 1000"));
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, "Vendor quotation", "v1", "quotation", "quotation"),
                workspace.db(), workspace.blobs());
        new DocumentLayoutService().layout(
                ingested.documentVersionId(), workspace.db(), workspace.blobs());
        return ingested.documentVersionId();
    }

    private CommandResult classify(Workspace workspace, long version, Path platform) {
        List<String> args = new ArrayList<>(List.of("classify",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", workspace.db().toString(),
                "--no-auto-induce"));
        if (platform != null) {
            args.add("--platform");
            args.add(platform.toString());
        }
        return command(args.toArray(String[]::new));
    }

    private CommandResult extract(Workspace workspace, long version, String packCode) {
        return command("extract",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(version),
                "--db", workspace.db().toString(),
                "--pack", packCode,
                "--extractor", "fixture",
                "--no-parse",
                "--no-auto-induce");
    }

    private static List<String> publishedPacks(String stdout) {
        Matcher matcher = Pattern.compile("published pack (\\S+) ").matcher(stdout);
        List<String> identities = new ArrayList<>();
        while (matcher.find()) {
            identities.add(matcher.group(1));
        }
        return identities;
    }

    private static Path packFile(Workspace workspace, String industry, String identity) {
        int at = identity.lastIndexOf('@');
        String code = identity.substring(0, at);
        String version = identity.substring(at + 1);
        return workspace.db().getParent().resolve("packs").resolve(industry)
                .resolve(code + "@" + version + ".yaml");
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String codeOf(String identity) {
        int at = identity.lastIndexOf('@');
        return at < 0 ? identity : identity.substring(0, at);
    }

    private static String versionOf(String identity) {
        int at = identity.lastIndexOf('@');
        return at < 0 ? identity : identity.substring(at + 1);
    }

    private static String itemKey(String documentType) {
        return documentType.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String registeredChecklist(String stdout) {
        Matcher matcher = Pattern.compile("registered checklist (\\S+) ").matcher(stdout);
        assertThat(matcher.find()).as(stdout).isTrue();
        return matcher.group(1);
    }

    private static String learnedChecklist(String stdout) {
        Matcher matcher = Pattern.compile("learned (\\S+) ").matcher(stdout);
        String identity = null;
        while (matcher.find()) {
            if (matcher.group(1).startsWith("in.green_hydrogen@")) {
                identity = matcher.group(1);
            }
        }
        assertThat(identity).as(stdout).isNotNull();
        return identity;
    }

    private static Path checklistFile(Workspace workspace, String identity) {
        return workspace.db().getParent().resolve("checklists").resolve(identity + ".yaml");
    }

    private static int openClauseCount(Connection connection, String code) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM clause_type WHERE code = ? AND status = 'open'")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Workspace workspace(String name) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve(name));
        Path db = directory.resolve("workspace.db");
        try (WorkspaceDatabase opened = WorkspaceDatabase.open(db)) {
            new MandatePolicyRepository(opened.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }
        return new Workspace(db, BlobStore.besideDatabase(db));
    }

    private long ingest(Workspace workspace, String fileName, List<String> lines) throws Exception {
        Path pdf = workspace.db().getParent().resolve(fileName);
        writePdf(pdf, lines);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, lines.get(0), "v1", "agreement", "agreement"),
                workspace.db(), workspace.blobs());
        new DocumentLayoutService().layout(ingested.documentVersionId(), workspace.db(), workspace.blobs());
        return ingested.documentVersionId();
    }

    private static void writePdf(Path pdf, List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 18;
                }
            }
            doc.save(pdf.toFile());
        }
    }

    private static CommandResult command(String... args) {
        CommandLine cli = TevDocMain.commandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        int exit = cli.execute(args);
        return new CommandResult(exit, out.toString(), err.toString());
    }

    private record Workspace(Path db, BlobStore blobs) {}

    private record CommandResult(int exit, String out, String err) {}
}
