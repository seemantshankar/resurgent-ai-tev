package com.resurgent.tev.parser.enrichment;

import java.nio.file.Path;
import java.util.List;

/** Result of a completed one-tab enrichment pipeline. */
public record EnrichSummary(
        EnrichmentReport report,
        Path redactedPath,
        boolean autoIngested) {

    public Path defaultReportPath(Path outputDirectory) {
        String stem = report.fileName().replaceFirst("(?i)\\.xlsx$", "");
        return outputDirectory.resolve(stem + "-enrichment-report.json");
    }

    public List<String> typesUsed() {
        return report.regions().stream()
                .map(EnrichmentReport.Region::type)
                .distinct()
                .toList();
    }
}
