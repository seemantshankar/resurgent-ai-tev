package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.AbstractFunctionPtg;
import org.apache.poi.ss.formula.ptg.Area3DPtg;
import org.apache.poi.ss.formula.ptg.Area3DPxg;
import org.apache.poi.ss.formula.ptg.AreaPtg;
import org.apache.poi.ss.formula.ptg.NamePtg;
import org.apache.poi.ss.formula.ptg.NameXPtg;
import org.apache.poi.ss.formula.ptg.NameXPxg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.Ref3DPtg;
import org.apache.poi.ss.formula.ptg.Ref3DPxg;
import org.apache.poi.ss.formula.ptg.RefPtg;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Tokenizes formula strings into ordered reference tokens and function token views.
 * Primary path uses POI's {@link FormulaParser}; fallback path uses regex salvage on parse error.
 */
public final class FormulaTokenizer {

    private static final Pattern SALVAGE_REF_PATTERN = Pattern.compile(
            "('[^']+'|[A-Za-z0-9_]+!)?(\\$?[A-Z]+\\$?\\d+(?::\\$?[A-Z]+\\$?\\d+)?|\\[\\d+\\][^\\s(),+*/-]+)");

    private FormulaTokenizer() {}

    public static FormulaTokenizerResult tokenize(String formulaText, int formulaRow, int formulaCol, Map<String, String> definedNames) {
        if (formulaText == null || formulaText.isBlank()) {
            return new FormulaTokenizerResult("ok", List.of(), List.of());
        }

        String cleanFormula = formulaText.startsWith("=") ? formulaText.substring(1) : formulaText;

        try (XSSFWorkbook dummyWb = new XSSFWorkbook()) {
            registerSheetNames(cleanFormula, dummyWb);

            XSSFEvaluationWorkbook fpw = XSSFEvaluationWorkbook.create(dummyWb);
            Ptg[] ptgs = FormulaParser.parse(cleanFormula, fpw, FormulaType.CELL, -1);

            List<FormulaToken> tokens = new ArrayList<>();
            List<String> functionTokens = new ArrayList<>();
            int tokenIndex = 0;

            for (Ptg ptg : ptgs) {
                if (ptg instanceof AbstractFunctionPtg fPtg) {
                    functionTokens.add(fPtg.getName());
                } else if (ptg instanceof RefPtg ref) {
                    tokens.add(buildLocalCellToken(tokenIndex++, ref, formulaRow, formulaCol));
                } else if (ptg instanceof AreaPtg area) {
                    tokens.add(buildLocalRangeToken(tokenIndex++, area, formulaRow, formulaCol));
                } else if (ptg instanceof Ref3DPtg ref3d) {
                    String sheetName = restoreExternalSheetPrefix(safeGetSheetName(fpw, ref3d.getExternSheetIndex()), cleanFormula);
                    tokens.add(build3DCellToken(tokenIndex++, sheetName, ref3d.getRow() + 1, ref3d.getColumn() + 1,
                            !ref3d.isRowRelative(), !ref3d.isColRelative(), formulaRow, formulaCol, cleanFormula));
                } else if (ptg instanceof Ref3DPxg ref3dpxg) {
                    String sheetName = restoreExternalSheetPrefix(ref3dpxg.getSheetName(), cleanFormula);
                    tokens.add(build3DCellToken(tokenIndex++, sheetName, ref3dpxg.getRow() + 1, ref3dpxg.getColumn() + 1,
                            !ref3dpxg.isRowRelative(), !ref3dpxg.isColRelative(), formulaRow, formulaCol, cleanFormula));
                } else if (ptg instanceof Area3DPtg area3d) {
                    String sheetName = restoreExternalSheetPrefix(safeGetSheetName(fpw, area3d.getExternSheetIndex()), cleanFormula);
                    tokens.add(build3DRangeToken(tokenIndex++, sheetName, new RangeBounds(
                            area3d.getFirstRow() + 1, area3d.getFirstColumn() + 1,
                            area3d.getLastRow() + 1, area3d.getLastColumn() + 1,
                            !area3d.isFirstRowRelative(), !area3d.isFirstColRelative(),
                            !area3d.isLastRowRelative(), !area3d.isLastColRelative()),
                            formulaRow, formulaCol, cleanFormula));
                } else if (ptg instanceof Area3DPxg area3dpxg) {
                    String sheetName = restoreExternalSheetPrefix(area3dpxg.getSheetName(), cleanFormula);
                    tokens.add(build3DRangeToken(tokenIndex++, sheetName, new RangeBounds(
                            area3dpxg.getFirstRow() + 1, area3dpxg.getFirstColumn() + 1,
                            area3dpxg.getLastRow() + 1, area3dpxg.getLastColumn() + 1,
                            !area3dpxg.isFirstRowRelative(), !area3dpxg.isFirstColRelative(),
                            !area3dpxg.isLastRowRelative(), !area3dpxg.isLastColRelative()),
                            formulaRow, formulaCol, cleanFormula));
                } else if (ptg instanceof NamePtg namePtg) {
                    String name = fpw.getNameText(namePtg);
                    tokens.add(new FormulaToken(tokenIndex++, name, "defined_name", null, name, null, null, null, null, false, false));
                } else if (ptg instanceof NameXPtg nameX) {
                    String name = nameX.toFormulaString(fpw);
                    tokens.add(new FormulaToken(tokenIndex++, name, "external", null, name, null, null, null, null, false, false));
                } else if (ptg instanceof NameXPxg nameXpxg) {
                    String name = nameXpxg.toFormulaString();
                    tokens.add(new FormulaToken(tokenIndex++, name, "external", nameXpxg.getSheetName(), name, null, null, null, null, false, false));
                }
            }

            return new FormulaTokenizerResult("ok", tokens, functionTokens);
        } catch (Exception e) {
            List<FormulaToken> salvaged = salvageTokens(cleanFormula, formulaRow, formulaCol);
            return new FormulaTokenizerResult("parse_error", salvaged, List.of());
        }
    }

    private static String safeGetSheetName(XSSFEvaluationWorkbook fpw, int sheetIndex) {
        try {
            return fpw.getSheetName(sheetIndex);
        } catch (Exception e) {
            return null;
        }
    }

    private static String restoreExternalSheetPrefix(String sheetName, String formulaText) {
        if (sheetName == null || sheetName.startsWith("[")) {
            return sheetName;
        }
        Matcher m = Pattern.compile("\\[(\\d+)\\]" + Pattern.quote(sheetName)).matcher(formulaText);
        if (m.find()) {
            return "[" + m.group(1) + "]" + sheetName;
        }
        return sheetName;
    }

    private static void registerSheetNames(String formulaText, XSSFWorkbook wb) {
        Pattern pattern = Pattern.compile("(\\[\\d+\\][^'!]+|'\\s*\\[\\d+\\][^']+'|'[^']+'|[A-Za-z0-9_]+)!");
        Matcher matcher = pattern.matcher(formulaText);
        while (matcher.find()) {
            String sheet = matcher.group(1);
            if (sheet != null) {
                if (sheet.startsWith("'") && sheet.endsWith("'")) {
                    sheet = sheet.substring(1, sheet.length() - 1);
                }
                if (wb.getSheet(sheet) == null) {
                    try {
                        wb.createSheet(sheet);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static FormulaToken buildLocalCellToken(int index, RefPtg ref, int formulaRow, int formulaCol) {
        int targetRow = ref.getRow() + 1;
        int targetCol = ref.getColumn() + 1;
        boolean absRow = !ref.isRowRelative();
        boolean absCol = !ref.isColRelative();
        String colStr = CellReference.convertNumToColString(ref.getColumn());
        String cellRef = colStr + targetRow;
        String rawToken = (absCol ? "$" : "") + colStr + (absRow ? "$" : "") + targetRow;
        return new FormulaToken(index, rawToken, "local_cell", null, cellRef, absRow, absCol,
                targetRow - formulaRow, targetCol - formulaCol, false, false);
    }

    /** Shared by local and 3D range builders: is this Excel's shorthand for a whole column/row? */
    private static boolean isWholeColumn(int firstRow, int lastRow) {
        return firstRow == 1 && lastRow >= 65536;
    }

    private static boolean isWholeRow(int firstCol, int lastCol) {
        return firstCol == 1 && lastCol >= 256;
    }

    /** Shared by local and 3D range builders: "firstCell:lastCell" from 1-based row/col bounds. */
    private static String rangeString(int firstRow, int firstCol, int lastRow, int lastCol) {
        String firstCell = CellReference.convertNumToColString(firstCol - 1) + firstRow;
        String lastCell = CellReference.convertNumToColString(lastCol - 1) + lastRow;
        return firstCell + ":" + lastCell;
    }

    private static FormulaToken buildLocalRangeToken(int index, AreaPtg area, int formulaRow, int formulaCol) {
        RangeBounds bounds = new RangeBounds(
                area.getFirstRow() + 1, area.getFirstColumn() + 1,
                area.getLastRow() + 1, area.getLastColumn() + 1,
                !area.isFirstRowRelative(), !area.isFirstColRelative(),
                !area.isLastRowRelative(), !area.isLastColRelative());

        return new FormulaToken(index, bounds.asWritten(), "local_range", null, bounds.normalized(),
                bounds.absFirstRow(), bounds.absFirstCol(),
                bounds.firstRow() - formulaRow, bounds.firstCol() - formulaCol,
                bounds.isWholeColumn(), bounds.isWholeRow());
    }

    private static FormulaToken build3DCellToken(int index, String sheetName, int targetRow, int targetCol,
            boolean absRow, boolean absCol, int formulaRow, int formulaCol, String formulaText) {
        String colStr = CellReference.convertNumToColString(targetCol - 1);
        String cellRef = colStr + targetRow;
        String coordinate = (absCol ? "$" : "") + colStr + (absRow ? "$" : "") + targetRow;
        String rawToken = sheetPrefixAsWritten(sheetName, coordinate, formulaText) + coordinate;
        String kind = sheetName != null && sheetName.startsWith("[") ? "external" : "cross_sheet_cell";
        return new FormulaToken(index, rawToken, kind, sheetName, cellRef, absRow, absCol,
                targetRow - formulaRow, targetCol - formulaCol, false, false);
    }

    private static FormulaToken build3DRangeToken(int index, String sheetName, RangeBounds bounds,
            int formulaRow, int formulaCol, String formulaText) {
        String writtenRange = bounds.asWritten();
        String rawToken = sheetPrefixAsWritten(sheetName, writtenRange, formulaText) + writtenRange;
        String kind = sheetName != null && sheetName.startsWith("[") ? "external" : "cross_sheet_range";
        return new FormulaToken(index, rawToken, kind, sheetName, bounds.normalized(),
                bounds.absFirstRow(), bounds.absFirstCol(),
                bounds.firstRow() - formulaRow, bounds.firstCol() - formulaCol,
                bounds.isWholeColumn(), bounds.isWholeRow());
    }

    /**
     * A range's bounds and the absolute markers each endpoint carries, as parsed. Bundled because
     * the ten values move together through range rendering, whole-column detection, and offsets.
     */
    private record RangeBounds(int firstRow, int firstCol, int lastRow, int lastCol,
            boolean absFirstRow, boolean absFirstCol, boolean absLastRow, boolean absLastCol) {

        boolean isWholeColumn() {
            return FormulaTokenizer.isWholeColumn(firstRow, lastRow);
        }

        boolean isWholeRow() {
            return FormulaTokenizer.isWholeRow(firstCol, lastCol);
        }

        /** Normalized {@code first:last}, the resolution lookup key (§4.4). */
        String normalized() {
            return rangeString(firstRow, firstCol, lastRow, lastCol);
        }

        /**
         * The coordinate as the formula writes it. {@link #normalized()} drops the absolute markers
         * and expands a whole-column reference to its row bounds — right for the lookup key, wrong
         * for the raw token, which §10.9 keeps verbatim.
         *
         * <p>Read entirely off the parsed ptg rather than searched for in the formula text, so two
         * ranges over the same cells differing only in their markers cannot be confused for one
         * another, and the result stays a pure function of the parse (§3).
         */
        String asWritten() {
            String firstColStr = CellReference.convertNumToColString(firstCol - 1);
            String lastColStr = CellReference.convertNumToColString(lastCol - 1);
            if (isWholeColumn()) {
                // Written by its columns alone: A:A, never the row bounds normalized() expands to.
                return (absFirstCol ? "$" : "") + firstColStr + ":" + (absLastCol ? "$" : "") + lastColStr;
            }
            if (isWholeRow()) {
                return (absFirstRow ? "$" : "") + firstRow + ":" + (absLastRow ? "$" : "") + lastRow;
            }
            return (absFirstCol ? "$" : "") + firstColStr + (absFirstRow ? "$" : "") + firstRow
                    + ":" + (absLastCol ? "$" : "") + lastColStr + (absLastRow ? "$" : "") + lastRow;
        }
    }

    /**
     * Renders the {@code sheet!} prefix the way the formula itself writes it.
     *
     * <p>Excel quotes a sheet name only where the name requires it, so re-rendering every name
     * quoted yields a raw token that does not occur in the formula it came from — breaking §10.9's
     * "preserve raw formula verbatim" and, downstream, {@link FormulaSkeletonGenerator}, which
     * abstracts a reference by replacing its raw span and silently leaves the reference intact when
     * that span is not literally present.
     *
     * <p>Quoting is a property of the source text alone — the parsed ptg does not record it — so
     * unlike the coordinate it has to be recovered from the formula. Both candidates are tested
     * with the coordinate attached, so a sheet name can never match inside a longer name that
     * shares its prefix. Where neither is found the quoted form is returned, being always
     * Excel-legal; a caller that reaches it has a formula whose text disagrees with its own parse.
     */
    private static String sheetPrefixAsWritten(String sheetName, String coordinate, String formulaText) {
        if (sheetName == null) {
            return "";
        }
        String quoted = "'" + sheetName + "'!";
        if (formulaText != null && formulaText.contains(sheetName + "!" + coordinate)
                && !formulaText.contains(quoted + coordinate)) {
            return sheetName + "!";
        }
        return quoted;
    }

    private static List<FormulaToken> salvageTokens(String formulaText, int formulaRow, int formulaCol) {
        List<FormulaToken> salvaged = new ArrayList<>();
        Matcher matcher = SALVAGE_REF_PATTERN.matcher(formulaText);
        int index = 0;
        while (matcher.find()) {
            String rawToken = matcher.group();
            String sheet = matcher.group(1);
            if (sheet != null) {
                if (sheet.startsWith("'") && sheet.endsWith("'!")) {
                    sheet = sheet.substring(1, sheet.length() - 2);
                } else if (sheet.endsWith("!")) {
                    sheet = sheet.substring(0, sheet.length() - 1);
                }
            }
            salvaged.add(new FormulaToken(index++, rawToken, "unresolved", sheet, matcher.group(2), null, null, null, null, false, false));
        }
        return salvaged;
    }
}
