package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON read/write boundary for the enrichment report v1 contract. */
public final class EnrichmentReportJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EnrichmentReportJson() {}

    public static EnrichmentReport read(Path path) throws IOException {
        return fromJson(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static EnrichmentReport fromJson(String json) throws IOException {
        return fromModelContent(json);
    }

    static EnrichmentReport fromModelContent(String content) throws IOException {
        final EnrichmentReport report;
        String json = EnrichmentModelContentNormalizer.normalize(content);
        try {
            report = normalizeBounds(MAPPER.readValue(json, EnrichmentReport.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EnrichmentReportFormatException(
                    "invalid enrichment report JSON: " + rootMessage(e), e);
        }
        validate(report);
        return report;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? error.toString() : message;
    }

    private static EnrichmentReport normalizeBounds(EnrichmentReport report) {
        if (report == null || report.regions() == null) {
            return report;
        }
        java.util.List<EnrichmentReport.Region> regions = report.regions().stream()
                .map(region -> new EnrichmentReport.Region(
                        region.id(),
                        RegionBounds.normalize(region.bounds()),
                        region.displayName(),
                        region.type(),
                        region.purpose(),
                        region.cells(),
                        region.notes()))
                .toList();
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

    private static void validate(EnrichmentReport report)
            throws EnrichmentReportFormatException {
        if (report == null) {
            throw new EnrichmentReportFormatException("report is required");
        }
        if (report.fileName() == null || report.fileName().isBlank()) {
            throw new EnrichmentReportFormatException("fileName is required");
        }
        if (!EnrichmentReport.VERSION.equals(report.version())) {
            throw new EnrichmentReportFormatException(
                    "version must be " + EnrichmentReport.VERSION);
        }
        EnrichmentReportValidator.validate(report);
    }

    public static void write(Path path, EnrichmentReport report) throws IOException {
        Files.writeString(path, toJson(report) + "\n", StandardCharsets.UTF_8);
    }

    public static String toJson(EnrichmentReport report) throws IOException {
        validate(report);
        return MAPPER.writeValueAsString(report);
    }
}
