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
            Each request may begin with a "Region" line describing the schedule the cells sit in
            (its family and what it is about). Use it to decide the kind of a label that carries no
            unit or currency word: a row such as "Firefighting & Misc." inside an expenses schedule
            is money even though the label never says so.

            Respect the "nothing incorrect is written" principle: if unsure, return lower confidence.
            """;

    private final WorkspaceRepository repo;
    private final ClassifierLlm llm;
    private final DynamicKindTokens dynamicDict;
    private CellContext ctx = CellContext.scan(List.of());

    public CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm) {
        this(repo, llm, new DynamicKindTokens());
    }

    CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm, DynamicKindTokens dynamicDict) {
        this.repo = repo;
        this.llm = llm;
        this.dynamicDict = dynamicDict;
    }

    /**
     * Classify remaining UNTYPABLE cells using LLM.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with LLM-inferred ones.
     *
     * @param cells all interpretation cells for the parse run
     * @param settled map of cellId → ReadingOutcome, modified in-place
     */
    public void classifyRemaining(List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled) {
        classifyRemaining(cells, settled, CellContext.scan(cells));
    }

    /**
     * As {@link #classifyRemaining(List, Map)}, with labels and Layer A region supplied by
     * {@code context} (the candidate-scoped resolver in production).
     */
    void classifyRemaining(
            List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled, CellContext context) {
        this.ctx = context;
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
            ReadingOutcome outcome = tryDictionaryBasedTyping(cell);
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

        // Group by owning region (one region description per batch); cells with no known
        // region fall back to grouping by worksheet.
        Map<String, List<InterpretationCellView>> byRegion = new java.util.LinkedHashMap<>();
        for (InterpretationCellView cell : stillUntyped) {
            RegionContext region = ctx.region(cell);
            String groupKey = region.known() ? "c" + region.candidateId() : "w" + cell.worksheetId();
            byRegion.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(cell);
        }

        // Batch cells for efficiency: classify 15 cells per LLM call instead of 1
        for (List<InterpretationCellView> regionCells : byRegion.values()) {
            for (int i = 0; i < regionCells.size(); i += 15) {
                int end = Math.min(i + 15, regionCells.size());
                classifyBatch(regionCells.subList(i, end), cells, settled);
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
            List<CellTypeResponse> responses = classifyBatchCells(requests, ctx.region(batch.get(0)));
            if (responses.size() != batch.size()) {
                // Positions no longer line up with cells; do not guess which answer is whose.
                throw new IllegalStateException(
                        "expected " + batch.size() + " responses, got " + responses.size());
            }
            for (int i = 0; i < batch.size(); i++) {
                applyResponse(batch.get(i), responses.get(i), settled);
            }
        } catch (Exception e) {
            System.err.println("[llm-fallback] Failed to classify batch: " + e.getMessage());
            // Fall back to individual classification on batch failure
            for (InterpretationCellView cell : batch) {
                CellTypeRequest request = buildCellTypeRequest(cell, allCells, settled);
                try {
                    applyResponse(cell, classifyCell(request, ctx.region(cell)), settled);
                } catch (Exception ex) {
                    System.err.println("[llm-fallback] Failed to classify " + cell.coord() + ": " + ex.getMessage());
                }
            }
        }
    }

    /** Settle one LLM answer and stage it as evidence for the dictionary. */
    private void applyResponse(
            InterpretationCellView cell, CellTypeResponse response, Map<Long, ReadingOutcome> settled) {
        if (response.confidence < MIN_CONFIDENCE) {
            return;
        }
        CellScale scale = parseScale(response.scale);
        if (scale == null) {
            System.err.println("[llm-fallback] Unknown scale '" + response.scale + "' for " + cell.coord());
            return;
        }
        settled.put(cell.cellId(), ReadingOutcome.typed(
                response.kind, scale, response.unit, response.currency, ReadingOutcome.DERIVED));
        learnFrom(cell, response);
    }

    private static CellScale parseScale(String wire) {
        if (wire == null || wire.isBlank()) {
            return CellScale.UNIT;
        }
        try {
            return CellScale.fromWire(wire);
        } catch (IllegalArgumentException e) {
            return CellScale.fromText(wire); // "lacs", "crores" ... or null when it names no scale
        }
    }

    /**
     * Stage the row label as evidence only when the region is a known main packet and the
     * deterministic pass could not have typed the label itself.
     */
    private void learnFrom(InterpretationCellView cell, CellTypeResponse response) {
        RegionContext region = ctx.region(cell);
        if (!region.known() || !region.learnable()) {
            return;
        }
        String rowLabel = ctx.rowLabel(cell);
        String combined = KindTokens.normalizeLabel(rowLabel + " " + ctx.columnLabel(cell));
        if (staticKind(combined) != null) {
            return;
        }
        dynamicDict.observe(
                region.scheduleFamily(), rowLabel, response.kind, response.unit,
                ctx.workbookKey(), region.sheetName(), cell.rowNum());
    }

    private List<CellTypeResponse> classifyBatchCells(List<CellTypeRequest> requests, RegionContext region) throws Exception {
        String userMessage = formatBatchUserMessage(requests, region);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 4096);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        System.err.println("[cell-llm] Batch of " + requests.size() + " cells: LLM responded in " + llmMs + "ms");
        return parseBatchCellTypeResponse(jsonResponse, requests.size());
    }

    private static void appendRegion(StringBuilder sb, RegionContext region) {
        if (region.scheduleFamily().isBlank() && region.about().isBlank()) {
            return;
        }
        sb.append("Region: family=").append(region.scheduleFamily());
        if (!region.packetHead().isBlank()) {
            sb.append("; head=").append(region.packetHead());
        }
        if (!region.sheetName().isBlank()) {
            sb.append("; sheet=").append(region.sheetName());
        }
        if (!region.about().isBlank()) {
            sb.append("; about=").append(region.about());
        }
        sb.append("\n\n");
    }

    private String formatBatchUserMessage(List<CellTypeRequest> requests, RegionContext region) {
        StringBuilder sb = new StringBuilder();
        appendRegion(sb, region);
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
            String kind = node.get("kind").asText().trim().toLowerCase(java.util.Locale.ROOT);
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
        String rowLabel = ctx.rowLabel(cell);
        String columnLabel = ctx.columnLabel(cell);
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

    private CellTypeResponse classifyCell(CellTypeRequest request, RegionContext region) throws Exception {
        String userMessage = formatUserMessage(request, region);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 2048);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            throw new IllegalStateException("LLM returned empty response for cell " + request.coord);
        }
        System.err.println("[cell-llm] Cell " + request.coord + ": LLM responded in " + llmMs + "ms");
        return parseCellTypeResponse(jsonResponse);
    }

    private String formatUserMessage(CellTypeRequest request, RegionContext region) {
        StringBuilder sb = new StringBuilder();
        appendRegion(sb, region);
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
        String kind = node.get("kind").asText().trim().toLowerCase(java.util.Locale.ROOT);
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

    /** Kind named by the static cue tokens in already-normalized label text, else {@code null}. */
    private static String staticKind(String normalizedLabels) {
        if (KindTokens.MONEY_TOKEN.matcher(normalizedLabels).find()) {
            return ReadingOutcome.MONEY;
        }
        if (KindTokens.PERCENT_TOKEN.matcher(normalizedLabels).find()) {
            return ReadingOutcome.PERCENT;
        }
        if (KindTokens.QUANTITY_TOKEN.matcher(normalizedLabels).find()) {
            return ReadingOutcome.QUANTITY;
        }
        return null;
    }

    /**
     * Type a cell without the LLM: first the static {@link KindTokens} cues, then what the
     * dictionary has learned about this row label inside this region's schedule family.
     */
    private ReadingOutcome tryDictionaryBasedTyping(InterpretationCellView cell) {
        String rowLabel = KindTokens.normalizeLabel(ctx.rowLabel(cell));
        String colLabel = KindTokens.normalizeLabel(ctx.columnLabel(cell));
        String combined = rowLabel + " " + colLabel;

        String kind = staticKind(combined);
        if (ReadingOutcome.MONEY.equals(kind)) {
            String scale = extractScaleFromLabels(rowLabel, colLabel);
            return ReadingOutcome.typed(ReadingOutcome.MONEY, CellScale.valueOf(scale.toUpperCase()), "",
                    extractCurrencyFromLabels(rowLabel, colLabel), ReadingOutcome.INPUT);
        }
        if (ReadingOutcome.PERCENT.equals(kind)) {
            return ReadingOutcome.typed(ReadingOutcome.PERCENT, CellScale.UNIT, "", "", ReadingOutcome.INPUT);
        }
        if (ReadingOutcome.QUANTITY.equals(kind)) {
            return ReadingOutcome.typed(ReadingOutcome.QUANTITY, CellScale.UNIT,
                    extractUnitFromLabels(rowLabel, colLabel), "", ReadingOutcome.INPUT);
        }
        return tryLearnedTyping(cell, colLabel);
    }

    private ReadingOutcome tryLearnedTyping(InterpretationCellView cell, String normalizedColumn) {
        RegionContext region = ctx.region(cell);
        if (!region.known()) {
            return null;
        }
        var learned = dynamicDict.lookup(region.scheduleFamily(), ctx.rowLabel(cell));
        if (learned.isEmpty()) {
            return null;
        }
        String kind = learned.get().kind();
        // Explicit %/quantity cues in the headers were already honoured: the static pass runs first.
        if (ReadingOutcome.MONEY.equals(kind)) {
            // Scale varies by sheet, so it is never remembered: no stated scale, ask the LLM.
            CellScale scale = CellScale.fromText(KindTokens.normalizeLabel(ctx.rowLabel(cell) + " " + normalizedColumn));
            if (scale == null) {
                return null;
            }
            return ReadingOutcome.typed(kind, scale, "", extractCurrencyFromLabels("", normalizedColumn),
                    ReadingOutcome.DERIVED);
        }
        return ReadingOutcome.typed(kind, CellScale.UNIT, learned.get().unit(), "", ReadingOutcome.DERIVED);
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
