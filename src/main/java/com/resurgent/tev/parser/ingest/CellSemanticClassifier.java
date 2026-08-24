package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cell-level scratch / support / orphan classification. Candidates are promoted
 * to support by walking dependents to a fixed point; nothing is deleted.
 */
final class CellSemanticClassifier {

    static final String UNLABELED_FORMULA_ISLAND = "UNLABELED_FORMULA_ISLAND";
    static final String DISABLED_LINE = "DISABLED_LINE";
    static final String PAGE_CONSTANT = "PAGE_CONSTANT";
    static final String SUPPORT_DEPENDENCY = "SUPPORT_DEPENDENCY";

    private static final Pattern DISABLED = Pattern.compile("(?i).*\\*\\s*0\\s*\\)?\\s*$");

    record Snapshot(
            long cellId,
            Long regionId,
            String formula,
            String rowLabel,
            String colLabel,
            String text,
            BigDecimal numeric) {}

    record CellFlags(
            boolean scratch,
            String scratchReason,
            boolean support,
            String supportReason,
            boolean orphan) {}

    record Result(
            Map<Long, CellFlags> cells,
            Set<Long> scratchRegions,
            int scratchCount,
            int supportCount,
            int orphanCount,
            int promotions) {}

    Result classify(List<Snapshot> cells, Map<Long, List<Long>> dependents, Map<Long, List<Long>> precedents) {
        Map<Long, Snapshot> byId = new LinkedHashMap<>();
        for (Snapshot cell : cells) {
            byId.put(cell.cellId(), cell);
        }
        Map<Long, String> scratchReasons = new HashMap<>();
        for (Snapshot cell : cells) {
            String reason = scratchReason(cell);
            if (reason != null) {
                scratchReasons.put(cell.cellId(), reason);
            }
        }
        Set<Long> scratch = new HashSet<>(scratchReasons.keySet());
        Set<Long> support = new HashSet<>();
        int promotions = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (long id : List.copyOf(scratch)) {
                if (feedsNonScratch(id, dependents, scratch, support, byId.keySet())) {
                    scratch.remove(id);
                    support.add(id);
                    promotions++;
                    changed = true;
                }
            }
        }
        Map<Long, CellFlags> flags = new LinkedHashMap<>();
        int scratchCount = 0;
        int supportCount = 0;
        int orphanCount = 0;
        for (Snapshot cell : cells) {
            boolean isSupport = support.contains(cell.cellId());
            boolean isScratch = scratch.contains(cell.cellId());
            boolean isOrphan = !isSupport && !isScratch && isOrphan(cell, dependents, precedents);
            if (isScratch) {
                scratchCount++;
            }
            if (isSupport) {
                supportCount++;
            }
            if (isOrphan) {
                orphanCount++;
            }
            flags.put(cell.cellId(), new CellFlags(
                    isScratch,
                    isScratch ? scratchReasons.get(cell.cellId()) : null,
                    isSupport,
                    isSupport ? SUPPORT_DEPENDENCY : null,
                    isOrphan));
        }
        Set<Long> scratchRegions = new HashSet<>();
        Map<Long, List<Snapshot>> byRegion = new HashMap<>();
        for (Snapshot cell : cells) {
            if (cell.regionId() != null) {
                byRegion.computeIfAbsent(cell.regionId(), key -> new ArrayList<>()).add(cell);
            }
        }
        for (Map.Entry<Long, List<Snapshot>> entry : byRegion.entrySet()) {
            List<Snapshot> meaningful = entry.getValue().stream().filter(this::meaningful).toList();
            if (!meaningful.isEmpty()
                    && meaningful.stream().allMatch(cell -> scratch.contains(cell.cellId()))) {
                scratchRegions.add(entry.getKey());
            }
        }
        return new Result(flags, scratchRegions, scratchCount, supportCount, orphanCount, promotions);
    }

    private static boolean feedsNonScratch(
            long id,
            Map<Long, List<Long>> dependents,
            Set<Long> scratch,
            Set<Long> support,
            Set<Long> known) {
        for (long dependent : dependents.getOrDefault(id, List.of())) {
            if (!known.contains(dependent)) {
                continue;
            }
            if (!scratch.contains(dependent) || support.contains(dependent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOrphan(
            Snapshot cell, Map<Long, List<Long>> dependents, Map<Long, List<Long>> precedents) {
        if (hasLabel(cell)) {
            return false;
        }
        boolean noDeps = dependents.getOrDefault(cell.cellId(), List.of()).isEmpty();
        boolean noPreds = precedents.getOrDefault(cell.cellId(), List.of()).isEmpty();
        return noDeps && noPreds;
    }

    private String scratchReason(Snapshot cell) {
        if (hasFormula(cell) && DISABLED.matcher(cell.formula().trim()).matches()) {
            return DISABLED_LINE;
        }
        if (hasFormula(cell) && !hasLabel(cell)) {
            return UNLABELED_FORMULA_ISLAND;
        }
        if (!hasFormula(cell) && !hasLabel(cell) && isPageConstant(cell)) {
            return PAGE_CONSTANT;
        }
        return null;
    }

    private static boolean hasFormula(Snapshot cell) {
        return cell.formula() != null && !cell.formula().isBlank();
    }

    private static boolean hasLabel(Snapshot cell) {
        return isNonNumericLabel(cell.colLabel())
                || isMeaningfulRowLabel(cell);
    }

    /**
     * A region role, not the cell echoing its own value into row_label.
     */
    private static boolean isMeaningfulRowLabel(Snapshot cell) {
        if (!notBlank(cell.rowLabel())) {
            return false;
        }
        String label = cell.rowLabel().trim();
        if (cell.text() != null && label.equalsIgnoreCase(cell.text().trim())) {
            return false;
        }
        if (cell.numeric() != null && isNumericString(label)
                && new BigDecimal(label).compareTo(cell.numeric()) == 0) {
            return false;
        }
        return !isNumericString(label);
    }

    private static boolean isNonNumericLabel(String label) {
        return notBlank(label) && !isNumericString(label);
    }

    private static boolean isNumericString(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean meaningful(Snapshot cell) {
        return hasFormula(cell) || cell.numeric() != null;
    }

    private static boolean isPageConstant(Snapshot cell) {
        if (cell.numeric() == null) {
            return false;
        }
        try {
            BigDecimal value = cell.numeric();
            if (value.stripTrailingZeros().scale() > 0) {
                return false;
            }
            long n = value.longValueExact();
            return n >= 1 && n <= 999;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    static Map<Long, List<Long>> reverse(Map<Long, List<Long>> precedents) {
        Map<Long, List<Long>> dependents = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : precedents.entrySet()) {
            for (Long precedent : entry.getValue()) {
                if (precedent != null) {
                    dependents.computeIfAbsent(precedent, key -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }
        return dependents;
    }
}
