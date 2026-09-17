package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;

/**
 * Builds and persists formula dependency annotations after interpretations exist.
 * Does not open or commit its own transaction. Range expansion is bounded;
 * blank/unresolved/external targets never invent cells. Shared dependency
 * path/kind is descriptive only — never an economic rollup instruction.
 */
public class FormulaAnnotationWriter {

    /** Max cells examined when expanding a range reference. */
    static final int MAX_RANGE_CELLS = 256;

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^\\$?([A-Za-z]+)\\$?(\\d+):\\$?([A-Za-z]+)\\$?(\\d+)$");

    /**
     * Replace formula annotations for the parse run using already-written
     * interpretations for dependency context.
     */
    public void write(WorkspaceRepository repo, long parseRunId) throws SQLException {
        Objects.requireNonNull(repo, "repo");
        List<CellInterpretation> interpretations =
                repo.selectCellInterpretationsForParseRun(parseRunId);
        if (interpretations.isEmpty()) {
            return;
        }
        Map<Long, CellInterpretation> interpretationByCell = new HashMap<>();
        for (CellInterpretation row : interpretations) {
            interpretationByCell.put(row.cellId(), row);
        }
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        Map<Long, InterpretationCellView> cellById = new HashMap<>();
        Map<Long, Map<String, InterpretationCellView>> byWorksheetCoord = new HashMap<>();
        Map<Long, Map<Long, InterpretationCellView>> byWorksheetRowCol = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            cellById.put(cell.cellId(), cell);
            byWorksheetCoord
                    .computeIfAbsent(cell.worksheetId(), id -> new HashMap<>())
                    .put(cell.coord().toUpperCase(Locale.ROOT), cell);
            long key = (((long) cell.rowNum()) << 32) | (cell.colNum() & 0xffffffffL);
            byWorksheetRowCol
                    .computeIfAbsent(cell.worksheetId(), id -> new HashMap<>())
                    .put(key, cell);
        }
        Map<Long, List<CellReferenceEdge>> edgesByFrom = new HashMap<>();
        for (CellReferenceEdge edge : repo.selectCellReferencesForParseRun(parseRunId)) {
            edgesByFrom
                    .computeIfAbsent(edge.fromCellId(), id -> new ArrayList<>())
                    .add(edge);
        }

        for (CellInterpretation interpretation : interpretations) {
            if (!"formula".equals(interpretation.valueOrigin())) {
                continue;
            }
            List<CellReferenceEdge> edges =
                    edgesByFrom.getOrDefault(interpretation.cellId(), List.of());
            InterpretationCellView formulaCell = cellById.get(interpretation.cellId());
            String formulaText = interpretation.formulaText();
            int ordinal = 0;
            for (CellReferenceEdge edge : edges) {
                FormulaAnnotation annotation = annotate(
                        parseRunId,
                        interpretation.cellId(),
                        ordinal++,
                        edge,
                        formulaText,
                        formulaCell,
                        cellById,
                        interpretationByCell,
                        byWorksheetCoord,
                        byWorksheetRowCol);
                long annotationId = repo.insertFormulaAnnotation(annotation);
                for (FormulaAnnotationMember member : annotation.members()) {
                    repo.insertFormulaAnnotationMember(annotationId, member);
                }
            }
        }
    }

    FormulaAnnotation annotate(
            long parseRunId,
            long cellId,
            int ordinal,
            CellReferenceEdge edge,
            String formulaText,
            InterpretationCellView formulaCell,
            Map<Long, InterpretationCellView> cellById,
            Map<Long, CellInterpretation> interpretationByCell,
            Map<Long, Map<String, InterpretationCellView>> byWorksheetCoord,
            Map<Long, Map<Long, InterpretationCellView>> byWorksheetRowCol) {
        String enclosing = enclosingFunction(formulaText, edge.rawToken());
        if (enclosing == null) {
            enclosing = enclosingFunction(formulaText, edge.targetRange());
        }
        if (isExternal(edge)) {
            return annotation(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    AnnotationCompleteness.EXTERNAL,
                    enclosing,
                    null,
                    null,
                    List.of());
        }
        if (edge.unresolvedReason() != null && !edge.unresolvedReason().isBlank()) {
            return annotation(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    AnnotationCompleteness.UNRESOLVED,
                    enclosing,
                    null,
                    null,
                    List.of());
        }
        if ("defined_name".equals(edge.refKind())) {
            return annotation(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    AnnotationCompleteness.UNRESOLVED,
                    enclosing,
                    null,
                    null,
                    List.of());
        }

        Long worksheetId = edge.targetWorksheetId();
        if (worksheetId == null && formulaCell != null) {
            worksheetId = formulaCell.worksheetId();
        }
        String range = edge.targetRange();
        if (range != null && range.contains(":")) {
            return annotateRange(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    enclosing,
                    worksheetId,
                    range,
                    interpretationByCell,
                    byWorksheetRowCol);
        }

        List<FormulaAnnotationMember> members = new ArrayList<>();
        InterpretationCellView target = null;
        if (edge.resolvedCellId() != null) {
            target = cellById.get(edge.resolvedCellId());
        }
        if (target == null && range != null && worksheetId != null) {
            target = lookupCoord(byWorksheetCoord, worksheetId, range);
        }
        if (target != null) {
            members.add(member(0, target, interpretationByCell));
        }

        String completeness = members.isEmpty()
                ? AnnotationCompleteness.INCOMPLETE
                : AnnotationCompleteness.COMPLETE;
        SharedHead shared = sharedHead(members);
        return annotation(
                parseRunId,
                cellId,
                ordinal,
                edge,
                completeness,
                enclosing,
                shared.path(),
                shared.kind(),
                members);
    }

    private FormulaAnnotation annotateRange(
            long parseRunId,
            long cellId,
            int ordinal,
            CellReferenceEdge edge,
            String enclosing,
            Long worksheetId,
            String range,
            Map<Long, CellInterpretation> interpretationByCell,
            Map<Long, Map<Long, InterpretationCellView>> byWorksheetRowCol) {
        if (edge.isWholeColumn() || edge.isWholeRow()) {
            return annotation(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    AnnotationCompleteness.TRUNCATED,
                    enclosing,
                    null,
                    null,
                    List.of());
        }
        Matcher matcher = RANGE_PATTERN.matcher(range.trim());
        if (!matcher.matches() || worksheetId == null) {
            return annotation(
                    parseRunId,
                    cellId,
                    ordinal,
                    edge,
                    AnnotationCompleteness.UNRESOLVED,
                    enclosing,
                    null,
                    null,
                    List.of());
        }
        int firstCol = CellReference.convertColStringToIndex(matcher.group(1)) + 1;
        int firstRow = Integer.parseInt(matcher.group(2));
        int lastCol = CellReference.convertColStringToIndex(matcher.group(3)) + 1;
        int lastRow = Integer.parseInt(matcher.group(4));
        if (firstRow > lastRow) {
            int tmp = firstRow;
            firstRow = lastRow;
            lastRow = tmp;
        }
        if (firstCol > lastCol) {
            int tmp = firstCol;
            firstCol = lastCol;
            lastCol = tmp;
        }

        Map<Long, InterpretationCellView> sheetIndex =
                byWorksheetRowCol.getOrDefault(worksheetId, Map.of());
        List<FormulaAnnotationMember> members = new ArrayList<>();
        boolean incomplete = false;
        boolean truncated = false;
        int examined = 0;
        int memberOrdinal = 0;
        outer:
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                examined++;
                if (examined > MAX_RANGE_CELLS) {
                    truncated = true;
                    break outer;
                }
                long key = (((long) row) << 32) | (col & 0xffffffffL);
                InterpretationCellView target = sheetIndex.get(key);
                if (target == null) {
                    incomplete = true;
                    continue;
                }
                members.add(member(memberOrdinal++, target, interpretationByCell));
            }
        }

        String completeness;
        if (truncated) {
            completeness = AnnotationCompleteness.TRUNCATED;
        } else if (incomplete) {
            completeness = AnnotationCompleteness.INCOMPLETE;
        } else {
            completeness = AnnotationCompleteness.COMPLETE;
        }
        SharedHead shared = sharedHead(members);
        return annotation(
                parseRunId,
                cellId,
                ordinal,
                edge,
                completeness,
                enclosing,
                shared.path(),
                shared.kind(),
                members);
    }

    private static FormulaAnnotation annotation(
            long parseRunId,
            long cellId,
            int ordinal,
            CellReferenceEdge edge,
            String completeness,
            String enclosing,
            String sharedPath,
            String sharedKind,
            List<FormulaAnnotationMember> members) {
        return new FormulaAnnotation(
                parseRunId,
                cellId,
                ordinal,
                edge.rawToken(),
                edge.refKind(),
                edge.targetSheetName(),
                edge.targetRange(),
                completeness,
                enclosing,
                sharedPath,
                sharedKind,
                members);
    }

    private static FormulaAnnotationMember member(
            int ordinal,
            InterpretationCellView target,
            Map<Long, CellInterpretation> interpretationByCell) {
        CellInterpretation interpretation = interpretationByCell.get(target.cellId());
        return new FormulaAnnotationMember(
                ordinal,
                target.cellId(),
                target.coord(),
                interpretation == null ? null : interpretation.nomenclaturePath(),
                interpretation == null ? null : interpretation.nomenclatureStatus());
    }

    private static InterpretationCellView lookupCoord(
            Map<Long, Map<String, InterpretationCellView>> byWorksheetCoord,
            Long worksheetId,
            String coord) {
        if (worksheetId == null || coord == null) {
            return null;
        }
        Map<String, InterpretationCellView> sheet = byWorksheetCoord.get(worksheetId);
        if (sheet == null) {
            return null;
        }
        return sheet.get(coord.toUpperCase(Locale.ROOT));
    }

    private static boolean isExternal(CellReferenceEdge edge) {
        if ("external".equals(edge.refKind())) {
            return true;
        }
        if (edge.externalLinkId() != null) {
            return true;
        }
        String raw = edge.rawToken();
        return raw != null && raw.contains("[");
    }

    static String enclosingFunction(String formulaText, String rawToken) {
        if (formulaText == null || rawToken == null || rawToken.isBlank()) {
            return null;
        }
        int idx = indexOfIgnoreCase(formulaText, rawToken);
        if (idx < 0) {
            // Raw token may include sheet prefix while targetRange does not.
            return null;
        }
        int i = idx - 1;
        while (i >= 0 && Character.isWhitespace(formulaText.charAt(i))) {
            i--;
        }
        if (i < 0 || formulaText.charAt(i) != '(') {
            return null;
        }
        i--;
        while (i >= 0 && Character.isWhitespace(formulaText.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0 && isFunctionNameChar(formulaText.charAt(i))) {
            i--;
        }
        if (end <= i + 1) {
            return null;
        }
        return formulaText.substring(i + 1, end).toUpperCase(Locale.ROOT);
    }

    private static boolean isFunctionNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static SharedHead sharedHead(List<FormulaAnnotationMember> members) {
        List<String> paths = new ArrayList<>();
        for (FormulaAnnotationMember member : members) {
            if (member.nomenclaturePath() != null && !member.nomenclaturePath().isBlank()) {
                paths.add(member.nomenclaturePath());
            }
        }
        if (paths.isEmpty()) {
            return new SharedHead(null, null);
        }
        String first = paths.get(0);
        boolean allSame = true;
        for (String path : paths) {
            if (!first.equals(path)) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            return new SharedHead(first, SharedDependencyKind.LEAF);
        }
        String ancestor = commonAncestor(paths);
        if (ancestor == null || ancestor.isBlank()) {
            return new SharedHead(null, null);
        }
        return new SharedHead(ancestor, SharedDependencyKind.ANCESTOR);
    }

    private static String commonAncestor(List<String> paths) {
        String[][] segments = new String[paths.size()][];
        int minLen = Integer.MAX_VALUE;
        for (int i = 0; i < paths.size(); i++) {
            segments[i] = paths.get(i).split("\\s*>\\s*");
            minLen = Math.min(minLen, segments[i].length);
        }
        List<String> common = new ArrayList<>();
        for (int i = 0; i < minLen; i++) {
            String part = segments[0][i];
            boolean match = true;
            for (int j = 1; j < segments.length; j++) {
                if (!part.equals(segments[j][i])) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                break;
            }
            common.add(part);
        }
        if (common.isEmpty()) {
            return null;
        }
        // Ancestor must be shorter than at least one full path.
        boolean proper = false;
        for (String[] segs : segments) {
            if (segs.length > common.size()) {
                proper = true;
                break;
            }
        }
        if (!proper) {
            return null;
        }
        return String.join(" > ", common);
    }

    private record SharedHead(String path, String kind) {}
}
