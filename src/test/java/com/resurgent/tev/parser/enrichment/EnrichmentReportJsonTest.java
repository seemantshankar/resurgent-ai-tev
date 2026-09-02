package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
    void parsesMarkdownWrappedModelJson() throws Exception {
        EnrichmentReport report = civilCostReport();
        String wrapped = """
                ```json
                %s
                ```
                """.formatted(EnrichmentReportJson.toJson(report));

        assertThat(EnrichmentReportJson.fromModelContent(wrapped)).isEqualTo(report);
    }

    @Test
    void normalizesSingleCellBoundsToRectangularRange() throws Exception {
        String json = """
                {
                  "version": "enrichment-report-v1",
                  "fileName": "fixture.xlsx",
                  "sheetName": "depreciation",
                  "redactedInputPath": "/tmp/redacted.xlsx",
                  "unhiddenTempPath": "/tmp/unhidden.xlsx",
                  "modelId": "fixture-enrichment-model",
                  "promptVersion": "enrichment-v2-regions-only",
                  "typeMenu": { "types": ["Depreciation"], "newTypesAdded": [] },
                  "regions": [
                    {
                      "id": "orphan-f2",
                      "bounds": "F2",
                      "displayName": "Stray",
                      "type": "Depreciation",
                      "purpose": "Orphan",
                      "cells": [],
                      "notes": []
                    }
                  ],
                  "problems": []
                }
                """;

        EnrichmentReport parsed = EnrichmentReportJson.fromJson(json);

        assertThat(parsed.regions().getFirst().bounds()).isEqualTo("F2:F2");
    }

    @Test
    void rejectsMissingRequiredMetadataAtParseTime() {
        String json = """
                {
                  "version": "enrichment-report-v1",
                  "sheetName": "Project Cost",
                  "redactedInputPath": "/data/Project-FM-redacted.xlsx",
                  "unhiddenTempPath": "/tmp/Project-FM-unhidden.xlsx",
                  "modelId": "fixture-enrichment-model",
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
    void allowsUnlabelledAmountCellOutsideRequiredRegion() throws Exception {
        ObjectNode report = reportJsonTree();
        ObjectNode orphanCell = (ObjectNode) report.at("/regions/1/cells/0");
        orphanCell.put("role", "amount");

        EnrichmentReport parsed =
                EnrichmentReportJson.fromJson(JSON.writeValueAsString(report));

        assertThat(parsed.regions().get(1).cells().getFirst().role())
                .isEqualTo(CellRole.AMOUNT);
        assertThat(parsed.regions().get(1).cells().getFirst().rowLabel()).isNull();
    }

    @Test
    void rejectsLabelsOnAmountCellOutsideRequiredRegion() throws Exception {
        ObjectNode report = reportJsonTree();
        ObjectNode orphanCell = (ObjectNode) report.at("/regions/1/cells/0");
        orphanCell.put("role", "amount");
        orphanCell.put("rowLabel", "Note");
        orphanCell.put("columnLabel", "Value");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("amount cell A8")
                .hasMessageContaining("must not carry labels");
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
        ObjectNode report = reportJsonTree();
        ((ObjectNode) report.at("/problems/0")).remove("regionIds");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("problems[0].regionIds");
    }

    @Test
    void rejectsMissingRequiredReportCollectionsAtParseTime() throws Exception {
        ObjectNode report = reportJsonTree();
        report.remove("problems");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("problems");
    }

    @Test
    void rejectsMalformedRegionAndCellEntriesAtParseTime() throws Exception {
        ObjectNode report = reportJsonTree();
        ((ObjectNode) report.at("/regions/0")).remove("displayName");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].displayName");
    }

    @Test
    void rejectsProblemWithoutCodeMessageOrCellsAtParseTime() throws Exception {
        assertInvalidProblemField("code");
        assertInvalidProblemField("message");
        assertInvalidProblemField("cells");
    }

    @Test
    void rejectsMalformedBoundsAndCellAddressesAtParseTime() throws Exception {
        ObjectNode malformedBounds = reportJsonTree();
        ((ObjectNode) malformedBounds.at("/regions/0")).put("bounds", "A1-D6");
        assertThatThrownBy(() ->
                        EnrichmentReportJson.fromJson(JSON.writeValueAsString(malformedBounds)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].bounds");

        ObjectNode malformedAddress = reportJsonTree();
        ((ObjectNode) malformedAddress.at("/regions/0/cells/0")).put("address", "not-a-cell");
        assertThatThrownBy(() ->
                        EnrichmentReportJson.fromJson(JSON.writeValueAsString(malformedAddress)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].cells[0].address");
    }

    @Test
    void rejectsCellOutsideItsRegionBoundsAtParseTime() throws Exception {
        ObjectNode report = reportJsonTree();
        ((ObjectNode) report.at("/regions/0/cells/0")).put("address", "Z99");

        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].cells[0].address")
                .hasMessageContaining("bounds");
    }

    @Test
    void rejectsTypesOutsideTheMenuAndInconsistentNewTypesAtParseTime() throws Exception {
        ObjectNode unknownRegionType = reportJsonTree();
        ((ObjectNode) unknownRegionType.at("/regions/0")).put("type", "Unknown Type");
        assertThatThrownBy(() ->
                        EnrichmentReportJson.fromJson(JSON.writeValueAsString(unknownRegionType)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("regions[0].type")
                .hasMessageContaining("typeMenu.types");

        ObjectNode unknownNewType = reportJsonTree();
        ((ArrayNode) unknownNewType.at("/typeMenu/newTypesAdded")).add("Unknown Type");
        assertThatThrownBy(() ->
                        EnrichmentReportJson.fromJson(JSON.writeValueAsString(unknownNewType)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("typeMenu.newTypesAdded[0]")
                .hasMessageContaining("typeMenu.types");
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

    private static void assertInvalidProblemField(String field) throws Exception {
        ObjectNode report = reportJsonTree();
        ((ObjectNode) report.at("/problems/0")).remove(field);
        assertThatThrownBy(() -> EnrichmentReportJson.fromJson(JSON.writeValueAsString(report)))
                .isInstanceOf(EnrichmentReportFormatException.class)
                .hasMessageContaining("problems[0]." + field);
    }

    private static ObjectNode reportJsonTree() throws Exception {
        return (ObjectNode) JSON.readTree(EnrichmentReportJson.toJson(civilCostReport()));
    }

    private static EnrichmentReport civilCostReport() {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "Project-FM.xlsx",
                "Project Cost",
                "/data/client/redacted/Project-FM-redacted.xlsx",
                "/tmp/Project-FM-unhidden-abc123.xlsx",
                "fixture-enrichment-model",
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
                                        new Cell("B3", CellRole.COLUMN_HEADER, null, null, null, null),
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
