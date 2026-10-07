package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Infers financial project industry from worksheet structure and cell contents.
 *
 * Analyzes:
 * - Worksheet names (Generation, Capex, O&M, Revenue, Financials indicate energy/infrastructure)
 * - Key cell texts (KUSUM, PPA, MW capacity indicate renewable energy)
 * - Project structure patterns (EPC, construction period, debt service)
 */
public final class IndustryDetector {

    private final WorkspaceRepository repo;

    // Industry pattern matchers
    private static final Pattern SOLAR_INDICATORS = Pattern.compile(
            "(?i)(kusum|solar|pv|panel|dc-?mw|ac-?mw|capacity.*mw)");
    private static final Pattern RENEWABLE_INDICATORS = Pattern.compile(
            "(?i)(generation|ppa|power purchase|wind|hydro|renewable)");
    private static final Pattern INFRASTRUCTURE_INDICATORS = Pattern.compile(
            "(?i)(capex|epc|debt.*service|dscr|irr|construction|operation)");
    private static final Pattern ENERGY_SHEETS = Pattern.compile(
            "(?i)(generation|capex|o&?m|revenue|financials)");

    private static final String INDUSTRY_SOLAR = "solar_power";
    private static final String INDUSTRY_RENEWABLE = "renewable_energy";
    private static final String INDUSTRY_INFRASTRUCTURE = "infrastructure";

    public IndustryDetector(WorkspaceRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    /**
     * Detect and persist industry for a parse run based on worksheet and cell analysis.
     * If industry is already confirmed, do nothing.
     *
     * @param parseRunId parse run to analyze
     * @param mandateId mandate to update
     * @throws DiscoverException if detection fails
     */
    public void detectAndPersist(long parseRunId, long mandateId) throws DiscoverException {
        try {
            // Skip if industry already confirmed
            var existing = repo.selectMandateIndustry(mandateId);
            if (existing != null && existing.confirmed()) {
                return;
            }

            String detected = detectIndustry(parseRunId);
            if (detected != null && !detected.equals("unspecified")) {
                repo.upsertMandateIndustry(mandateId, detected, false, true);
            }
        } catch (SQLException e) {
            throw new DiscoverException("failed to detect industry: " + e.getMessage(), e);
        }
    }

    /**
     * Analyze worksheets and cells to infer industry.
     * Returns one of: "solar_power", "renewable_energy", "infrastructure", or null.
     */
    private String detectIndustry(long parseRunId) throws SQLException {
        var worksheets = repo.selectWorksheetsForParseRun(parseRunId);

        ScoringContext score = new ScoringContext();

        // Analyze worksheet names
        for (var ws : worksheets) {
            scoreSheetName(ws.sheetName(), score);
        }

        // Sample key cells for content clues
        for (var ws : worksheets) {
            scoreSheetCells(ws.worksheetId(), score);
        }

        return resolveIndustry(score);
    }

    private void scoreSheetName(String name, ScoringContext score) {
        if (name == null) return;

        if (ENERGY_SHEETS.matcher(name).find()) {
            score.energySheets++;
        }
        if (RENEWABLE_INDICATORS.matcher(name).find()) {
            score.renewableHits++;
        }
        if (SOLAR_INDICATORS.matcher(name).find()) {
            score.solarHits++;
        }
    }

    private void scoreSheetCells(long worksheetId, ScoringContext score) throws SQLException {
        // Sample first 100 cells per worksheet to avoid overwhelming analysis
        List<String> cellTexts = repo.selectCellTextsForWorksheet(worksheetId, 100);

        for (String text : cellTexts) {
            if (text == null || text.isBlank()) {
                continue;
            }

            if (SOLAR_INDICATORS.matcher(text).find()) {
                score.solarHits += 2; // Weighted higher for direct indicators
            }
            if (RENEWABLE_INDICATORS.matcher(text).find()) {
                score.renewableHits++;
            }
            if (INFRASTRUCTURE_INDICATORS.matcher(text).find()) {
                score.infrastructureHits++;
            }
        }
    }

    private String resolveIndustry(ScoringContext score) {
        // High confidence solar indicators (KUSUM, DC-MW, etc.)
        if (score.solarHits >= 2) {
            return INDUSTRY_SOLAR;
        }

        // Renewable energy (generation, PPA, etc.) + multiple energy sheets
        if (score.renewableHits >= 1 && score.energySheets >= 3) {
            return INDUSTRY_RENEWABLE;
        }

        // Infrastructure pattern (capex, epc, debt service, etc.)
        if (score.infrastructureHits >= 2 && score.energySheets >= 2) {
            return INDUSTRY_INFRASTRUCTURE;
        }

        return null;
    }

    private static class ScoringContext {
        int solarHits = 0;
        int renewableHits = 0;
        int infrastructureHits = 0;
        int energySheets = 0;
    }
}
