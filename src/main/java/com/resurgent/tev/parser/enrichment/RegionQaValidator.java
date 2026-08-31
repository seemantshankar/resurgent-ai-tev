package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/** Records partition and reclassification violations without changing regions. */
public final class RegionQaValidator {

    public EnrichmentReport validate(WorksheetSnapshot sheet, EnrichmentReport report) {
        List<Problem> problems = new ArrayList<>();
        boolean regionsOnly = LlmEnrichmentAdapter.isRegionsOnly(report.promptVersion());
        report.regions().forEach(region -> region.cells().forEach(cell -> {
            if (!sheet.filledCells().contains(cell.address())) {
                problems.add(new Problem(
                        EnrichmentReport.ProblemCode.BLANK_CELL_IN_REPORT,
                        "Cell " + cell.address() + " is blank and must not appear in region "
                                + region.id(),
                        List.of(cell.address()),
                        List.of(region.id())));
            }
        }));
        sheet.filledCells().stream().sorted(Comparator.naturalOrder()).forEach(cell -> {
            List<Region> assignments = containingRegions(cell, report.regions());
            if (assignments.isEmpty()) {
                problems.add(new Problem(
                        EnrichmentReport.ProblemCode.UNASSIGNED_CELL,
                        "Filled cell " + cell + " is outside every region",
                        List.of(cell),
                        List.of()));
            } else if (assignments.size() > 1) {
                problems.add(new Problem(
                        EnrichmentReport.ProblemCode.OVERLAP,
                        "Filled cell " + cell + " is assigned to multiple regions",
                        List.of(cell),
                        assignments.stream().map(Region::id).toList()));
            } else if (!regionsOnly && assignments.getFirst().cells().stream()
                    .noneMatch(regionCell -> regionCell.address().equals(cell))) {
                Region assignment = assignments.getFirst();
                problems.add(new Problem(
                        EnrichmentReport.ProblemCode.UNASSIGNED_CELL,
                        "Filled cell " + cell + " has no cell role in region " + assignment.id(),
                        List.of(cell),
                        List.of(assignment.id())));
            }
        });
        sheet.formulaReferences().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(formula -> {
                    List<Region> requiredSources = containingRegions(
                                    formula.getKey(), report.regions()).stream()
                            .filter(region -> region.purpose() == RegionPurpose.REQUIRED)
                            .toList();
                    formula.getValue().stream().sorted().forEach(reference ->
                            addReclassificationProblems(
                                    problems,
                                    formula.getKey(),
                                    reference,
                                    requiredSources,
                                    report.regions()));
                });

        if (problems.isEmpty()) {
            return report;
        }
        return copyWithProblems(report, problems);
    }

    private static void addReclassificationProblems(
            List<Problem> problems,
            String formulaCell,
            String referencedCell,
            List<Region> requiredSources,
            List<Region> regions) {
        List<Region> invalidTargets = containingRegions(referencedCell, regions).stream()
                .filter(region -> region.purpose() == RegionPurpose.SCRATCH
                        || region.purpose() == RegionPurpose.ORPHAN)
                .toList();
        for (Region source : requiredSources) {
            for (Region target : invalidTargets) {
                problems.add(new Problem(
                        EnrichmentReport.ProblemCode.SCRATCH_REFERENCED_BY_REQUIRED,
                        "Required region " + source.id() + " formula " + formulaCell
                                + " references " + target.purpose().jsonValue()
                                + " region " + target.id(),
                        List.of(formulaCell, referencedCell),
                        List.of(source.id(), target.id())));
            }
        }
    }

    private static List<Region> containingRegions(String address, List<Region> regions) {
        CellReference cell = new CellReference(address);
        return regions.stream()
                .filter(region -> CellRangeAddress.valueOf(region.bounds())
                        .isInRange(cell.getRow(), cell.getCol()))
                .toList();
    }

    private static EnrichmentReport copyWithProblems(
            EnrichmentReport report, List<Problem> addedProblems) {
        List<Problem> problems = new java.util.ArrayList<>(report.problems());
        problems.addAll(addedProblems);
        return new EnrichmentReport(
                report.version(),
                report.fileName(),
                report.sheetName(),
                report.redactedInputPath(),
                report.unhiddenTempPath(),
                report.modelId(),
                report.promptVersion(),
                report.typeMenu(),
                report.regions(),
                problems);
    }
}
