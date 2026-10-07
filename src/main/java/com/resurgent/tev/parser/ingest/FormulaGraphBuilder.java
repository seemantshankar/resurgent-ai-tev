package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.CellCoordRef;
import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.FormulaGap;
import com.resurgent.tev.parser.db.FormulaLink;
import com.resurgent.tev.parser.db.PersistedCellReference;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the formula graph after reference edges are stored. Direct links name
 * the persisted cells a formula reads. Reach is the shortest chain through
 * those links. A gap records a read that leaves the workbook, cannot be
 * expanded, or sits on a cycle.
 */
public final class FormulaGraphBuilder {

    public static final String EXTERNAL = "external";
    public static final String DEFINED_NAME = "defined_name";
    public static final String CYCLE = "cycle";

    public void build(WorkspaceRepository repo, long parseRunId,
            Map<String, Long> sheetNameToId, Map<String, String> definedNames) throws SQLException {
        Map<Long, List<CellCoordRef>> cellsByWorksheet = repo.selectCellCoordsByWorksheet(parseRunId);
        Map<Long, Set<Long>> direct = new LinkedHashMap<>();
        Set<FormulaGap> gaps = new LinkedHashSet<>();

        for (PersistedCellReference persisted : repo.selectPersistedCellReferencesForParseRun(parseRunId)) {
            CellReferenceEdge edge = persisted.edge();
            long from = edge.fromCellId();
            if (edge.unresolvedReason() != null) {
                gaps.add(gap(from, gapReason(edge), token(edge)));
                continue;
            }
            if (isExternal(edge)) {
                gaps.add(gap(from, EXTERNAL, token(edge)));
                continue;
            }
            if ("defined_name".equals(edge.refKind())) {
                expandDefinedName(from, edge, sheetNameToId, definedNames, cellsByWorksheet, direct, gaps);
                continue;
            }
            if (edge.resolvedCellId() != null) {
                addLink(direct, from, edge.resolvedCellId());
                continue;
            }
            expandRange(from, edge.targetWorksheetId(), edge.targetRange(), cellsByWorksheet, direct);
        }

        // A cell is cyclic when it is on a cycle or reads, through any chain, a cell that is. That is found by
        // walking back from the cycle cells to what reads them, once, not by listing everything every cell reaches.
        Set<Long> cyclicCells = cellsReadingACycle(cycleParticipants(direct), direct);
        for (long origin : direct.keySet()) {
            if (cyclicCells.contains(origin)) {
                gaps.add(gap(origin, CYCLE, ""));
            }
        }

        List<FormulaLink> links = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> entry : direct.entrySet()) {
            for (long to : entry.getValue()) {
                links.add(new FormulaLink(entry.getKey(), to));
            }
        }
        repo.insertFormulaLinks(links);
        repo.insertFormulaGaps(new ArrayList<>(gaps));
    }

    private static void expandDefinedName(long from, CellReferenceEdge edge,
            Map<String, Long> sheetNameToId, Map<String, String> definedNames,
            Map<Long, List<CellCoordRef>> cellsByWorksheet,
            Map<Long, Set<Long>> direct, Set<FormulaGap> gaps) {
        String refersTo = lookupDefinedName(definedNames, edge.rawToken(), edge.targetRange());
        String[] sheetAndRange = refersTo == null ? null : splitSheetAndRange(refersTo);
        if (sheetAndRange == null) {
            gaps.add(gap(from, DEFINED_NAME, token(edge)));
            return;
        }
        Long worksheetId = sheetAndRange[0] == null
                ? edge.targetWorksheetId()
                : sheetNameToId.get(IngestService.sheetLookupKey(sheetAndRange[0]));
        if (worksheetId == null) {
            gaps.add(gap(from, DEFINED_NAME, token(edge)));
            return;
        }
        expandRange(from, worksheetId, sheetAndRange[1], cellsByWorksheet, direct);
    }

    private static void expandRange(long from, Long worksheetId, String targetRange,
            Map<Long, List<CellCoordRef>> cellsByWorksheet, Map<Long, Set<Long>> direct) {
        if (worksheetId == null || targetRange == null || targetRange.isBlank()) {
            return;
        }
        for (CellCoordRef cell : cellsByWorksheet.getOrDefault(worksheetId, List.of())) {
            if (WorkspaceRepository.rangeContains(targetRange, cell.rowNum(), cell.colNum())) {
                addLink(direct, from, cell.cellId());
            }
        }
    }

    /**
     * Nodes that sit on a directed cycle in the direct-read graph, found once
     * for the whole workbook.
     */
    private static Set<Long> cycleParticipants(Map<Long, Set<Long>> direct) {
        Map<Long, Integer> color = new HashMap<>();
        ArrayDeque<Long> stack = new ArrayDeque<>();
        Set<Long> cyclic = new HashSet<>();
        for (long origin : direct.keySet()) {
            if (color.getOrDefault(origin, 0) == 0) {
                paintCycles(origin, direct, color, stack, cyclic);
            }
        }
        return cyclic;
    }

    private static void paintCycles(long node, Map<Long, Set<Long>> direct,
            Map<Long, Integer> color, ArrayDeque<Long> stack, Set<Long> cyclic) {
        color.put(node, 1);
        stack.push(node);
        for (long next : direct.getOrDefault(node, Set.of())) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 1) {
                cyclic.add(node);
                for (long stacked : stack) {
                    cyclic.add(stacked);
                    if (stacked == next) {
                        break;
                    }
                }
            } else if (nextColor == 0) {
                paintCycles(next, direct, color, stack, cyclic);
            }
        }
        stack.pop();
        color.put(node, 2);
    }

    /** The cycle cells and every cell that reads one of them, directly or through other cells. */
    private static Set<Long> cellsReadingACycle(Set<Long> cycleNodes, Map<Long, Set<Long>> direct) {
        Set<Long> found = new HashSet<>(cycleNodes);
        if (cycleNodes.isEmpty()) {
            return found;
        }
        Map<Long, List<Long>> readers = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : direct.entrySet()) {
            for (long read : entry.getValue()) {
                readers.computeIfAbsent(read, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        ArrayDeque<Long> queue = new ArrayDeque<>(cycleNodes);
        while (!queue.isEmpty()) {
            for (long reader : readers.getOrDefault(queue.removeFirst(), List.of())) {
                if (found.add(reader)) {
                    queue.add(reader);
                }
            }
        }
        return found;
    }

    private static String gapReason(CellReferenceEdge edge) {
        if (isExternal(edge) || "external_unresolved".equals(edge.unresolvedReason())) {
            return EXTERNAL;
        }
        return edge.unresolvedReason();
    }

    private static boolean isExternal(CellReferenceEdge edge) {
        if (edge.externalLinkId() != null || "external".equals(edge.refKind())) {
            return true;
        }
        return edge.rawToken() != null && edge.rawToken().contains("[");
    }

    private static String lookupDefinedName(Map<String, String> definedNames, String rawToken, String targetRange) {
        if (definedNames == null || definedNames.isEmpty()) {
            return null;
        }
        String byRaw = matchName(definedNames, rawToken);
        return byRaw != null ? byRaw : matchName(definedNames, targetRange);
    }

    private static String matchName(Map<String, String> definedNames, String name) {
        if (name == null) {
            return null;
        }
        String direct = definedNames.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : definedNames.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Splits a defined name's refers-to into a sheet (null when unqualified) and
     * an A1 range. Returns null when the refers-to is not a plain cell or range.
     */
    static String[] splitSheetAndRange(String refersTo) {
        if (refersTo == null) {
            return null;
        }
        String body = refersTo.trim();
        if (body.startsWith("=")) {
            body = body.substring(1).trim();
        }
        if (body.isEmpty() || body.indexOf('(') >= 0 || body.indexOf('#') >= 0 || body.indexOf(',') >= 0) {
            return null;
        }
        String sheet = null;
        String range;
        if (body.charAt(0) == '\'') {
            StringBuilder sheetName = new StringBuilder();
            int index = 1;
            while (index < body.length()) {
                char ch = body.charAt(index);
                if (ch == '\'' && index + 1 < body.length() && body.charAt(index + 1) == '\'') {
                    sheetName.append('\'');
                    index += 2;
                    continue;
                }
                if (ch == '\'') {
                    break;
                }
                sheetName.append(ch);
                index++;
            }
            if (index >= body.length() || body.charAt(index) != '\''
                    || index + 1 >= body.length() || body.charAt(index + 1) != '!') {
                return null;
            }
            sheet = sheetName.toString();
            range = body.substring(index + 2);
        } else {
            int bang = body.lastIndexOf('!');
            if (bang >= 0) {
                sheet = body.substring(0, bang);
                range = body.substring(bang + 1);
            } else {
                range = body;
            }
        }
        range = range.replace("$", "").trim();
        if (range.isEmpty() || sheet != null && sheet.isBlank()) {
            return null;
        }
        return new String[] {sheet, range};
    }

    private static void addLink(Map<Long, Set<Long>> direct, long from, long to) {
        direct.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
    }

    private static FormulaGap gap(long from, String reason, String rawToken) {
        return new FormulaGap(from, reason, rawToken == null ? "" : rawToken);
    }

    private static String token(CellReferenceEdge edge) {
        if (edge.rawToken() != null) {
            return edge.rawToken();
        }
        return edge.targetRange() == null ? "" : edge.targetRange();
    }
}
