package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import java.util.List;

final class EnrichmentReportValidator {

    private EnrichmentReportValidator() {}

    static void validate(EnrichmentReport report) throws EnrichmentReportFormatException {
        requireText(report.sheetName(), "sheetName");
        requireText(report.redactedInputPath(), "redactedInputPath");
        requireText(report.unhiddenTempPath(), "unhiddenTempPath");
        requireText(report.modelId(), "modelId");
        requireText(report.promptVersion(), "promptVersion");

        if (report.typeMenu() == null) {
            throw required("typeMenu");
        }
        requireStringList(report.typeMenu().types(), "typeMenu.types");
        requireStringList(report.typeMenu().newTypesAdded(), "typeMenu.newTypesAdded");

        List<Region> regions = requireList(report.regions(), "regions");
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            Region region = regions.get(regionIndex);
            String field = "regions[" + regionIndex + "]";
            if (region == null) {
                throw required(field);
            }
            requireText(region.id(), field + ".id");
            requireText(region.bounds(), field + ".bounds");
            requireText(region.displayName(), field + ".displayName");
            requireText(region.type(), field + ".type");
            if (region.purpose() == null) {
                throw required(field + ".purpose");
            }
            List<Cell> cells = requireList(region.cells(), field + ".cells");
            requireStringList(region.notes(), field + ".notes");
            for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                Cell cell = cells.get(cellIndex);
                String cellField = field + ".cells[" + cellIndex + "]";
                if (cell == null) {
                    throw required(cellField);
                }
                requireText(cell.address(), cellField + ".address");
                if (cell.role() == null) {
                    throw required(cellField + ".role");
                }
                if (cell.role() == CellRole.AMOUNT) {
                    if (region.purpose() != RegionPurpose.REQUIRED) {
                        throw new EnrichmentReportFormatException(
                                "amount cell " + cell.address() + " must belong to a Required region");
                    }
                    requireText(cell.rowLabel(), "amount cell " + cell.address() + " rowLabel");
                    requireText(cell.columnLabel(), "amount cell " + cell.address() + " columnLabel");
                } else if (cell != null && hasLabels(cell)) {
                    throw new EnrichmentReportFormatException(
                            "structural cell " + cell.address() + " must not carry labels");
                }
            }
        }

        List<Problem> problems = requireList(report.problems(), "problems");
        for (int problemIndex = 0; problemIndex < problems.size(); problemIndex++) {
            Problem problem = problems.get(problemIndex);
            String field = "problems[" + problemIndex + "]";
            if (problem == null) {
                throw required(field);
            }
            if (problem.code() == null) {
                throw required(field + ".code");
            }
            requireText(problem.message(), field + ".message");
            requireStringList(problem.cells(), field + ".cells");
            requireStringList(problem.regionIds(), field + ".regionIds");
        }
    }

    private static boolean hasLabels(Cell cell) {
        return cell.rowLabel() != null
                || cell.parentRowLabel() != null
                || cell.columnLabel() != null
                || cell.parentColumnLabel() != null;
    }

    private static void requireText(String value, String field) throws EnrichmentReportFormatException {
        if (value == null || value.isBlank()) {
            throw required(field);
        }
    }

    private static <T> List<T> requireList(List<T> values, String field)
            throws EnrichmentReportFormatException {
        if (values == null) {
            throw required(field);
        }
        return values;
    }

    private static void requireStringList(List<String> values, String field)
            throws EnrichmentReportFormatException {
        List<String> requiredValues = requireList(values, field);
        for (int i = 0; i < requiredValues.size(); i++) {
            requireText(requiredValues.get(i), field + "[" + i + "]");
        }
    }

    private static EnrichmentReportFormatException required(String field) {
        return new EnrichmentReportFormatException(field + " is required");
    }
}
