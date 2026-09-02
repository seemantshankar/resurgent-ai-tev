package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.poi.ss.util.CellReference;

/**
 * Versioned prompt and response boundary for one-tab LLM enrichment.
 */
public final class LlmEnrichmentAdapter {

    public static final String PROMPT_VERSION = "enrichment-v2.4";
    public static final String PROMPT_VERSION_REGIONS_ONLY = "enrichment-v2.4-regions-only";
    public static final String PROMPT_VERSION_REPAIR = "enrichment-v2.4-repair";
    public static final String PROMPT_VERSION_REPAIR_REGIONS_ONLY = "enrichment-v2.4-repair-regions-only";

    /** @deprecated v1 flat TSV input; retained for tests comparing legacy prompts */
    public static final String LEGACY_PROMPT_VERSION = "enrichment-v1";

    /** @deprecated v1 flat TSV input; retained for tests comparing legacy prompts */
    public static final String LEGACY_PROMPT_VERSION_REGIONS_ONLY = "enrichment-v1-regions-only";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmEnrichmentConfig config;
    private final EnrichmentModelClient client;

    public LlmEnrichmentAdapter(LlmEnrichmentConfig config, EnrichmentModelClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
    }

    public static boolean isRegionsOnly(String promptVersion) {
        return promptVersion != null && promptVersion.endsWith("-regions-only");
    }

    public static String promptVersion(EnrichmentPromptMode mode) {
        return mode == EnrichmentPromptMode.REGIONS_ONLY
                ? PROMPT_VERSION_REGIONS_ONLY
                : PROMPT_VERSION;
    }

    public static String repairPromptVersion(EnrichmentPromptMode mode) {
        return mode == EnrichmentPromptMode.REGIONS_ONLY
                ? PROMPT_VERSION_REPAIR_REGIONS_ONLY
                : PROMPT_VERSION_REPAIR;
    }

    public EnrichmentReport enrich(EnrichmentInput input)
            throws EnrichmentInfrastructureException {
        try {
            return complete(input.sheetName(), promptVersion(input.mode()), buildPrompt(input));
        } catch (EnrichmentInfrastructureException e) {
            throw e;
        } catch (Exception e) {
            throw new EnrichmentInfrastructureException(
                    "external enrichment failed: " + message(e), e);
        }
    }

    public EnrichmentReport repair(EnrichmentRepairInput input)
            throws EnrichmentInfrastructureException {
        try {
            return complete(
                    input.sheetName(),
                    repairPromptVersion(input.mode()),
                    buildRepairPrompt(input));
        } catch (EnrichmentInfrastructureException e) {
            throw e;
        } catch (Exception e) {
            throw new EnrichmentInfrastructureException(
                    "external enrichment failed: " + message(e), e);
        }
    }

    private EnrichmentReport complete(String sheetName, String expectedPromptVersion, String prompt)
            throws EnrichmentInfrastructureException {
        try {
            String response = client.generate(new EnrichmentModelRequest(
                    config.apiKey(),
                    config.modelId(),
                    config.endpoint(),
                    config.httpReferer(),
                    config.appTitle(),
                    prompt,
                    config.maxOutputTokens()));
            EnrichmentReport report;
            try {
                report = EnrichmentReportJson.fromModelContent(response);
            } catch (EnrichmentReportFormatException parseFailure) {
                Path debug = Files.createTempFile(
                        "tev-enrich-raw-response-",
                        "-" + sheetName.replaceAll("[^A-Za-z0-9._-]+", "_") + ".txt");
                Files.writeString(debug, response);
                throw new EnrichmentReportFormatException(
                        parseFailure.getMessage() + "; raw response written to " + debug,
                        parseFailure);
            }
            if (!config.modelId().equals(report.modelId())) {
                throw new EnrichmentReportFormatException(
                        "modelId must match configured model " + config.modelId());
            }
            if (!expectedPromptVersion.equals(report.promptVersion())) {
                throw new EnrichmentReportFormatException(
                        "promptVersion must be " + expectedPromptVersion);
            }
            return report;
        } catch (Exception e) {
            throw new EnrichmentInfrastructureException(
                    "external enrichment failed: " + message(e), e);
        }
    }

    private String buildPrompt(EnrichmentInput input) throws Exception {
        String sampleType = input.typeMenu().isEmpty() ? "Civil Cost" : input.typeMenu().getFirst();
        String fileName = input.redactedWorkbook().getFileName().toString();
        String redactedPath = input.redactedWorkbook().toString();
        String unhiddenPath = input.unhiddenWorkbook().toString();
        String exampleJson = input.mode() == EnrichmentPromptMode.REGIONS_ONLY
                ? EnrichmentPromptExamples.regionsOnly(
                        fileName,
                        input.sheetName(),
                        redactedPath,
                        unhiddenPath,
                        config.modelId(),
                        sampleType,
                        promptVersion(input.mode()))
                : EnrichmentPromptExamples.full(
                        fileName,
                        input.sheetName(),
                        redactedPath,
                        unhiddenPath,
                        config.modelId(),
                        sampleType,
                        promptVersion(input.mode()));

        WorksheetEnrichmentView view = new WorksheetEnrichmentViewBuilder().build(
                input.unhiddenWorkbook(), input.sheetName());

        StringBuilder prompt = new StringBuilder();
        prompt.append("Return only valid JSON matching enrichment-report-v1.\n");
        prompt.append("Example structure (metadata values must match this prompt):\n");
        prompt.append(exampleJson).append('\n');
        prompt.append("""
                Input format: sparse grid (Excel-like layout with real row numbers and column
                letters) and cell index (NDJSON with one object per filled cell). Use ONLY
                coordinates from the cell index for region bounds and region.cells addresses.
                Do not invent addresses that are not in the cell index.
                Decide regions from labels and formulas, not from blank-row gaps. One region
                per distinct table. A table's bounding box MUST include its title, unit banner,
                column header row(s), and row header column(s). Never emit a separate region
                that is only year/column headers (e.g. D139:M139) when those headers belong
                to the table immediately below. A labeled continuation in the same column
                band (e.g. "Net Fixed Assets excluding Land" under the Net Fixed Assets
                years) stays in that table's box.
                A block separated from a live table (blank column or off to the side) is
                Scratch unless a formula in a main/Required table REFERENCES those cells.
                Do not treat an island as Required merely because its own formulas read the
                main table. Side checksums that only compute from the schedule and are unused
                (e.g. O47:V48, O58:P58, U59:W59) stay Scratch. A region referenced by a
                Required formula must be Required — never Scratch or Orphan.
                """);
        if (input.mode() == EnrichmentPromptMode.REGIONS_ONLY) {
            prompt.append("""
                    This is a regions-only pass. Propose region boxes only: id, bounds,
                    displayName, type, purpose, and notes. Leave cells as an empty array for
                    every region. Every filled cell from the cell index must fall inside exactly
                    one region bounds. Do not list individual cells yet.
                    purpose must be exactly "Required", "Scratch", or "Orphan" (that spelling).
                    """);
        } else {
            prompt.append("""
                    Propose regions and list every filled cell exactly once in region.cells.
                    Blank cells are omitted from the cell index and must not appear in
                    region.cells. Region bounds may cover empty space, but list only filled
                    addresses from the cell index in each region.
                    Use one region per distinct table and assign every filled cell exactly once.
                    Region purpose is Required, Scratch, or Orphan. purpose must be exactly
                    "Required", "Scratch", or "Orphan" (that spelling). A region referenced by a
                    Required formula must be Required. Reuse synonymous entries from the type
                    menu before proposing a new type. Cell roles are title, annotation,
                    rowHeader, columnHeader, and amount. Only amount cells in Required regions
                    receive row and column labels.
                    """);
        }
        prompt.append("Prompt version: ").append(promptVersion(input.mode())).append('\n');
        prompt.append("Report version: ").append(EnrichmentReport.VERSION).append('\n');
        prompt.append("File name: ").append(fileName).append('\n');
        prompt.append("Sheet: ").append(input.sheetName()).append('\n');
        prompt.append("Redacted input path: ").append(redactedPath).append('\n');
        prompt.append("Unhidden temporary path: ").append(unhiddenPath).append('\n');
        prompt.append("Model id: ").append(config.modelId()).append('\n');
        prompt.append("Type menu: ").append(String.join(", ", input.typeMenu())).append('\n');
        prompt.append("Filled cell count: ").append(view.filledCellCount()).append('\n');
        prompt.append("Used range rows ").append(view.minRow()).append('-').append(view.maxRow());
        prompt.append(", columns ")
                .append(CellReference.convertNumToColString(view.minCol()))
                .append('-')
                .append(CellReference.convertNumToColString(view.maxCol()))
                .append('\n');
        prompt.append("Sparse grid (Row N | col values; each value is coord:display):\n");
        prompt.append(view.columnHeaderLine()).append('\n');
        prompt.append(view.sparseGrid()).append('\n');
        prompt.append("Cell index (NDJSON, one filled cell per line):\n");
        prompt.append(view.cellIndexNdjson()).append('\n');
        return prompt.toString();
    }

    private String buildRepairPrompt(EnrichmentRepairInput input) throws Exception {
        String sampleType = input.typeMenu().isEmpty() ? "Civil Cost" : input.typeMenu().getFirst();
        String fileName = input.redactedWorkbook().getFileName().toString();
        String expectedVersion = repairPromptVersion(input.mode());
        String exampleJson = input.mode() == EnrichmentPromptMode.REGIONS_ONLY
                ? EnrichmentPromptExamples.regionsOnly(
                        fileName,
                        input.sheetName(),
                        input.redactedWorkbook().toString(),
                        input.unhiddenWorkbook().toString(),
                        config.modelId(),
                        sampleType,
                        expectedVersion)
                : EnrichmentPromptExamples.full(
                        fileName,
                        input.sheetName(),
                        input.redactedWorkbook().toString(),
                        input.unhiddenWorkbook().toString(),
                        config.modelId(),
                        sampleType,
                        expectedVersion);
        WorksheetEnrichmentView view = new WorksheetEnrichmentViewBuilder().build(
                input.unhiddenWorkbook(), input.sheetName());
        RepairWindow window = input.window();
        String croppedGrid = RepairWindow.cropSparseGrid(view.sparseGrid(), window.cropCells());
        String croppedIndex = RepairWindow.cropNdjson(view.cellIndexNdjson(), window.cropCells());
        List<NearbyRegionJson> nearby = new ArrayList<>();
        for (var region : window.nearbyRegions()) {
            nearby.add(new NearbyRegionJson(
                    region.id(),
                    region.bounds(),
                    region.displayName(),
                    region.type(),
                    region.purpose().jsonValue()));
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Return only valid JSON matching enrichment-report-v1.\n");
        prompt.append("Example structure (metadata values must match this prompt):\n");
        prompt.append(exampleJson).append('\n');
        prompt.append("""
                This is a repair pass. The first pass left filled cells outside every region.
                Assign those leftovers only. Return replacement boxes for the nearby regions
                listed below, and add new regions if a leftover belongs in its own box.
                Do not emit or retouch regions whose ids are not in that nearby list.
                Do not re-assign cells that are already inside a listed region except by
                expanding that region's bounds. Use ONLY coordinates from this cropped cell
                index. Every leftover address must fall inside exactly one returned region.
                """);
        if (input.mode() == EnrichmentPromptMode.REGIONS_ONLY) {
            prompt.append("""
                    This is a regions-only pass. Leave cells as an empty array for every
                    region. purpose must be exactly "Required", "Scratch", or "Orphan".
                    """);
        } else {
            prompt.append("""
                    List filled cells from this cropped index in region.cells. purpose must
                    be exactly "Required", "Scratch", or "Orphan".
                    """);
        }
        prompt.append("Prompt version: ").append(expectedVersion).append('\n');
        prompt.append("Report version: ").append(EnrichmentReport.VERSION).append('\n');
        prompt.append("File name: ").append(fileName).append('\n');
        prompt.append("Sheet: ").append(input.sheetName()).append('\n');
        prompt.append("Model id: ").append(config.modelId()).append('\n');
        prompt.append("Type menu: ").append(String.join(", ", input.typeMenu())).append('\n');
        prompt.append("Leftover filled cells (must be covered):\n");
        prompt.append(String.join(", ", window.leftovers())).append('\n');
        prompt.append("Nearby regions to replace or expand:\n");
        prompt.append(MAPPER.writeValueAsString(nearby)).append('\n');
        prompt.append("Cropped sparse grid:\n");
        prompt.append(view.columnHeaderLine()).append('\n');
        prompt.append(croppedGrid);
        prompt.append("Cropped cell index (NDJSON):\n");
        prompt.append(croppedIndex);
        return prompt.toString();
    }

    private record NearbyRegionJson(
            String id,
            String bounds,
            String displayName,
            String type,
            String purpose) {}

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
}
