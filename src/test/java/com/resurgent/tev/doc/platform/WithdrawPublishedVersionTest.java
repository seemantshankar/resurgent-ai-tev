package com.resurgent.tev.doc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.TevDocMain;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * Withdraw one published pack or checklist version (issue #191). A later
 * mandate copies the next version still published; a mandate that already
 * has the file keeps those bytes.
 */
class WithdrawPublishedVersionTest {

    private static final long MANDATE = 1L;

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
    void withdrawingTheHigherPackKeepsItWhereItWasCopiedAndHandsTheNextToANewMandate()
            throws Exception {
        Workspace first = workspace("mandate-a");
        Workspace second = workspace("mandate-b");
        ingest(first, "hydrogen-a.pdf", HYDROGEN);
        ingest(second, "hydrogen-b.pdf", HYDROGEN);
        Path registry = tempDir.resolve("platform.db");
        String[] induce = {"induce", "--industry", "green_hydrogen",
                "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString()};
        CommandResult lowerPass = command(induce);
        assertThat(lowerPass.exit()).as(lowerPass.err()).isZero();
        String lower = publishedPacks(lowerPass.out()).get(0);
        CommandResult higherPass = command(induce);
        assertThat(higherPass.exit()).as(higherPass.err()).isZero();
        String higher = publishedPacks(higherPass.out()).get(0);
        assertThat(codeOf(higher)).isEqualTo(codeOf(lower));
        assertThat(PackStore.compareVersions(versionOf(higher), versionOf(lower))).isPositive();

        Workspace already = workspace("already");
        long alreadyVersion = ingest(already, "hydrogen-already.pdf", HYDROGEN);
        CommandResult copied = classify(already, alreadyVersion, registry);
        assertThat(copied.exit()).as(copied.err()).isZero();
        assertThat(copied.out()).contains("pack " + higher);
        Path copiedFile = packFile(already, "green_hydrogen", higher);
        Path sourceFile = packFile(first, "green_hydrogen", higher);
        Path lowerFile = packFile(first, "green_hydrogen", lower);
        String copiedHash = sha256(copiedFile);
        String sourceHash = sha256(sourceFile);
        String lowerHash = sha256(lowerFile);

        assertThat(TevDocMain.commandLine().getSubcommands()).containsKey("withdraw");
        CommandResult withdrawn = command("withdraw",
                "--platform", registry.toString(),
                "--pack", higher,
                "--industry", "green_hydrogen");
        assertThat(withdrawn.exit()).as(withdrawn.err()).isZero();
        assertThat(withdrawn.out()).contains("withdrew pack " + higher);
        assertThat(withdrawn.out()).contains("industry green_hydrogen");
        assertThat(withdrawn.out()).contains("status withdrawn");
        assertThat(sha256(copiedFile)).isEqualTo(copiedHash);
        assertThat(sha256(sourceFile)).isEqualTo(sourceHash);
        assertThat(sha256(lowerFile)).isEqualTo(lowerHash);
        assertThat(Files.isRegularFile(registry)).isTrue();

        CommandResult stillClassified = command("classify",
                "--mandate", Long.toString(MANDATE),
                "--version", Long.toString(alreadyVersion),
                "--db", already.db().toString(),
                "--platform", registry.toString(),
                "--force",
                "--no-auto-induce");
        assertThat(stillClassified.exit()).as(stillClassified.err()).isZero();
        assertThat(stillClassified.out()).contains("pack " + higher);
        assertThat(extract(already, alreadyVersion, codeOf(higher)).out())
                .contains("Extracted with pack " + higher);
        CommandResult stillAssessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", already.db().toString(),
                "--platform", registry.toString());
        assertThat(stillAssessed.exit()).as(stillAssessed.err()).isZero();
        assertThat(stillAssessed.out()).contains("assess_checklist");
        assertThat(sha256(copiedFile)).isEqualTo(copiedHash);

        Workspace fresh = workspace("fresh");
        long freshVersion = ingest(fresh, "hydrogen-fresh.pdf", HYDROGEN);
        CommandResult classified = classify(fresh, freshVersion, registry);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack " + lower);
        assertThat(classified.out()).doesNotContain("pack " + higher);
        assertThat(Files.isRegularFile(packFile(fresh, "green_hydrogen", lower))).isTrue();
        assertThat(Files.exists(packFile(fresh, "green_hydrogen", higher))).isFalse();
        assertThat(sha256(packFile(fresh, "green_hydrogen", lower))).isEqualTo(lowerHash);
        assertThat(extract(fresh, freshVersion, codeOf(lower)).out())
                .contains("Extracted with pack " + lower);
        CommandResult assessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", fresh.db().toString(),
                "--platform", registry.toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(assessed.out()).contains("assess_checklist");
    }

    @Test
    void withdrawingTheHigherChecklistKeepsItWhereItWasCopiedAndHandsTheNextToANewMandate()
            throws Exception {
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
        assertThat(classify(first, firstVersion, null).exit()).isZero();
        assertThat(classify(second, secondVersion, null).exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", first.db().toString())
                .exit()).isZero();
        assertThat(command("assess", "--mandate", Long.toString(MANDATE), "--db", second.db().toString())
                .exit()).isZero();

        CommandResult firstChecklist = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(firstChecklist.exit()).as(firstChecklist.err()).isZero();
        String lower = registeredChecklist(firstChecklist.out());
        long landA = ingest(first, "land-a.pdf", LAND);
        long landB = ingest(second, "land-b.pdf", LAND);
        CommandResult landPacks = command("induce", "--industry", "green_hydrogen",
                "--doc-type", "land.survey",
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(landPacks.exit()).as(landPacks.err()).isZero();
        assertThat(classify(first, landA, null).out()).contains("pack land.survey@");
        assertThat(classify(second, landB, null).out()).contains("pack land.survey@");
        CommandResult bumped = command("induce-checklists", "--platform", registry.toString(),
                "--mandate", Long.toString(MANDATE), "--db", first.db().toString(),
                "--mandate", Long.toString(MANDATE), "--db", second.db().toString());
        assertThat(bumped.exit()).as(bumped.err()).isZero();
        String higher = registeredChecklist(bumped.out());
        assertThat(codeOf(higher)).isEqualTo(codeOf(lower));
        assertThat(PackStore.compareVersions(versionOf(higher), versionOf(lower))).isPositive();

        Workspace already = workspace("already");
        long alreadyVersion = ingest(already, "hydrogen-already.pdf", HYDROGEN);
        CommandResult copied = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", already.db().toString(),
                "--platform", registry.toString());
        assertThat(copied.exit()).as(copied.err()).isZero();
        Path copiedFile = checklistFile(already, higher);
        Path sourceFile = checklistFile(first, higher);
        Path lowerFile = checklistFile(first, lower);
        assertThat(Files.isRegularFile(copiedFile)).isTrue();
        String copiedHash = sha256(copiedFile);
        String sourceHash = sha256(sourceFile);
        String lowerHash = sha256(lowerFile);

        CommandResult withdrawn = command("withdraw",
                "--platform", registry.toString(),
                "--checklist", higher);
        assertThat(withdrawn.exit()).as(withdrawn.err()).isZero();
        assertThat(withdrawn.out()).contains("withdrew checklist " + higher);
        assertThat(withdrawn.out()).contains("status withdrawn");
        assertThat(sha256(copiedFile)).isEqualTo(copiedHash);
        assertThat(sha256(sourceFile)).isEqualTo(sourceHash);
        assertThat(sha256(lowerFile)).isEqualTo(lowerHash);

        CommandResult stillClassified = classify(already, alreadyVersion, registry);
        assertThat(stillClassified.exit()).as(stillClassified.err()).isZero();
        assertThat(stillClassified.out()).contains("pack " + pack);
        assertThat(extract(already, alreadyVersion, codeOf(pack)).exit()).isZero();
        CommandResult stillAssessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", already.db().toString(),
                "--platform", registry.toString());
        assertThat(stillAssessed.exit()).as(stillAssessed.err()).isZero();
        assertThat(stillAssessed.out()).contains("assess_checklist");
        assertThat(sha256(copiedFile)).isEqualTo(copiedHash);
        assertThat(command("qa", "--mandate", Long.toString(MANDATE),
                "--db", already.db().toString(), "--json").out())
                .contains("\"checklist_version\" : \"" + versionOf(higher) + "\"");

        Workspace fresh = workspace("fresh");
        long freshVersion = ingest(fresh, "hydrogen-fresh.pdf", HYDROGEN);
        CommandResult assessed = command("assess",
                "--mandate", Long.toString(MANDATE),
                "--db", fresh.db().toString(),
                "--platform", registry.toString());
        assertThat(assessed.exit()).as(assessed.err()).isZero();
        assertThat(assessed.out()).contains("assess_checklist");
        assertThat(Files.isRegularFile(checklistFile(fresh, lower))).isTrue();
        assertThat(Files.exists(checklistFile(fresh, higher))).isFalse();
        assertThat(sha256(checklistFile(fresh, lower))).isEqualTo(lowerHash);
        CommandResult classified = classify(fresh, freshVersion, registry);
        assertThat(classified.exit()).as(classified.err()).isZero();
        assertThat(classified.out()).contains("pack " + pack);
        assertThat(extract(fresh, freshVersion, codeOf(pack)).exit()).isZero();
        assertThat(command("qa", "--mandate", Long.toString(MANDATE),
                "--db", fresh.db().toString(), "--json").out())
                .contains("\"checklist_version\" : \"" + versionOf(lower) + "\"")
                .doesNotContain("\"checklist_version\" : \"" + versionOf(higher) + "\"");
    }

    @Test
    void aWithdrawWithABadArgumentIsRejectedAndDoesNotCreateARegistry() throws Exception {
        Path registry = tempDir.resolve("not-created.db");

        CommandResult noPlatform = command("withdraw",
                "--pack", "agreement.ppa@1.0.0", "--industry", "solar_power");
        assertThat(noPlatform.exit()).isEqualTo(3);
        assertThat(noPlatform.err()).contains("withdraw rejected");
        assertThat(Files.exists(registry)).isFalse();

        CommandResult neither = command("withdraw", "--platform", registry.toString());
        assertThat(neither.exit()).isEqualTo(3);
        assertThat(neither.err()).contains("withdraw rejected");
        assertThat(Files.exists(registry)).isFalse();

        CommandResult both = command("withdraw", "--platform", registry.toString(),
                "--pack", "agreement.ppa@1.0.0", "--industry", "solar_power",
                "--checklist", "in.green_hydrogen@1.0.0");
        assertThat(both.exit()).isEqualTo(3);
        assertThat(both.err()).contains("withdraw rejected");
        assertThat(Files.exists(registry)).isFalse();

        CommandResult noIndustry = command("withdraw", "--platform", registry.toString(),
                "--pack", "agreement.ppa@1.0.0");
        assertThat(noIndustry.exit()).isEqualTo(3);
        assertThat(Files.exists(registry)).isFalse();

        CommandResult noVersion = command("withdraw", "--platform", registry.toString(),
                "--checklist", "in.green_hydrogen");
        assertThat(noVersion.exit()).isEqualTo(3);
        assertThat(Files.exists(registry)).isFalse();

        CommandResult absent = command("withdraw", "--platform", registry.toString(),
                "--pack", "agreement.ppa@1.0.0", "--industry", "solar_power");
        assertThat(absent.exit()).as(absent.err()).isEqualTo(3);
        assertThat(absent.err()).contains("withdraw rejected");
        assertThat(Files.exists(registry)).isFalse();

        Workspace workspace = workspace("policy");
        Path existing = tempDir.resolve("platform.db");
        CommandResult policy = command("policy",
                "--mandate", Long.toString(MANDATE),
                "--db", workspace.db().toString(),
                "--platform", existing.toString());
        assertThat(policy.exit()).as(policy.err()).isZero();
        CommandResult missing = command("withdraw", "--platform", existing.toString(),
                "--pack", "agreement.ppa@9.9.9", "--industry", "solar_power");
        assertThat(missing.exit()).as(missing.err()).isEqualTo(3);
        assertThat(missing.err()).contains("withdraw rejected");
        CommandResult spine = command("withdraw", "--platform", existing.toString(),
                "--checklist", "in.spine@1.0.0");
        assertThat(spine.exit()).as(spine.err()).isEqualTo(3);
        assertThat(spine.err()).contains("withdraw rejected");
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
        assertThat(identities).as(stdout).isNotEmpty();
        return identities;
    }

    private static Path packFile(Workspace workspace, String industry, String identity) {
        return workspace.db().getParent().resolve("packs").resolve(industry)
                .resolve(codeOf(identity) + "@" + versionOf(identity) + ".yaml");
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    private static String codeOf(String identity) {
        int at = identity.lastIndexOf('@');
        return at < 0 ? identity : identity.substring(0, at);
    }

    private static String versionOf(String identity) {
        int at = identity.lastIndexOf('@');
        return at < 0 ? identity : identity.substring(at + 1);
    }

    private static String registeredChecklist(String stdout) {
        Matcher matcher = Pattern.compile("registered checklist (\\S+) ").matcher(stdout);
        assertThat(matcher.find()).as(stdout).isTrue();
        return matcher.group(1);
    }

    private static Path checklistFile(Workspace workspace, String identity) {
        return workspace.db().getParent().resolve("checklists").resolve(identity + ".yaml");
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
