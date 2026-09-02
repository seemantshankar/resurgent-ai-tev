package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.ProblemCode;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import com.resurgent.tev.parser.redact.RedactException;
import com.resurgent.tev.parser.redact.RedactService;
import com.resurgent.tev.parser.redact.RedactSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Coordinates redaction, temporary input, model enrichment, normalization, and QA. */
public final class EnrichService {

    private final EnrichmentModelClient modelClient;

    public EnrichService(EnrichmentModelClient modelClient) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
    }

    public EnrichSummary enrich(
            Path input,
            long mandateId,
            Path db,
            String sheetName,
            Path outputDirectory,
            ParserConfig parserConfig,
            LlmEnrichmentConfig llmConfig,
            WorkspaceDatabase.OpenOptions openOptions,
            EnrichmentPromptMode promptMode)
            throws IOException, SQLException, RedactException, EnrichmentInfrastructureException {
        requireSpreadsheet(input);
        Path standardRedacted = standardRedactedPath(input, outputDirectory);
        boolean preserveExistingRedacted = Files.isRegularFile(standardRedacted);
        Path redactionDirectory = preserveExistingRedacted
                ? Files.createTempDirectory("tev-enrichment-redacted-")
                : outputDirectory;
        try {
            RedactSummary redaction = new RedactService().redact(
                    input,
                    mandateId,
                    db,
                    sheetName,
                    redactionDirectory,
                    parserConfig,
                    openOptions);
            Path unhidden = new TemporaryUnhiddenCopyBuilder().build(
                    redaction.outputPath(), sheetName);
            try {
                RegionTypeMenuService menuService = new RegionTypeMenuService(db);
                WorksheetSnapshot snapshot =
                        new WorksheetSnapshotReader().read(unhidden, sheetName);
                EnrichmentReport validated = proposeUntilCovered(
                        input,
                        redaction.outputPath(),
                        unhidden,
                        sheetName,
                        promptMode,
                        llmConfig,
                        menuService,
                        snapshot);
                Path redactedArtifact = preserveExistingRedacted
                        ? standardRedacted
                        : redaction.outputPath();
                return new EnrichSummary(
                        validated, redactedArtifact, redaction.autoIngested());
            } finally {
                Files.deleteIfExists(unhidden);
            }
        } finally {
            if (preserveExistingRedacted) {
                Files.deleteIfExists(redactionDirectory.resolve(
                        standardRedacted.getFileName()));
                Files.deleteIfExists(redactionDirectory);
            }
        }
    }

    private EnrichmentReport proposeUntilCovered(
            Path input,
            Path redacted,
            Path unhidden,
            String sheetName,
            EnrichmentPromptMode promptMode,
            LlmEnrichmentConfig llmConfig,
            RegionTypeMenuService menuService,
            WorksheetSnapshot snapshot)
            throws SQLException, EnrichmentInfrastructureException {
        EnrichmentReport first = proposeAndValidate(
                input, redacted, unhidden, sheetName, promptMode, llmConfig, menuService, snapshot);
        if (!hasUnassigned(first)) {
            return first;
        }
        RepairWindow window = RepairWindow.from(first, snapshot.filledCells());
        if (window.leftovers().isEmpty()) {
            return first;
        }
        EnrichmentReport patched = repairAndValidate(
                input, redacted, unhidden, sheetName, promptMode, llmConfig, menuService,
                snapshot, first, window);
        return patched.problems().isEmpty() ? patched : first;
    }

    private EnrichmentReport repairAndValidate(
            Path input,
            Path redacted,
            Path unhidden,
            String sheetName,
            EnrichmentPromptMode promptMode,
            LlmEnrichmentConfig llmConfig,
            RegionTypeMenuService menuService,
            WorksheetSnapshot snapshot,
            EnrichmentReport first,
            RepairWindow window)
            throws SQLException, EnrichmentInfrastructureException {
        List<String> currentMenu = menuService.load();
        EnrichmentReport proposed = new LlmEnrichmentAdapter(llmConfig, modelClient).repair(
                new EnrichmentRepairInput(
                        redacted, unhidden, sheetName, currentMenu, promptMode, window));
        List<Region> merged = mergeRepair(first.regions(), proposed.regions(), window.nearbyIds());
        RegionTypeNormalizationResult normalized = menuService.normalizeProposals(
                merged.stream().map(Region::type).toList());
        EnrichmentReport normalizedReport = authoritativeReport(
                input,
                redacted,
                unhidden,
                sheetName,
                new EnrichmentReport(
                        first.version(),
                        first.fileName(),
                        first.sheetName(),
                        first.redactedInputPath(),
                        first.unhiddenTempPath(),
                        proposed.modelId(),
                        first.promptVersion(),
                        first.typeMenu(),
                        merged,
                        List.of()),
                normalized);
        return new RegionQaValidator().validate(snapshot, normalizedReport);
    }

    static List<Region> mergeRepair(
            List<Region> first,
            List<Region> patch,
            Set<String> replaceIds) {
        List<Region> merged = new ArrayList<>();
        for (Region region : first) {
            if (!replaceIds.contains(region.id())) {
                merged.add(region);
            }
        }
        Set<String> firstIds = new LinkedHashSet<>();
        for (Region region : first) {
            firstIds.add(region.id());
        }
        for (Region region : patch) {
            if (replaceIds.contains(region.id()) || !firstIds.contains(region.id())) {
                merged.add(region);
            }
        }
        return merged;
    }

    private EnrichmentReport proposeAndValidate(
            Path input,
            Path redacted,
            Path unhidden,
            String sheetName,
            EnrichmentPromptMode promptMode,
            LlmEnrichmentConfig llmConfig,
            RegionTypeMenuService menuService,
            WorksheetSnapshot snapshot)
            throws SQLException, EnrichmentInfrastructureException {
        List<String> currentMenu = menuService.load();
        EnrichmentReport proposed = new LlmEnrichmentAdapter(llmConfig, modelClient).enrich(
                new EnrichmentInput(redacted, unhidden, sheetName, currentMenu, promptMode));
        RegionTypeNormalizationResult normalized = menuService.normalizeProposals(
                proposed.regions().stream().map(Region::type).toList());
        EnrichmentReport normalizedReport = authoritativeReport(
                input, redacted, unhidden, sheetName, proposed, normalized);
        return new RegionQaValidator().validate(snapshot, normalizedReport);
    }

    private static boolean hasUnassigned(EnrichmentReport report) {
        return report.problems().stream()
                .map(Problem::code)
                .anyMatch(code -> code == ProblemCode.UNASSIGNED_CELL);
    }

    private static EnrichmentReport authoritativeReport(
            Path input,
            Path redacted,
            Path unhidden,
            String sheetName,
            EnrichmentReport proposed,
            RegionTypeNormalizationResult normalized) {
        List<Region> regions = new ArrayList<>(proposed.regions().size());
        for (int i = 0; i < proposed.regions().size(); i++) {
            Region region = proposed.regions().get(i);
            regions.add(new Region(
                    region.id(),
                    region.bounds(),
                    region.displayName(),
                    normalized.canonicalTypes().get(i),
                    region.purpose(),
                    region.cells(),
                    region.notes()));
        }
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                input.getFileName().toString(),
                sheetName,
                redacted.toAbsolutePath().normalize().toString(),
                unhidden.toAbsolutePath().normalize().toString(),
                proposed.modelId(),
                proposed.promptVersion(),
                new TypeMenu(normalized.types(), normalized.newTypesAdded()),
                regions,
                proposed.problems());
    }

    private static void requireSpreadsheet(Path input) throws RedactException {
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            throw new RedactException("enrich v1 supports .xlsx and .xls only: " + input.getFileName());
        }
    }

    private static Path standardRedactedPath(Path input, Path outputDirectory) {
        String fileName = input.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".xls")
                && !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            String stem = fileName.replaceFirst("(?i)\\.xls$", "");
            return outputDirectory.resolve(stem + "-redacted.xls");
        }
        String stem = fileName.replaceFirst("(?i)\\.xlsx$", "");
        return outputDirectory.resolve(stem + "-redacted.xlsx");
    }
}
