package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds and persists Cell interpretation snapshots inside the caller's
 * classify transaction. Does not open or commit its own transaction.
 */
public class InterpretationWriter {

    /**
     * Replace all interpretations for the parse run. Returns the number written
     * (must equal the persisted cell count).
     */
    public int write(
            WorkspaceRepository repo, long parseRunId, List<NomenclatureBinding> bindings)
            throws SQLException {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(bindings, "bindings");
        Map<Long, NomenclatureBinding> byCell = new HashMap<>();
        for (NomenclatureBinding binding : bindings) {
            byCell.put(binding.cellId(), binding);
        }
        repo.deleteInterpretationsForParseRun(parseRunId);
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        for (InterpretationCellView cell : cells) {
            repo.insertCellInterpretation(build(parseRunId, cell, byCell.get(cell.cellId())));
        }
        return cells.size();
    }

    CellInterpretation build(
            long parseRunId, InterpretationCellView cell, NomenclatureBinding binding) {
        boolean formula = isFormula(cell);
        String valueOrigin = formula ? "formula" : "literal";
        String resultSource;
        String resultingValue;
        if (formula) {
            if (hasUsableCache(cell)) {
                resultSource = "formula_cache";
                resultingValue = resultingFromCache(cell);
            } else {
                resultSource = "missing_cache";
                resultingValue = null;
            }
        } else {
            resultSource = "literal";
            resultingValue = resultingFromLiteral(cell);
        }

        String status;
        String path = null;
        String amountRole = null;
        Boolean softLeaf = null;
        Boolean viaAlias = null;
        if (binding != null) {
            status = NomenclatureStatus.BOUND;
            path = binding.path();
            amountRole = binding.amountRole();
            softLeaf = binding.softLeaf();
            viaAlias = binding.viaAlias();
        } else if (cell.isMergedParticipant() || isBlank(cell) || isClearlyNonLayerB(cell, formula)) {
            status = NomenclatureStatus.NOT_APPLICABLE;
        } else {
            status = NomenclatureStatus.UNBOUND;
        }

        return new CellInterpretation(
                parseRunId,
                cell.cellId(),
                valueOrigin,
                resultingValue,
                resultSource,
                blankToNull(cell.formulaText()),
                blankToNull(cell.formulaState()),
                blankToNull(cell.cacheState()),
                cell.isError(),
                blankToNull(cell.errorType()),
                path,
                amountRole,
                softLeaf,
                viaAlias,
                status);
    }

    private static boolean isFormula(InterpretationCellView cell) {
        if (cell.formulaState() != null && !cell.formulaState().isBlank()) {
            return true;
        }
        return cell.formulaText() != null && !cell.formulaText().isBlank();
    }

    private static boolean hasUsableCache(InterpretationCellView cell) {
        return cell.cachedValue() != null && !cell.cachedValue().isBlank();
    }

    private static String resultingFromCache(InterpretationCellView cell) {
        return cell.cachedValue();
    }

    private static String resultingFromLiteral(InterpretationCellView cell) {
        if (cell.numericValue() != null) {
            return cell.numericValue();
        }
        if (cell.textValue() != null) {
            return cell.textValue();
        }
        if (cell.boolValue() != null) {
            return cell.boolValue() ? "true" : "false";
        }
        if (cell.dateValue() != null) {
            return cell.dateValue();
        }
        if (cell.displayValue() != null && !cell.displayValue().isBlank()) {
            return cell.displayValue();
        }
        return null;
    }

    private static boolean isBlank(InterpretationCellView cell) {
        if (isFormula(cell)) {
            return false;
        }
        String valueType = cell.valueType() == null ? "" : cell.valueType().toLowerCase(Locale.ROOT);
        if ("empty".equals(valueType) || "blank".equals(valueType)) {
            return true;
        }
        return cell.numericValue() == null
                && (cell.textValue() == null || cell.textValue().isBlank())
                && cell.boolValue() == null
                && cell.dateValue() == null
                && !cell.isError();
    }

    /**
     * Non-numeric literals (headers, labels, dates, booleans) are not Layer B money
     * candidates when unbound. Formulas and numeric literals stay unbound instead.
     */
    private static boolean isClearlyNonLayerB(InterpretationCellView cell, boolean formula) {
        if (formula) {
            return false;
        }
        if (cell.numericValue() != null) {
            return false;
        }
        String valueType = cell.valueType() == null ? "" : cell.valueType().toLowerCase(Locale.ROOT);
        return !"number".equals(valueType);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
