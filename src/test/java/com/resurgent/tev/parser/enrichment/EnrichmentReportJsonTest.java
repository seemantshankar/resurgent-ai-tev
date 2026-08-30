package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.ProblemCode;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnrichmentReportJsonTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsCivilCostReportWithBusinessProblems() throws Exception {
        EnrichmentReport report = civilCostReport();
        Path reportPath = tempDir.resolve("Project-FM-enrichment-report.json");

        EnrichmentReportJson.write(reportPath, report);

        assertThat(EnrichmentReportJson.read(reportPath)).isEqualTo(report);
        assertThat(reportPath)
                .content()
                .contains(
                        "\"version\" : \"enrichment-report-v1\"",
                        "\"role\" : \"columnHeader\"",
                        "\"code\" : \"scratch_referenced_by_required\"")
                .doesNotContain("\"rowLabel\" : null");
    }

    @Test
    void rejectsMissingRequiredMetadataAtParseTime() {
        String json = """
                {
                  "version": "enrichment-report-v1",
                  "sheetName": "Project Cost",
                  "redactedInputPath": "/data/Project-FM-redacted.xlsx",
                  "unhiddenTempPath": "/tmp/Project-FM-unhidden.xlsx",
                  "modelId": "gpt-4.1",
                  "promptVersion": "enrich-v1",
                  "typeMenu": { "types": [], "newTypesAdded": [] },
                  "regions": [],
                  "problems": []
                }
                """;

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(json))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("fileName");
    }

    @Test
    void rejectsUnsupportedReportVersionAtParseTime() throws Exception {
        String json = EnrichmentReportJson.toJson(civilCostReport())
                .replace(EnrichmentReport.VERSION, "enrichment-report-v2");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(json))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("version")
                .hasMessageContaining(EnrichmentReport.VERSION);
    }

    @Test
    void rejectsAmountCellWithoutRequiredLabelsAtParseTime() throws Exception {
        String json = EnrichmentReportJson.toJson(civilCostReport())
                .replace("\"rowLabel\" : \"Structure\"", "\"rowLabel\" : null");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(json))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("B4")
                .hasMessageContaining("rowLabel");
    }

    @Test
    void rejectsAmountCellOutsideRequiredRegionAtParseTime() throws Exception {
        EnrichmentReport original = civilCostReport();
        Region orphanWithAmount = new Region(
                "r2",
                "A8:A8",
                "Notes: GST extra",
                "Orphan",
                RegionPurpose.ORPHAN,
                List.of(new Cell("A8", CellRole.AMOUNT, "Note", null, "Value", null)),
                List.of());
        EnrichmentReport invalid = withRegions(
                original, List.of(original.regions().getFirst(), orphanWithAmount));

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(EnrichmentReportJson.toJson(invalid)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("amount cell A8")
                .hasMessageContaining("Required");
    }

    @Test
    void rejectsLabelsOnStructuralCellAtParseTime() throws Exception {
        String json = EnrichmentReportJson.toJson(civilCostReport())
                .replace(
                        "\"role\" : \"title\"",
                        "\"role\" : \"title\", \"rowLabel\" : \"invented\"");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(json))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("A1")
                .hasMessageContaining("labels");
    }

    @Test
    void rejectsProblemWithoutRequiredEntryShapeAtParseTime() throws Exception {
        EnrichmentReport original = civilCostReport();
        Problem malformed = new Problem(
                ProblemCode.OVERLAP,
                "Cells B5 assigned to both r1 and r3",
                List.of("B5"),
                null);
        EnrichmentReport invalid = withProblems(original, List.of(malformed));

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(EnrichmentReportJson.toJson(invalid)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("problems[0].regionIds");
    }

    @Test
    void rejectsMissingRequiredReportCollectionsAtParseTime() throws Exception {
        EnrichmentReport invalid = withProblems(civilCostReport(), null);

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(EnrichmentReportJson.toJson(invalid)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("problems");
    }

    @Test
    void rejectsMalformedRegionAndCellEntriesAtParseTime() throws Exception {
        EnrichmentReport original = civilCostReport();
        Region malformedRegion = new Region(
                "r1",
                "A1:D6",
                null,
                "Civil Cost",
                RegionPurpose.REQUIRED,
                List.of(new Cell("A1", null, null, null, null, null)),
                List.of());
        EnrichmentReport invalid = withRegions(original, List.of(malformedRegion));

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(EnrichmentReportJson.toJson(invalid)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].displayName");
    }

    @Test
    void rejectsProblemWithoutCodeMessageOrCellsAtParseTime() throws Exception {
        EnrichmentReport original = civilCostReport();

        assertInvalidProblem(
                original,
                new Problem(null, "message", List.of("B5"), List.of("r1")),
                "problems[0].code");
        assertInvalidProblem(
                original,
                new Problem(ProblemCode.OVERLAP, null, List.of("B5"), List.of("r1")),
                "problems[0].message");
        assertInvalidProblem(
                original,
                new Problem(ProblemCode.OVERLAP, "message", null, List.of("r1")),
                "problems[0].cells");
    }

    @Test
    void rejectsInvalidReportBeforeWriting() {
        EnrichmentReport original = civilCostReport();
        EnrichmentReport invalid = new EnrichmentReport(
                "enrichment-report-v2",
                original.fileName(),
                original.sheetName(),
                original.redactedInputPath(),
                original.unhiddenTempPath(),
                original.modelId(),
                original.promptVersion(),
                original.typeMenu(),
                original.regions(),
                original.problems());
        Path output = tempDir.resolve("invalid.json");

        assertThatThrownBy(() -> EnrichmentReportJson.write(output, invalid))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("version");
        assertThat(output).doesNotExist();
    }

    private static void assertInvalidProblem(
            EnrichmentReport original, Problem problem, String expectedField) throws Exception {
        EnrichmentReport invalid = withProblems(original, List.of(problem));
        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(EnrichmentReportJson.toJson(invalid)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining(expectedField);
    }

    private static EnrichmentReport withRegions(
            EnrichmentReport report, List<Region> regions) {
        return new EnrichmentReport(
                report.version(),
                report.fileName(),
                report.sheetName(),
                report.redactedInputPath(),
                report.unhiddenTempPath(),
                report.modelId(),
                report.promptVersion(),
                report.typeMenu(),
                regions,
                report.problems());
    }

    private static EnrichmentReport withProblems(
            EnrichmentReport report, List<Problem> problems) {
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

    private static EnrichmentReport civilCostReport() {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "Project-FM.xlsx",
                "Project Cost",
                "/data/client/redacted/Project-FM-redacted.xlsx",
                "/tmp/Project-FM-unhidden-abc123.xlsx",
                "gpt-4.1",
                "enrich-v1",
                new TypeMenu(
                        List.of("Project Cost", "Civil Cost", "Orphan"),
                        List.of()),
                List.of(
                        new Region(
                                "r1",
                                "A1:D6",
                                "Civil Cost Breakup as per Quotation dt 12.04.24",
                                "Civil Cost",
                                RegionPurpose.REQUIRED,
                                List.of(
                                        new Cell("A1", CellRole.TITLE, null, null, null, null),
                                        new Cell("A2", CellRole.ANNOTATION, null, null, null, null),
                                        new Cell("A3", CellRole.COLUMN_HEADER, null, null, null, null),
                                        new Cell("A4", CellRole.ROW_HEADER, null, null, null, null),
                                        new Cell(
                                                "B4",
                                                CellRole.AMOUNT,
                                                "Structure",
                                                null,
                                                "Year 1",
                                                null),
                                        new Cell(
                                                "D6",
                                                CellRole.AMOUNT,
                                                "Total",
                                                null,
                                                "Year 1",
                                                null)),
                                List.of()),
                        new Region(
                                "r2",
                                "A8:A8",
                                "Notes: GST extra",
                                "Orphan",
                                RegionPurpose.ORPHAN,
                                List.of(new Cell(
                                        "A8", CellRole.ANNOTATION, null, null, null, null)),
                                List.of())),
                List.of(
                        new Problem(
                                ProblemCode.OVERLAP,
                                "Cells B5 assigned to both r1 and r3",
                                List.of("B5"),
                                List.of("r1", "r3")),
                        new Problem(
                                ProblemCode.SCRATCH_REFERENCED_BY_REQUIRED,
                                "Region r4 (Scratch) is referenced by Required region r1 formula at D6",
                                List.of("D6"),
                                List.of("r1", "r4")),
                        new Problem(
                                ProblemCode.UNASSIGNED_CELL,
                                "Filled cell C10 not in any region",
                                List.of("C10"),
                                List.of())));
    }
}
