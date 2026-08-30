package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** Versioned, inspectable enrichment proposal for one redacted workbook sheet. */
public record EnrichmentReport(
        String version,
        String fileName,
        String sheetName,
        String redactedInputPath,
        String unhiddenTempPath,
        String modelId,
        String promptVersion,
        TypeMenu typeMenu,
        List<Region> regions,
        List<Problem> problems) {

    public static final String VERSION = "enrichment-report-v1";

    public EnrichmentReport {
        regions = immutable(regions);
        problems = immutable(problems);
    }

    public record TypeMenu(List<String> types, List<String> newTypesAdded) {
        public TypeMenu {
            types = immutable(types);
            newTypesAdded = immutable(newTypesAdded);
        }
    }

    public record Region(
            String id,
            String bounds,
            String displayName,
            String type,
            RegionPurpose purpose,
            List<Cell> cells,
            List<String> notes) {

        public Region {
            cells = immutable(cells);
            notes = immutable(notes);
        }
    }

    public record Cell(
            String address,
            CellRole role,
            String rowLabel,
            String parentRowLabel,
            String columnLabel,
            String parentColumnLabel) {}

    public record Problem(
            ProblemCode code,
            String message,
            List<String> cells,
            List<String> regionIds) {

        public Problem {
            cells = immutable(cells);
            regionIds = immutable(regionIds);
        }
    }

    public enum RegionPurpose {
        REQUIRED("Required"),
        SCRATCH("Scratch"),
        ORPHAN("Orphan");

        private final String jsonValue;

        RegionPurpose(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        @JsonCreator
        public static RegionPurpose fromJson(String value) {
            return enumFromJson(RegionPurpose.values(), value);
        }
    }

    public enum CellRole {
        TITLE("title"),
        ANNOTATION("annotation"),
        ROW_HEADER("rowHeader"),
        COLUMN_HEADER("columnHeader"),
        AMOUNT("amount");

        private final String jsonValue;

        CellRole(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        @JsonCreator
        public static CellRole fromJson(String value) {
            return enumFromJson(CellRole.values(), value);
        }
    }

    public enum ProblemCode {
        OVERLAP("overlap"),
        UNASSIGNED_CELL("unassigned_cell"),
        SCRATCH_REFERENCED_BY_REQUIRED("scratch_referenced_by_required");

        private final String jsonValue;

        ProblemCode(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        @JsonCreator
        public static ProblemCode fromJson(String value) {
            return enumFromJson(ProblemCode.values(), value);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static <E extends Enum<E>> E enumFromJson(E[] values, String value) {
        for (E candidate : values) {
            String jsonValue = switch (candidate) {
                case RegionPurpose purpose -> purpose.jsonValue();
                case CellRole role -> role.jsonValue();
                case ProblemCode code -> code.jsonValue();
                default -> throw new IllegalStateException("unsupported enrichment enum");
            };
            if (jsonValue.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown value: " + value);
    }
}
