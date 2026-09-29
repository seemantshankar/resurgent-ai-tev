package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * <h2>Implementation Notes</h2>
 * To enable: uncomment the LLM fallback call in {@link CellReadingWriter#replace(WorkspaceRepository, long)}
 * 
 * Requires:
 * - Extend this class with actual LLM integration (e.g., OpenRouter API)
 * - Define prompt format for cell type classification
 * - Parse LLM response into {@link ReadingOutcome}
 * - Handle rate limits and LLM errors gracefully
 * 
 * @see ReadingOutcome
 * @see CellReadingInferencer
 * @see ClassifierLlm
 */
public class CellTypeClassifierLlm {
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
        for (InterpretationCellView cell : cells) {
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

        // TODO: Batch unclassified cells into LLM request
        // - Group by worksheet for context
        // - Build prompt with cell text, row/column labels, neighboring cells
        // - Send to LLM
        // - Parse response into ReadingOutcome objects
        // - Update settled map with results marked as source="llm_fallback"

        // Example structure:
        // List<LlmCellTypeRequest> requests = buildRequests(unclassified, cells, settled);
        // for (LlmCellTypeRequest req : requests) {
        //     LlmCellTypeResponse resp = llm.classifyCell(req);
        //     if (resp.confidence() >= MIN_CONFIDENCE) {
        //         settled.put(req.cellId(), ReadingOutcome.typed(
        //             resp.kind(), resp.scale(), resp.unit(), resp.currency(), "llm_fallback"));
        //     }
        // }
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

    // TODO: Define the LLM request/response types and build methods
    // - LlmCellTypeRequest: cell data, context, neighboring cells
    // - LlmCellTypeResponse: inferred kind, scale, unit, currency, confidence
}
