package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Resolves structural formula reference tokens and persists {@code cell_reference} rows
 * and review queue entries for unresolvable references.
 */
public final class ReferenceResolver {

    private static final Pattern EXT_LINK_PATTERN = Pattern.compile("^\\[(\\d+)\\]");

    private final WorkspaceRepository repo;

    public ReferenceResolver(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void resolveAndPersist(long fromCellId, List<FormulaToken> tokens, long parseRunId,
            Map<String, Long> sheetNameToId, Map<Integer, Long> externalLinkMap,
            Map<String, Map<String, Long>> cellCoordMap, String now) throws SQLException, IOException {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }

        for (FormulaToken token : tokens) {
            String targetSheet = token.targetSheetName();
            Long targetWorksheetId = null;
            Long resolvedCellId = null;
            Long externalLinkId = null;
            String unresolvedReason = null;

            Integer extIndex = extractExtLinkIndex(token.rawToken());
            if (extIndex == null && token.targetSheetName() != null) {
                extIndex = extractExtLinkIndex(token.targetSheetName());
            }
            if (extIndex != null || "external".equals(token.refKind())) {
                if (extIndex != null) {
                    externalLinkId = externalLinkMap.get(extIndex);
                }
                if (externalLinkId == null) {
                    unresolvedReason = "external_unresolved";
                }
            }

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                targetWorksheetId = sheetNameToId.get(targetSheet);
            }

            if (targetWorksheetId == null && externalLinkId == null && !externalLinkMap.isEmpty()) {
                externalLinkId = externalLinkMap.values().iterator().next();
            }

            boolean isExternal = externalLinkId != null || (token.rawToken() != null && token.rawToken().contains("[")) || (targetSheet != null && targetSheet.contains("[")) || "external".equals(token.refKind());

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                if (targetWorksheetId == null && !isExternal && unresolvedReason == null) {
                    unresolvedReason = "sheet_not_found";
                    queueReview(parseRunId, "formula_reference", "Target sheet not found: " + targetSheet,
                            Map.of("rawToken", token.rawToken(), "targetSheet", targetSheet, "fromCellId", fromCellId), now);
                }
            }

            if ("defined_name".equals(token.refKind()) && unresolvedReason == null) {
                unresolvedReason = "defined_name_unresolved";
                queueReview(parseRunId, "formula_reference", "Unresolved defined name: " + token.rawToken(),
                        Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId), now);
            }

            if (unresolvedReason == null && token.targetRange() != null && !token.targetRange().contains(":")) {
                String targetSheetLookup = targetSheet != null ? targetSheet : getSheetNameForWorksheetId(sheetNameToId, targetWorksheetId);
                if (targetSheetLookup != null && cellCoordMap.containsKey(targetSheetLookup)) {
                    resolvedCellId = cellCoordMap.get(targetSheetLookup).get(token.targetRange());
                }
            }

            repo.insertCellReference(fromCellId, token.tokenIndex(), token.rawToken(), token.refKind(),
                    targetSheet, targetWorksheetId, token.targetRange(), resolvedCellId,
                    externalLinkId, token.absRow(), token.absCol(), token.rowOffset(), token.colOffset(),
                    token.isWholeColumn(), token.isWholeRow(), unresolvedReason);
        }
    }

    private static Integer extractExtLinkIndex(String token) {
        if (token == null) {
            return null;
        }
        Matcher m = EXT_LINK_PATTERN.matcher(token);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String getSheetNameForWorksheetId(Map<String, Long> sheetNameToId, Long worksheetId) {
        if (worksheetId == null) {
            return null;
        }
        for (Map.Entry<String, Long> entry : sheetNameToId.entrySet()) {
            if (worksheetId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void queueReview(long parseRunId, String category, String summary, Map<String, Object> detailMap, String now)
            throws SQLException, IOException {
        repo.insertReviewQueue(parseRunId, category, summary, Jsonb.toJson(detailMap), "Pending", false, now, null);
    }
}
