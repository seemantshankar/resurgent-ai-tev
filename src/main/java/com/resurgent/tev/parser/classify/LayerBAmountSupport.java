package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer B numeric helpers. A <em>numeric cell</em> is any number/formula number;
 * a <em>money cell</em> is a numeric identified as currency/cost. Quantity, rate,
 * and percent cells may appear in the prompt but must not take {@code add}/
 * {@code deduct}/{@code total} roles.
 */
final class LayerBAmountSupport {

    static final int CONTEXT_CELL_CAP = 40;

    /**
     * The subset of {@link KindTokens#QUANTITY_TOKEN} a row label may claim. A bare noun
     * ({@code Deluxe Rooms}) is an entity name, not a unit; only a counting marker
     * ({@code No. of}, {@code Number of}, {@code Nos.}, {@code Qty}, a measured
     * {@code Area}/{@code Capacity}) states the unit explicitly.
     */
    private static final Pattern ROW_QUANTITY_TOKEN = Pattern.compile(
            "(?i)(?:\\bno\\.?\\s*of\\b|\\bnumber\\s+of\\b|\\bqty\\b|\\bquantity\\b|\\bnos\\.?\\b|"
                    + "\\bunits?\\b|\\bsq\\.?\\s*ft\\b|\\bsqft\\b|\\bsqm\\b|"
                    + "\\barea\\b|\\bcapacity\\b|\\bcount\\b)");
    private static final Pattern RATE_TOKEN = Pattern.compile(
            "(?i)(?:\\brate\\b|per\\s+unit|per\\s+sq|/\\s*sq|rs\\s*/|₹\\s*/|unit\\s+rate|"
                    + "price\\s+per)");
    /**
     * A column/row header that names a period band rather than a counted quantity:
     * {@code Year 7}, {@code YR-3}, {@code FY 2025-26}, {@code Q3}, {@code Month 12},
     * a bare year, or the bare band word. Matched whole so prose like
     * {@code No. of Years of Operation} stays a quantity.
     */
    private static final Pattern PERIOD_HEADER = Pattern.compile(
            "(?i)^(?:(?:fy|cy|ay)\\s*)?(?:years?|yrs?|months?|mths?|quarters?|qtrs?|periods?|q|m|p)?"
                    + "\\s*[-–/]?\\s*(?:\\d{1,4}(?:\\s*[-–/]\\s*\\d{2,4})?)?$");
    /**
     * A percent is stated, never implied by a word. Bare {@code GST}/{@code interest}
     * appear inside entity names ("BPL LED … (Including GST)") where they describe the
     * item, not a percentage; a percent rate carries {@code %} or the word "percent".
     * Shared with the reporting evidence via {@link KindTokens#PERCENT_TOKEN}.
     */
    private static final Pattern RATE_IN_LABEL = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");

    private LayerBAmountSupport() {}

    /** Literal numeric (no formula). */
    static boolean isLiteralNumeric(PacketCell cell) {
        if (cell == null) {
            return false;
        }
        if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
            return false;
        }
        return hasNumericPayload(cell);
    }

    /** Formula with a numeric cached value. */
    static boolean isFormulaNumeric(PacketCell cell) {
        if (cell == null) {
            return false;
        }
        if (cell.formulaText() == null || cell.formulaText().isBlank()) {
            return false;
        }
        return hasNumericPayload(cell);
    }

    /** Any numeric cell listed in the Layer B prompt index. */
    static boolean isPromptNumeric(PacketCell cell) {
        return isLiteralNumeric(cell) || isFormulaNumeric(cell);
    }

    /** @deprecated Prefer {@link #isPromptNumeric}. */
    static boolean isPromptAmount(PacketCell cell) {
        return isPromptNumeric(cell);
    }

    /** @deprecated Prefer {@link #isLiteralNumeric}. */
    static boolean isLiteralAmount(PacketCell cell) {
        return isLiteralNumeric(cell);
    }

    /** @deprecated Prefer {@link #isFormulaNumeric}. */
    static boolean isFormulaAmount(PacketCell cell) {
        return isFormulaNumeric(cell);
    }

    /** @deprecated Prefer {@link #isPromptNumeric}. */
    static boolean isAmountCell(PacketCell cell) {
        return isPromptNumeric(cell);
    }

    static NumericKind classifyKind(Packet packet, PacketCell cell) {
        if (!isPromptNumeric(cell)) {
            return NumericKind.UNKNOWN;
        }
        String label = packet == null ? "" : resolveRowLabel(packet, cell);
        String header = packet == null ? "" : resolveColumnHeader(packet, cell);
        String display = cell.displayValue() == null ? "" : cell.displayValue();
        return classifyKind(label, header, display, packet != null);
    }

    /**
     * Classify a numeric cell from its text cues alone. The row label is read first:
     * when it states its own unit it wins, whether that unit is a percentage, a rate,
     * money or a counted unit. Only when the row is a pure entity name
     * ({@code Deluxe Rooms}) does the column header — resolved by the Candidate scope,
     * never a naive scan — supply the missing dimension (ADR 0020).
     */
    static NumericKind classifyKind(
            String rowLabel, String columnHeader, String displayValue, boolean hasContext) {
        String label = rowLabel == null ? "" : rowLabel;
        String header = columnHeader == null ? "" : columnHeader;
        String display = displayValue == null ? "" : displayValue;
        // Period-header test runs ahead of the quantity test: "Year 7" over a column
        // of money is a period band, not a count, and must contribute no cue at all.
        String cueHeader = isPeriodHeader(header) ? "" : header;
        String cueLabel = isPeriodHeader(label) ? "" : label;

        // A percent display is a fact about the cell itself and always wins.
        if (display.contains("%")) {
            return NumericKind.PERCENT;
        }
        NumericKind rowCue = explicitUnitCue(cueLabel, true);
        if (rowCue != null) {
            return rowCue;
        }
        NumericKind columnCue = explicitUnitCue(cueHeader, false);
        if (columnCue != null) {
            return columnCue;
        }
        return classifyFromLabel(cueLabel, display, hasContext);
    }

    /**
     * True when a row label states no unit for its own numbers, so only the column
     * can type the cell. The graph uses this to decide whether resolving the
     * Candidate-scoped column header is worth doing at all.
     */
    static boolean rowLabelNeedsColumnUnit(String rowLabel) {
        if (rowLabel == null || rowLabel.isBlank() || isPeriodHeader(rowLabel)) {
            return true;
        }
        return explicitUnitCue(rowLabel, true) == null;
    }

    /** The original row-label-only reading, used when the column contributes no cue. */
    private static NumericKind classifyFromLabel(String label, String display, boolean hasContext) {
        String haystack = (label + " " + display).toLowerCase(Locale.ROOT);
        // A quoted numeric rate in a row label says how the row was computed, not what
        // the row's own numbers are, so it never cues percent.
        String percentCue = (stripRateInLabel(label) + " " + display).toLowerCase(Locale.ROOT);
        if (KindTokens.PERCENT_TOKEN.matcher(percentCue).find()) {
            return NumericKind.PERCENT;
        }
        if (RATE_TOKEN.matcher(haystack).find()) {
            return NumericKind.RATE;
        }
        if (KindTokens.QUANTITY_TOKEN.matcher(haystack).find()
                && !KindTokens.MONEY_TOKEN.matcher(haystack).find()) {
            return NumericKind.QUANTITY;
        }
        if (KindTokens.MONEY_TOKEN.matcher(haystack).find()
                || looksLikeCurrencyDisplay(display)) {
            return NumericKind.MONEY;
        }
        if (!hasContext) {
            // Bare cell without row/column context: assume money for cost grids.
            return NumericKind.MONEY;
        }
        if (!label.isBlank()
                && !KindTokens.QUANTITY_TOKEN.matcher(label.toLowerCase(Locale.ROOT)).find()) {
            return NumericKind.MONEY;
        }
        return NumericKind.UNKNOWN;
    }

    /**
     * The unit a single label states for itself, or {@code null} for a pure entity
     * name ({@code Deluxe Rooms}) or a banner with no unit. A row label may only
     * claim a counted unit through an explicit marker ({@code No. of}, {@code Nos.},
     * {@code Qty}), because a bare noun names the entity, not the dimension; a column
     * header may claim the measured noun directly ({@code ROOMS FOR SALE}).
     */
    private static NumericKind explicitUnitCue(String text, boolean rowContext) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String percentText = rowContext ? stripRateInLabel(text).toLowerCase(Locale.ROOT) : lower;
        if (KindTokens.PERCENT_TOKEN.matcher(percentText).find()) {
            return NumericKind.PERCENT;
        }
        if (RATE_TOKEN.matcher(lower).find()) {
            return NumericKind.RATE;
        }
        if (KindTokens.MONEY_TOKEN.matcher(lower).find() || looksLikeCurrencyDisplay(text)) {
            return NumericKind.MONEY;
        }
        Pattern quantity = rowContext ? ROW_QUANTITY_TOKEN : KindTokens.QUANTITY_TOKEN;
        if (quantity.matcher(lower).find()) {
            return NumericKind.QUANTITY;
        }
        return null;
    }

    /**
     * True when the text names a period band rather than a counted quantity. Blank
     * text is not a period header: it carries no cue either way.
     */
    static boolean isPeriodHeader(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PERIOD_HEADER.matcher(text.trim()).matches();
    }

    /** A row label with any quoted numeric rate removed, so it cannot cue percent. */
    private static String stripRateInLabel(String label) {
        return RATE_IN_LABEL.matcher(label).replaceAll(" ");
    }

    /**
     * Role gating: a non-money kind cannot take add/deduct/total (helper only,
     * intentionally supported for qty/rate/percent).
     *
     * <p>Being a formula is no longer a gate. It was standing in for
     * anti-double-counting, but 71% of numeric cells are formulas, so it forced
     * every one of them to helper or total and left the binding table contributing
     * nothing to any leaf rollup. The cell graph tests double counting directly: a
     * cell is a rollup exactly when it heads an aggregation.
     */
    static boolean isBindableForRole(Packet packet, PacketCell cell, String role) {
        if (!isPromptNumeric(cell) || !AmountRole.isKnown(role)) {
            return false;
        }
        NumericKind kind = classifyKind(packet, cell);
        if (AmountRole.ADD.equals(role) || AmountRole.DEDUCT.equals(role)
                || AmountRole.TOTAL.equals(role)) {
            return kind.allowsCostRole();
        }
        // helper: money, quantity, rate, percent, unknown
        return true;
    }

    static boolean isBindableForRole(PacketCell cell, String role) {
        return isBindableForRole(null, cell, role);
    }

    static boolean hasAmountCells(Packet packet) {
        if (packet == null) {
            return false;
        }
        for (PacketCell cell : packet.cells()) {
            if (isPromptNumeric(cell)) {
                return true;
            }
        }
        return false;
    }

    static List<PacketCell> amountCells(Packet packet) {
        List<PacketCell> amounts = new ArrayList<>();
        for (PacketCell cell : packet.cells()) {
            if (isPromptNumeric(cell)) {
                amounts.add(cell);
            }
        }
        amounts.sort(Comparator
                .comparingInt(PacketCell::rowNum)
                .thenComparingInt(PacketCell::colNum));
        return List.copyOf(amounts);
    }

    static String resolveRowLabel(Packet packet, PacketCell amount) {
        if (packet == null || amount == null) {
            return "";
        }
        PacketCell best = null;
        for (PacketCell cell : packet.cells()) {
            if (cell.rowNum() != amount.rowNum() || cell.colNum() >= amount.colNum()) {
                continue;
            }
            String label = labelText(cell);
            if (label == null) {
                continue;
            }
            if (best == null || cell.colNum() > best.colNum()) {
                best = cell;
            }
        }
        if (best != null) {
            return labelText(best);
        }
        String self = labelText(amount);
        if (self != null) {
            return self;
        }
        return "";
    }

    static String resolveColumnHeader(Packet packet, PacketCell amount) {
        if (packet == null || amount == null) {
            return "";
        }
        PacketCell best = null;
        for (PacketCell cell : packet.cells()) {
            if (cell.colNum() != amount.colNum() || cell.rowNum() >= amount.rowNum()) {
                continue;
            }
            String label = labelText(cell);
            if (label == null) {
                continue;
            }
            if (best == null || cell.rowNum() > best.rowNum()) {
                best = cell;
            }
        }
        return best == null ? "" : labelText(best);
    }

    static List<PacketCell> contextCells(Packet packet) {
        if (packet == null) {
            return List.of();
        }
        List<PacketCell> amounts = amountCells(packet);
        if (amounts.isEmpty()) {
            return List.of();
        }
        int minAmountRow = amounts.stream().mapToInt(PacketCell::rowNum).min().orElse(0);
        Set<Long> amountIds = new LinkedHashSet<>();
        Set<Integer> amountCols = new LinkedHashSet<>();
        Set<Integer> amountRows = new LinkedHashSet<>();
        for (PacketCell amount : amounts) {
            amountIds.add(amount.cellId());
            amountCols.add(amount.colNum());
            amountRows.add(amount.rowNum());
        }
        List<PacketCell> context = new ArrayList<>();
        for (PacketCell cell : packet.cells()) {
            if (amountIds.contains(cell.cellId()) || isPromptNumeric(cell)) {
                continue;
            }
            if (labelText(cell) == null) {
                continue;
            }
            boolean near = amountRows.contains(cell.rowNum())
                    || amountCols.contains(cell.colNum())
                    || (cell.rowNum() >= minAmountRow - 3 && cell.rowNum() < minAmountRow);
            if (near) {
                context.add(cell);
            }
        }
        context.sort(Comparator
                .comparingInt(PacketCell::rowNum)
                .thenComparingInt(PacketCell::colNum));
        if (context.size() > CONTEXT_CELL_CAP) {
            context = context.subList(0, CONTEXT_CELL_CAP);
        }
        return List.copyOf(context);
    }

    private static boolean looksLikeCurrencyDisplay(String display) {
        return KindTokens.displayNamesCurrency(display);
    }

    private static boolean hasNumericPayload(PacketCell cell) {
        return "number".equals(cell.valueType())
                || (cell.numericValue() != null && !cell.numericValue().isBlank());
    }

    static String labelText(PacketCell cell) {
        if (cell == null) {
            return null;
        }
        return LabelText.of(
                cell.textValue(),
                cell.displayValue(),
                cell.numericValue(),
                cell.valueType(),
                cell.formulaText());
    }
}
