package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EnrichmentReportValidator {

    private static final Pattern CELL_ADDRESS =
            Pattern.compile("^([A-Z]{1,3})([1-9][0-9]{0,6})$");
    private static final int MAX_COLUMN = 16_384;
    private static final int MAX_ROW = 1_048_576;

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
        List<String> types = requireStringList(report.typeMenu().types(), "typeMenu.types");
        List<String> newTypesAdded = requireStringList(
                report.typeMenu().newTypesAdded(), "typeMenu.newTypesAdded");
        for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
            if ("Other".equalsIgnoreCase(types.get(typeIndex))) {
                throw new EnrichmentReportFormatException(
                        "typeMenu.types[" + typeIndex + "] must not be Other");
            }
        }
        for (int typeIndex = 0; typeIndex < newTypesAdded.size(); typeIndex++) {
            if (!types.contains(newTypesAdded.get(typeIndex))) {
                throw new EnrichmentReportFormatException(
                        "typeMenu.newTypesAdded[" + typeIndex + "] must exist in typeMenu.types");
            }
        }

        List<Region> regions = requireList(report.regions(), "regions");
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            Region region = regions.get(regionIndex);
            String field = "regions[" + regionIndex + "]";
            if (region == null) {
                throw required(field);
            }
            requireText(region.id(), field + ".id");
            requireBounds(region.bounds(), field + ".bounds");
            requireText(region.displayName(), field + ".displayName");
            requireText(region.type(), field + ".type");
            if (!types.contains(region.type())) {
                throw new EnrichmentReportFormatException(
                        field + ".type must exist in typeMenu.types");
            }
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
                requireAddress(cell.address(), cellField + ".address");
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

    private static List<String> requireStringList(List<String> values, String field)
            throws EnrichmentReportFormatException {
        List<String> requiredValues = requireList(values, field);
        for (int i = 0; i < requiredValues.size(); i++) {
            requireText(requiredValues.get(i), field + "[" + i + "]");
        }
        return requiredValues;
    }

    private static void requireAddress(String address, String field)
            throws EnrichmentReportFormatException {
        requireText(address, field);
        if (parseAddress(address) == null) {
            throw new EnrichmentReportFormatException(field + " must be a valid cell address");
        }
    }

    private static void requireBounds(String bounds, String field)
            throws EnrichmentReportFormatException {
        requireText(bounds, field);
        String[] endpoints = bounds.split(":", -1);
        if (endpoints.length != 2) {
            throw new EnrichmentReportFormatException(field + " must be a rectangular range");
        }
        int[] first = parseAddress(endpoints[0]);
        int[] last = parseAddress(endpoints[1]);
        if (first == null || last == null || first[0] > last[0] || first[1] > last[1]) {
            throw new EnrichmentReportFormatException(field + " must be a valid rectangular range");
        }
    }

    private static int[] parseAddress(String address) {
        Matcher matcher = CELL_ADDRESS.matcher(address);
        if (!matcher.matches()) {
            return null;
        }
        int column = 0;
        for (char letter : matcher.group(1).toCharArray()) {
            column = column * 26 + letter - 'A' + 1;
        }
        int row = Integer.parseInt(matcher.group(2));
        return column <= MAX_COLUMN && row <= MAX_ROW ? new int[] {column, row} : null;
    }

    private static EnrichmentReportFormatException required(String field) {
        return new EnrichmentReportFormatException(field + " is required");
    }
}
