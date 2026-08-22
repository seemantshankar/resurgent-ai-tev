package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.resurgent.tev.parser.db.CellReferenceRow;
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

            Integer extIndex = extractExtLinkIndex(token.rawToken());
            if (extIndex == null && token.targetSheetName() != null) {
                extIndex = extractExtLinkIndex(token.targetSheetName());
            }
            if (extIndex != null || "external".equals(token.refKind())) {
                if (extIndex != null) {
                    externalLinkId = ctx.externalLinkMap().get(extIndex);
                }
                if (externalLinkId == null) {
                    unresolvedReason = "external_unresolved";
                }
            }

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                targetWorksheetId = ctx.sheetNameToId().get(targetSheet);
            } else if (targetSheet == null && !"defined_name".equals(token.refKind())) {
                // Local (unqualified) reference: the tokenizer leaves targetSheetName null for
                // same-sheet references (local_cell/local_range), since Excel formulas don't
                // repeat "ThisSheet!" for same-sheet refs. Default the target worksheet to the
                // referencing cell's own worksheet so these actually resolve/expand instead of
                // silently carrying a null target forever. target_sheet_name itself is left
                // null (unchanged persisted meaning: null = same sheet as the reference).
                targetWorksheetId = fromWorksheetId;
            }
            String localSheetName = ctx.worksheetIdToSheetName().get(fromWorksheetId);

            // B1: no fallback binding to "some" external link when the sheet name simply
            // doesn't resolve locally — that used to fabricate a match for genuinely local
            // sheet_not_found cases. isExternal below is computed independently from the raw
            // token / target sheet / ref kind, so removing the fallback doesn't lose real
            // external-ref detection.
            boolean isExternal = externalLinkId != null || (token.rawToken() != null && token.rawToken().contains("["))
                    || (targetSheet != null && targetSheet.contains("[")) || "external".equals(token.refKind());

            if (targetSheet != null && !targetSheet.isBlank() && !targetSheet.startsWith("[")) {
                if (targetWorksheetId == null && !isExternal && unresolvedReason == null) {
                    unresolvedReason = "sheet_not_found";
                    queueReview(ctx.parseRunId(), "formula_reference", "Target sheet not found: " + targetSheet,
                            Map.of("rawToken", token.rawToken(), "targetSheet", targetSheet, "fromCellId", fromCellId), ctx.now());
                }
            }

            if ("defined_name".equals(token.refKind()) && unresolvedReason == null) {
                // B2: only unresolved when the name is NOT a known workbook-level defined name.
                // A known defined name legitimately has no resolved_cell_id (resolving a
                // defined name to a specific cell is out of scope) but is not an error.
                if (!isKnownDefinedName(token, ctx.knownDefinedNames())) {
                    unresolvedReason = "defined_name_unresolved";
                    queueReview(ctx.parseRunId(), "formula_reference", "Unresolved defined name: " + token.rawToken(),
                            Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId), ctx.now());
                }
            }

            if (unresolvedReason == null && token.targetRange() != null && !token.targetRange().contains(":")) {
                String targetSheetLookup = targetSheet != null ? targetSheet
                        : (localSheetName != null ? localSheetName
                                : (targetWorksheetId == null ? null : ctx.worksheetIdToSheetName().get(targetWorksheetId)));
                if (targetSheetLookup != null && ctx.cellCoordMap().containsKey(targetSheetLookup)) {
                    resolvedCellId = ctx.cellCoordMap().get(targetSheetLookup).get(token.targetRange());
                }
            }

            if ("external_unresolved".equals(unresolvedReason)) {
                queueReview(ctx.parseRunId(), "formula_reference", "Unresolved external reference: " + token.rawToken(),
                        Map.of("rawToken", token.rawToken(), "fromCellId", fromCellId), ctx.now());
            }

            if (resolvedCellId != null || externalLinkId != null) {
                stats.resolved++;
            } else if (unresolvedReason != null) {
                stats.unresolved++;
            } else {
                // Ranges and known-defined-names: legitimately unresolved to a specific cell
                // id but not an error condition either. Count as resolved for reconciliation
                // purposes only when a reason isn't warranted; otherwise this would never
                // balance against total.
                stats.resolved++;
            }

            repo.insertCellReference(new CellReferenceRow(fromCellId, token.tokenIndex(), token.rawToken(),
                    token.refKind(), targetSheet, targetWorksheetId, token.targetRange(), resolvedCellId,
                    externalLinkId, token.absRow(), token.absCol(), token.rowOffset(), token.colOffset(),
                    token.isWholeColumn(), token.isWholeRow(), unresolvedReason));
        }
    }

    private static boolean isKnownDefinedName(FormulaToken token, java.util.Set<String> knownDefinedNames) {
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

    private void queueReview(long parseRunId, String category, String summary, Map<String, Object> detailMap, String now)
            throws SQLException, IOException {
        repo.insertReviewQueue(parseRunId, category, summary, Jsonb.toJson(detailMap), "Pending", false, now, null);
    }
}
