package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
            WorkspaceDatabase.OpenOptions openOptions)
            throws IOException, SQLException, RedactException, EnrichmentInfrastructureException {
        requireXlsx(input);
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
                List<String> currentMenu = menuService.load();
                EnrichmentReport proposed = new LlmEnrichmentAdapter(llmConfig, modelClient).enrich(
                        new EnrichmentInput(
                                redaction.outputPath(), unhidden, sheetName, currentMenu));
                RegionTypeNormalizationResult normalized = menuService.normalizeProposals(
                        proposed.regions().stream().map(Region::type).toList());
                EnrichmentReport normalizedReport = authoritativeReport(
                        input,
                        redaction.outputPath(),
                        unhidden,
                        sheetName,
                        proposed,
                        normalized);
                WorksheetSnapshot snapshot =
                        new WorksheetSnapshotReader().read(unhidden, sheetName);
                EnrichmentReport validated =
                        new RegionQaValidator().validate(snapshot, normalizedReport);
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

    private static void requireXlsx(Path input) throws RedactException {
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".xlsx")) {
            throw new RedactException("enrich v1 supports .xlsx only: " + input.getFileName());
        }
    }

    private static Path standardRedactedPath(Path input, Path outputDirectory) {
        String stem = input.getFileName().toString().replaceFirst("(?i)\\.xlsx$", "");
        return outputDirectory.resolve(stem + "-redacted.xlsx");
    }
}
