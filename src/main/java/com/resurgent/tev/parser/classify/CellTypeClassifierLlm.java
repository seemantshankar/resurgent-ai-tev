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

    public CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm) {
        this.repo = repo;
        this.llm = llm;
    }

    /**
     * Classify remaining UNTYPABLE cells using LLM.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with LLM-inferred ones.
     *
     * @param cells all interpretation cells for the parse run
     * @param settled map of cellId → ReadingOutcome, modified in-place
     */
    public void classifyRemaining(List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled) {
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
            return;
        }

        System.err.println("[llm-fallback] Classifying " + unclassified.size() + " untyped cells via LLM");

        // Group by worksheet for context coherence
        Map<Long, List<InterpretationCellView>> byWorksheet = new HashMap<>();
        for (InterpretationCellView cell : unclassified) {
            byWorksheet.computeIfAbsent(cell.worksheetId(), k -> new ArrayList<>()).add(cell);
        }

        for (List<InterpretationCellView> worksheetCells : byWorksheet.values()) {
            for (InterpretationCellView cell : worksheetCells) {
                CellTypeRequest request = buildCellTypeRequest(cell, cells, settled);
                try {
                    CellTypeResponse response = classifyCell(request);
                    if (response.confidence >= MIN_CONFIDENCE) {
                        CellScale scale = CellScale.fromWire(response.scale);
                        settled.put(cell.cellId(), ReadingOutcome.typed(
                                response.kind, scale, response.unit, response.currency, "llm_fallback"));
                    }
                } catch (Exception e) {
                    System.err.println("[llm-fallback] Failed to classify " + cell.coord() + ": " + e.getMessage());
                }
            }
        }
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
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 2048);
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
}
