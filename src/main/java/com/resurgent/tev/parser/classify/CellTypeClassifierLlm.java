package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM-based fallback for cell type classification.
 *
 * Used after structural inference (total row rules, column consensus) to classify
 * remaining UNTYPABLE cells. This provides a last-resort classification for cells
 * that could not be deterministically typed through labels, format, formulas, or
 * structural context.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Only classifies cells that remain UNTYPABLE after deterministic rules
 *   <li>Returns type + confidence, not just type
 *   <li>Respects the "nothing incorrect is written" principle — low-confidence results stay untyped
 *   <li>Batches requests for efficiency
 *   <li>Integrates with existing {@link ClassifierLlm} infrastructure
 * </ul>
 *
 * @see ReadingOutcome
 * @see CellReadingInferencer
 * @see ClassifierLlm
 */
public class CellTypeClassifierLlm {
    private static final double MIN_CONFIDENCE = 0.80;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            You are a financial model cell type classifier. Your task is to classify numeric cells in financial models.

            For each cell, determine:
            1. kind: one of {money, quantity, rate, percent, count, ratio}
            2. scale: one of {unit, thousand, lakh, million, crore, billion}
            3. unit: optional unit of measurement (e.g., "sqm", "kg", "" for none)
            4. currency: optional 3-letter currency code (e.g., "INR", "USD", "" for none)
            5. confidence: 0.0-1.0 representing your confidence in this classification

            Return a JSON object with these fields.
            Respect the "nothing incorrect is written" principle: if unsure, return lower confidence.
            """;

    private final WorkspaceRepository repo;
    private final ClassifierLlm llm;
    private final DynamicKindTokens dynamicDict;

    public CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm) {
        this.repo = repo;
        this.llm = llm;
        this.dynamicDict = new DynamicKindTokens();
    }

    /**
     * Classify remaining UNTYPABLE cells using LLM.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with LLM-inferred ones.
     *
     * @param cells all interpretation cells for the parse run
     * @param settled map of cellId → ReadingOutcome, modified in-place
     */
    public void classifyRemaining(List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled) {
        System.err.println("[cell-classifier] Starting classifyRemaining, total cells=" + cells.size());
        System.err.flush();
        List<InterpretationCellView> unclassified = new ArrayList<>();
        Map<Long, InterpretationCellView> cellIndex = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            cellIndex.put(cell.cellId(), cell);
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome != null && ReadingOutcome.UNTYPABLE.equals(outcome.refusal)) {
                if (isNumeric(cell)) {
                    unclassified.add(cell);
                }
            }
        }

        if (unclassified.isEmpty()) {
            System.err.println("[cell-classifier] No unclassified cells found, skipping LLM");
            System.err.flush();
            return;
        }

        // First pass: try to type using KindTokens dictionary (deterministic)
        List<InterpretationCellView> stillUntyped = new ArrayList<>();
        for (InterpretationCellView cell : unclassified) {
            ReadingOutcome outcome = tryDictionaryBasedTyping(cell, cells);  // Use all cells for label context
            if (outcome != null) {
                settled.put(cell.cellId(), outcome);
            } else {
                stillUntyped.add(cell);
            }
        }

        int typedByDictionary = unclassified.size() - stillUntyped.size();
        if (typedByDictionary > 0) {
            System.err.println("[cell-classifier] Typed " + typedByDictionary + " cells via KindTokens dictionary");
        }

        if (stillUntyped.isEmpty()) {
            System.err.println("[cell-classifier] All cells typed by dictionary, skipping LLM");
            System.err.flush();
            return;
        }

        System.err.println("[cell-classifier] Classifying " + stillUntyped.size() + " untyped cells via LLM");
        System.err.flush();

        // Group by worksheet for context coherence
        Map<Long, List<InterpretationCellView>> byWorksheet = new HashMap<>();
        for (InterpretationCellView cell : stillUntyped) {
            byWorksheet.computeIfAbsent(cell.worksheetId(), k -> new ArrayList<>()).add(cell);
        }

        // Batch cells for efficiency: classify 15 cells per LLM call instead of 1
        for (List<InterpretationCellView> worksheetCells : byWorksheet.values()) {
            for (int i = 0; i < worksheetCells.size(); i += 15) {
                int end = Math.min(i + 15, worksheetCells.size());
                List<InterpretationCellView> batch = worksheetCells.subList(i, end);
                classifyBatch(batch, cells, settled);
            }
        }
    }

    /**
     * Get the dynamic dictionary. Must be called after classification to persist learned terms.
     */
    public DynamicKindTokens getDynamicDictionary() {
        return dynamicDict;
    }

    private void classifyBatch(List<InterpretationCellView> batch, List<InterpretationCellView> allCells, Map<Long, ReadingOutcome> settled) {
        List<CellTypeRequest> requests = new ArrayList<>();
        for (InterpretationCellView cell : batch) {
            requests.add(buildCellTypeRequest(cell, allCells, settled));
        }

        try {
            List<CellTypeResponse> responses = classifyBatchCells(requests);
            for (int i = 0; i < batch.size(); i++) {
                CellTypeResponse response = responses.get(i);
                if (response.confidence >= MIN_CONFIDENCE) {
                    InterpretationCellView cell = batch.get(i);
                    CellScale scale = CellScale.fromWire(response.scale);
                    settled.put(cell.cellId(), ReadingOutcome.typed(
                            response.kind, scale, response.unit, response.currency, ReadingOutcome.DERIVED));
                    // Learn new terms for future workbooks
                    String rowLabel = extractRowLabel(cell, allCells);
                    String colLabel = extractColumnLabel(cell, allCells);
                    dynamicDict.learnTerm(response.kind, rowLabel, colLabel);
                }
            }
        } catch (Exception e) {
            System.err.println("[llm-fallback] Failed to classify batch: " + e.getMessage());
            // Fall back to individual classification on batch failure
            for (InterpretationCellView cell : batch) {
                CellTypeRequest request = buildCellTypeRequest(cell, allCells, settled);
                try {
                    CellTypeResponse response = classifyCell(request);
                    if (response.confidence >= MIN_CONFIDENCE) {
                        CellScale scale = CellScale.fromWire(response.scale);
                        settled.put(cell.cellId(), ReadingOutcome.typed(
                                response.kind, scale, response.unit, response.currency, ReadingOutcome.DERIVED));
                        // Learn new terms for future workbooks
                        String rowLabel = extractRowLabel(cell, allCells);
                        String colLabel = extractColumnLabel(cell, allCells);
                        dynamicDict.learnTerm(response.kind, rowLabel, colLabel);
                    }
                } catch (Exception ex) {
                    System.err.println("[llm-fallback] Failed to classify " + cell.coord() + ": " + ex.getMessage());
                }
            }
        }
    }

    private List<CellTypeResponse> classifyBatchCells(List<CellTypeRequest> requests) throws Exception {
        String userMessage = formatBatchUserMessage(requests);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 4096);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        System.err.println("[cell-llm] Batch of " + requests.size() + " cells: LLM responded in " + llmMs + "ms");
        return parseBatchCellTypeResponse(jsonResponse, requests.size());
    }

    private String formatBatchUserMessage(List<CellTypeRequest> requests) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the following ").append(requests.size()).append(" cells:\n\n");

        for (int i = 0; i < requests.size(); i++) {
            CellTypeRequest request = requests.get(i);
            sb.append("Cell ").append(i + 1).append(": ").append(request.coord)
                    .append(" (display: \"").append(request.displayValue).append("\"");
            if (!request.formulaText.isBlank()) {
                sb.append(", formula: \"").append(request.formulaText).append("\"");
            }
            sb.append(")\n");

            if (!request.rowLabel.isBlank()) {
                sb.append("  Row Label: ").append(request.rowLabel).append("\n");
            }
            if (!request.columnLabel.isBlank()) {
                sb.append("  Column Label: ").append(request.columnLabel).append("\n");
            }

            if (!request.neighbors.isEmpty()) {
                sb.append("  Context:");
                for (NeighborCell neighbor : request.neighbors) {
                    sb.append(" ").append(neighbor.direction).append("=").append(neighbor.displayValue)
                            .append("(").append(neighbor.type).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("Return ONLY JSON. Wrap the ").append(requests.size()).append(" classifications in a JSON object with key 'results':\n");
        sb.append("{\n");
        sb.append("  \"results\": [\n");
        sb.append("    {\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95},\n");
        sb.append("    {\"kind\":\"quantity\",\"scale\":\"unit\",\"unit\":\"pieces\",\"currency\":\"\",\"confidence\":0.90}\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private List<CellTypeResponse> parseBatchCellTypeResponse(String jsonResponse, int expectedCount) throws Exception {
        List<CellTypeResponse> responses = new ArrayList<>();
        JsonNode root = MAPPER.readTree(jsonResponse);

        // Handle multiple formats: [...], {"cells": [...]}, {"array": [...]}, or any object containing an array
        JsonNode nodes;
        if (root.isArray()) {
            nodes = root;
        } else if (root.isObject()) {
            // Try common wrapper keys first
            if (root.has("cells")) {
                nodes = root.get("cells");
            } else if (root.has("array")) {
                nodes = root.get("array");
            } else if (root.has("results")) {
                nodes = root.get("results");
            } else if (root.has("data")) {
                nodes = root.get("data");
            } else {
                // Find first array in the object
                nodes = null;
                for (JsonNode field : root) {
                    if (field.isArray()) {
                        nodes = field;
                        break;
                    }
                }
                if (nodes == null) {
                    throw new IllegalArgumentException("No array found in response object");
                }
            }
            if (!nodes.isArray()) {
                throw new IllegalArgumentException("Expected array value, got: " + nodes.getNodeType());
            }
        } else {
            throw new IllegalArgumentException("Expected JSON array or object with array, got: " + jsonResponse.substring(0, Math.min(100, jsonResponse.length())));
        }

        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            String kind = node.get("kind").asText();
            String scale = node.get("scale").asText();
            String unit = node.get("unit").asText("");
            String currency = node.get("currency").asText("");
            double confidence = node.get("confidence").asDouble(0.0);

            if (!List.of("money", "quantity", "rate", "percent", "count", "ratio").contains(kind)) {
                throw new IllegalArgumentException("Invalid kind at index " + i + ": " + kind);
            }

            responses.add(new CellTypeResponse(kind, scale, unit, currency, confidence));
        }

        if (responses.size() != expectedCount) {
            System.err.println("[llm-fallback] Warning: expected " + expectedCount + " responses, got " + responses.size());
        }

        return responses;
    }

    private CellTypeRequest buildCellTypeRequest(
            InterpretationCellView cell, List<InterpretationCellView> allCells, Map<Long, ReadingOutcome> settled) {
        // Extract row label (text from same row, columns 0-3)
        String rowLabel = extractRowLabel(cell, allCells);
        // Extract column label (text from header area, same column)
        String columnLabel = extractColumnLabel(cell, allCells);
        // Extract neighboring cells with their types
        List<NeighborCell> neighbors = extractNeighbors(cell, allCells, settled);

        return new CellTypeRequest(
                cell.cellId(),
                cell.worksheetId(),
                cell.coord(),
                cell.displayValue() != null ? cell.displayValue() : "",
                cell.numericValue() != null ? cell.numericValue() : "",
                rowLabel,
                columnLabel,
                cell.formulaText() != null ? cell.formulaText() : "",
                neighbors);
    }

    private String extractRowLabel(InterpretationCellView cell, List<InterpretationCellView> allCells) {
        for (InterpretationCellView c : allCells) {
            if (c.worksheetId() == cell.worksheetId()
                    && c.rowNum() == cell.rowNum()
                    && c.colNum() >= 0
                    && c.colNum() <= 3
                    && c.textValue() != null
                    && !c.textValue().isBlank()) {
                return c.textValue();
            }
        }
        return "";
    }

    private String extractColumnLabel(InterpretationCellView cell, List<InterpretationCellView> allCells) {
        // Look in rows 0-5, same column
        for (int row = 0; row <= 5; row++) {
            for (InterpretationCellView c : allCells) {
                if (c.worksheetId() == cell.worksheetId()
                        && c.rowNum() == row
                        && c.colNum() == cell.colNum()
                        && c.textValue() != null
                        && !c.textValue().isBlank()) {
                    return c.textValue();
                }
            }
        }
        return "";
    }

    private List<NeighborCell> extractNeighbors(
            InterpretationCellView cell, List<InterpretationCellView> allCells, Map<Long, ReadingOutcome> settled) {
        List<NeighborCell> neighbors = new ArrayList<>();
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        String[] directions = {"up", "down", "left", "right"};

        for (int i = 0; i < offsets.length; i++) {
            int targetRow = cell.rowNum() + offsets[i][0];
            int targetCol = cell.colNum() + offsets[i][1];

            for (InterpretationCellView c : allCells) {
                if (c.worksheetId() == cell.worksheetId()
                        && c.rowNum() == targetRow
                        && c.colNum() == targetCol
                        && c.numericValue() != null
                        && !c.numericValue().isBlank()) {
                    ReadingOutcome outcome = settled.get(c.cellId());
                    String type = outcome != null && outcome.kind != null ? outcome.kind : "unknown";
                    neighbors.add(new NeighborCell(directions[i], c.displayValue(), type));
                    break;
                }
            }
        }
        return neighbors;
    }

    private CellTypeResponse classifyCell(CellTypeRequest request) throws Exception {
        String userMessage = formatUserMessage(request);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 2048);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            throw new IllegalStateException("LLM returned empty response for cell " + request.coord);
        }
        System.err.println("[cell-llm] Cell " + request.coord + ": LLM responded in " + llmMs + "ms");
        return parseCellTypeResponse(jsonResponse);
    }

    private String formatUserMessage(CellTypeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cell: ").append(request.coord).append(" (display: \"").append(request.displayValue).append("\"");
        if (!request.formulaText.isBlank()) {
            sb.append(", formula: \"").append(request.formulaText).append("\"");
        }
        sb.append(")\n");

        if (!request.rowLabel.isBlank()) {
            sb.append("Row Label: ").append(request.rowLabel).append("\n");
        }
        if (!request.columnLabel.isBlank()) {
            sb.append("Column Label: ").append(request.columnLabel).append("\n");
        }

        if (!request.neighbors.isEmpty()) {
            sb.append("Context:\n");
            for (NeighborCell neighbor : request.neighbors) {
                sb.append("  ").append(neighbor.direction).append(": ").append(neighbor.displayValue)
                        .append(" (").append(neighbor.type).append(")\n");
            }
        }

        sb.append("\nClassify as: kind, scale, unit, currency with confidence (0.0-1.0)\n");
        sb.append("Return JSON: {\"kind\":\"...\",\"scale\":\"...\",\"unit\":\"...\",\"currency\":\"...\",\"confidence\":...}\n");

        return sb.toString();
    }

    private CellTypeResponse parseCellTypeResponse(String jsonResponse) throws Exception {
        JsonNode node = MAPPER.readTree(jsonResponse);
        String kind = node.get("kind").asText();
        String scale = node.get("scale").asText();
        String unit = node.get("unit").asText("");
        String currency = node.get("currency").asText("");
        double confidence = node.get("confidence").asDouble(0.0);

        // Validate kind
        if (!List.of("money", "quantity", "rate", "percent", "count", "ratio").contains(kind)) {
            throw new IllegalArgumentException("Invalid kind: " + kind);
        }

        return new CellTypeResponse(kind, scale, unit, currency, confidence);
    }

    private boolean isNumeric(InterpretationCellView cell) {
        if (cell.isError()) {
            return false;
        }
        if ("number".equals(cell.valueType())) {
            return true;
        }
        if (cell.formulaText() == null || cell.formulaText().isBlank()) {
            return false;
        }
        return cell.numericValue() != null && !cell.numericValue().isBlank();
    }

    record CellTypeRequest(
            long cellId,
            long worksheetId,
            String coord,
            String displayValue,
            String numericValue,
            String rowLabel,
            String columnLabel,
            String formulaText,
            List<NeighborCell> neighbors) {}

    record NeighborCell(String direction, String displayValue, String type) {}

    record CellTypeResponse(String kind, String scale, String unit, String currency, double confidence) {}

    /**
     * Try to classify cell using KindTokens dictionary before falling back to LLM.
     * Checks row/column labels for money, quantity, percent patterns.
     */
    private ReadingOutcome tryDictionaryBasedTyping(InterpretationCellView cell, List<InterpretationCellView> allCells) {
        String rowLabel = extractRowLabel(cell, allCells);
        String colLabel = extractColumnLabel(cell, allCells);
        String combined = (rowLabel + " " + colLabel).toLowerCase();

        // Check for money indicators
        if (KindTokens.MONEY_TOKEN.matcher(combined).find()) {
            String currency = extractCurrencyFromLabels(rowLabel, colLabel);
            String scale = extractScaleFromLabels(rowLabel, colLabel);
            return ReadingOutcome.typed("money", CellScale.valueOf(scale.toUpperCase()), "", currency, ReadingOutcome.INPUT);
        }

        // Check for percent indicators
        if (KindTokens.PERCENT_TOKEN.matcher(combined).find()) {
            return ReadingOutcome.typed("percent", CellScale.UNIT, "", "", ReadingOutcome.INPUT);
        }

        // Check for quantity indicators
        if (KindTokens.QUANTITY_TOKEN.matcher(combined).find()) {
            String unit = extractUnitFromLabels(rowLabel, colLabel);
            return ReadingOutcome.typed("quantity", CellScale.UNIT, unit, "", ReadingOutcome.INPUT);
        }

        // Not deterministically typable
        return null;
    }

    private String extractCurrencyFromLabels(String rowLabel, String colLabel) {
        String combined = (rowLabel + " " + colLabel).toLowerCase();
        java.util.regex.Matcher matcher = KindTokens.CURRENCY.matcher(combined);
        if (matcher.find()) {
            String normalized = KindTokens.normalizeCurrency(matcher.group());
            return normalized != null ? normalized : "";
        }
        return "";
    }

    private String extractScaleFromLabels(String rowLabel, String colLabel) {
        // CellScale.fromText is the one scale-word matcher (lakh/lac/lacs/crore/million/thousand/000s).
        CellScale scale = CellScale.fromText(KindTokens.normalizeLabel(rowLabel + " " + colLabel));
        return scale == null ? "unit" : scale.name().toLowerCase(java.util.Locale.ROOT);
    }

    private String extractUnitFromLabels(String rowLabel, String colLabel) {
        String combined = (rowLabel + " " + colLabel).toLowerCase();
        java.util.regex.Matcher matcher = KindTokens.UNIT.matcher(combined);
        if (matcher.find()) {
            return KindTokens.normalizeUnit(matcher.group());
        }
        return "";
    }
}
