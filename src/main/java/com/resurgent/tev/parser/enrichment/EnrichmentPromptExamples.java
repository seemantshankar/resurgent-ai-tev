package com.resurgent.tev.parser.enrichment;

/** Compact JSON examples embedded in enrichment prompts. */
final class EnrichmentPromptExamples {

    private EnrichmentPromptExamples() {}

    static String regionsOnly(
            String fileName,
            String sheetName,
            String redactedPath,
            String unhiddenPath,
            String modelId,
            String sampleType,
            String promptVersion) {
        return """
                {
                  "version": "enrichment-report-v1",
                  "fileName": "%s",
                  "sheetName": "%s",
                  "redactedInputPath": "%s",
                  "unhiddenTempPath": "%s",
                  "modelId": "%s",
                  "promptVersion": "%s",
                  "typeMenu": { "types": ["%s"], "newTypesAdded": [] },
                  "regions": [
                    {
                      "id": "main-table",
                      "bounds": "A4:M151",
                      "displayName": "Depreciation Schedule",
                      "type": "%s",
                      "purpose": "Required",
                      "cells": [],
                      "notes": []
                    }
                  ],
                  "problems": []
                }"""
                .formatted(
                        escape(fileName),
                        escape(sheetName),
                        escape(redactedPath),
                        escape(unhiddenPath),
                        escape(modelId),
                        escape(promptVersion),
                        escape(sampleType),
                        escape(sampleType));
    }

    static String full(
            String fileName,
            String sheetName,
            String redactedPath,
            String unhiddenPath,
            String modelId,
            String sampleType,
            String promptVersion) {
        return """
                {
                  "version": "enrichment-report-v1",
                  "fileName": "%s",
                  "sheetName": "%s",
                  "redactedInputPath": "%s",
                  "unhiddenTempPath": "%s",
                  "modelId": "%s",
                  "promptVersion": "%s",
                  "typeMenu": { "types": ["%s"], "newTypesAdded": [] },
                  "regions": [
                    {
                      "id": "civil-cost",
                      "bounds": "A1:D6",
                      "displayName": "Civil Cost Breakup",
                      "type": "%s",
                      "purpose": "Required",
                      "cells": [
                        { "address": "A1", "role": "title" },
                        { "address": "A2", "role": "annotation" },
                        { "address": "B4", "role": "amount", "rowLabel": "Structure", "columnLabel": "Year 1" }
                      ],
                      "notes": []
                    }
                  ],
                  "problems": []
                }"""
                .formatted(
                        escape(fileName),
                        escape(sheetName),
                        escape(redactedPath),
                        escape(unhiddenPath),
                        escape(modelId),
                        escape(promptVersion),
                        escape(sampleType),
                        escape(sampleType));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
