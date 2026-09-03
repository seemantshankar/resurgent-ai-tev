package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves structural formula reference tokens and persists {@code cell_reference}
 * rows (and review-queue entries for unresolvable references). Ranges stay
 * unexpanded; blank targets do not invent cells.
 */
public final class ReferenceResolver {

    private static final Pattern EXT_LINK_PATTERN = Pattern.compile("^\\[(\\d+)\\]");

    private final WorkspaceRepository repo;

    public ReferenceResolver(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void resolveAndPersist(long fromCellId, long fromWorksheetId, List<FormulaToken> tokens,
            ReferenceResolutionContext ctx, ReferenceStats stats) throws SQLException, IOException {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }

        for (FormulaToken token : tokens) {
            stats.total++;
            String targetSheet = token.targetSheetName();
            Long targetWorksheetId = null;
            Long resolvedCellId = null;
            Long externalLinkId = null;
            String unresolvedReason = null;

            if ("unresolved".equals(token.refKind())) {
                // Salvage path (ADR 0003): edges are unresolved by construction.
                unresolvedReason = "parse_error";
                queueReview(ctx.parseRunId(), "formula_reference",
                        "Salvaged reference from formula parse error: " + token.rawToken(),
                        Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId), ctx.now());
            }

            Integer extIndex = extractExtLinkIndex(token.rawToken());
            if (extIndex == null && token.targetSheetName() != null) {
                extIndex = extractExtLinkIndex(token.targetSheetName());
            }
            if (extIndex != null || "external".equals(token.refKind())) {
                if (extIndex != null) {
                    externalLinkId = ctx.externalLinkMap().get(extIndex);
                }
                if (externalLinkId == null && unresolvedReason == null) {
                    unresolvedReason = "external_unresolved";
                }
            }

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                targetWorksheetId = ctx.sheetNameToId().get(targetSheet);
            } else if (targetSheet == null && !"defined_name".equals(token.refKind())
                    && !"unresolved".equals(token.refKind())) {
                // Local unqualified reference: default target worksheet to the
                // referencing cell's sheet. Persisted target_sheet_name stays null.
                targetWorksheetId = fromWorksheetId;
            }
            String localSheetName = ctx.worksheetIdToSheetName().get(fromWorksheetId);

            boolean isExternal = externalLinkId != null
                    || (token.rawToken() != null && token.rawToken().contains("["))
                    || (targetSheet != null && targetSheet.contains("["))
                    || "external".equals(token.refKind());

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                if (targetWorksheetId == null && !isExternal && unresolvedReason == null) {
                    unresolvedReason = "sheet_not_found";
                    queueReview(ctx.parseRunId(), "formula_reference",
                            "Target sheet not found: " + targetSheet,
                            Map.of("rawToken", token.rawToken(), "targetSheet", targetSheet,
                                    "fromCellId", fromCellId),
                            ctx.now());
                }
            }

            if ("defined_name".equals(token.refKind()) && unresolvedReason == null) {
                if (!isKnownDefinedName(token, ctx.knownDefinedNames())) {
                    unresolvedReason = "defined_name_unresolved";
                    queueReview(ctx.parseRunId(), "formula_reference",
                            "Unresolved defined name: " + token.rawToken(),
                            Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId),
                            ctx.now());
                }
            }

            if (unresolvedReason == null && token.targetRange() != null
                    && !token.targetRange().contains(":")) {
                String targetSheetLookup = targetSheet != null ? targetSheet
                        : (localSheetName != null ? localSheetName
                                : (targetWorksheetId == null ? null
                                        : ctx.worksheetIdToSheetName().get(targetWorksheetId)));
                if (targetSheetLookup != null && ctx.cellCoordMap().containsKey(targetSheetLookup)) {
                    resolvedCellId = ctx.cellCoordMap().get(targetSheetLookup).get(token.targetRange());
                }
            }

            if ("external_unresolved".equals(unresolvedReason)) {
                queueReview(ctx.parseRunId(), "formula_reference",
                        "Unresolved external reference: " + token.rawToken(),
                        Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId),
                        ctx.now());
            }

            if (resolvedCellId != null || externalLinkId != null) {
                stats.resolved++;
            } else if (unresolvedReason != null) {
                stats.unresolved++;
            } else {
                // Ranges and known defined names: no single resolved_cell_id, but not an error.
                stats.resolved++;
            }

            repo.insertCellReference(new CellReferenceEdge(
                    fromCellId, token.tokenIndex(), token.rawToken(), token.refKind(),
                    targetSheet, targetWorksheetId, token.targetRange(), resolvedCellId,
                    externalLinkId, token.absRow(), token.absCol(), token.rowOffset(),
                    token.colOffset(), token.isWholeColumn(), token.isWholeRow(),
                    unresolvedReason));
        }
    }

    private static boolean isKnownDefinedName(FormulaToken token,
            java.util.Set<String> knownDefinedNames) {
        if (knownDefinedNames == null || knownDefinedNames.isEmpty()) {
            return false;
        }
        if (token.rawToken() != null && knownDefinedNames.contains(token.rawToken())) {
            return true;
        }
        return token.targetRange() != null && knownDefinedNames.contains(token.targetRange());
    }

    private static Integer extractExtLinkIndex(String token) {
        if (token == null) {
            return null;
        }
        Matcher m = EXT_LINK_PATTERN.matcher(token);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private void queueReview(long parseRunId, String category, String summary,
            Map<String, Object> detailMap, String now) throws SQLException, IOException {
        repo.insertReviewQueue(parseRunId, category, summary, Jsonb.toJson(detailMap),
                "Pending", false, now, null);
    }
}
