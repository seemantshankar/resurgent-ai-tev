package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structural references from an Excel formula string.
 *
 * <p>External references use the 1-based workbook index syntax
 * {@code [n]Sheet!Ref}. Defined-name references are detected by scanning for
 * known names as whole-word tokens.
 */
public final class FormulaReferenceExtractor {

    /**
     * External reference tokens:
     * <ul>
     *   <li>{@code [1]Sheet!A1}</li>
     *   <li>{@code ' [1]Sheet Name '!A1}</li>
     *   <li>{@code [1]!DefinedName}</li>
     * </ul>
     */
    private static final Pattern EXTERNAL_REF_PATTERN = Pattern.compile(
            "(?:'\\[\\d+\\][^']+'|\\[\\d+\\][A-Za-z0-9_]+)(?:![A-Za-z0-9_$.:$]+)?|\\[\\d+\\]![A-Za-z0-9_]+");

    private static final int MAX_DEFINED_NAME_LENGTH = 256;

    private FormulaReferenceExtractor() {}

    /**
     * Extracts external references only. Defined-name refs and local sheet refs
     * are left empty/null.
     */
    public static FormulaReferences extract(String formulaText) {
        return extract(formulaText, List.of());
    }

    /**
     * Pattern for local cell references (A1, $A$1, Sheet!A1, 'Sheet Name'!A1).
     * Captures the optional sheet qualifier and the cell coordinate.
     */
    private static final Pattern LOCAL_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([A-Za-z0-9_]+))!([A-Z]+\\d+)|([A-Z]+\\d+)");

    /**
     * Extracts external references and defined-name references.
     *
     * @param formulaText the raw Excel formula (e.g. {@code =[1]Other!A1+MyName})
     * @param definedNames known defined names to look for as whole-word tokens
     */
    public static FormulaReferences extract(String formulaText, Collection<String> definedNames) {
        List<String> externalRefs = new ArrayList<>();
        Matcher externalMatcher = EXTERNAL_REF_PATTERN.matcher(formulaText);
        while (externalMatcher.find()) {
            externalRefs.add(externalMatcher.group());
        }

        List<String> definedNameRefs = new ArrayList<>();
        if (definedNames != null) {
            for (String name : definedNames) {
                if (name == null || name.isBlank() || name.length() > MAX_DEFINED_NAME_LENGTH) {
                    continue;
                }
                if (isNameReferenced(formulaText, name)) {
                    definedNameRefs.add(name);
                }
            }
        }

        // Local sheet refs are not extracted yet; leave null per Ticket 06 scope.
        return new FormulaReferences(externalRefs, null, definedNameRefs);
    }

    /**
     * Extracts local cell references that belong to the given sheet name and
     * returns them as POI {@link CellReference}s. Used for computing the real
     * worksheet bounding box from same-sheet formula precedents.
     */
    public static List<org.apache.poi.ss.util.CellReference> extractLocalRefs(
            String formulaText, String sheetName) {
        List<org.apache.poi.ss.util.CellReference> refs = new ArrayList<>();
        if (formulaText == null || sheetName == null) {
            return refs;
        }
        Matcher m = LOCAL_REF_PATTERN.matcher(formulaText);
        while (m.find()) {
            String quotedSheet = m.group(1);
            String unquotedSheet = m.group(2);
            String cellCoord = m.group(3) != null ? m.group(3) : m.group(4);
            String refSheet = quotedSheet != null ? quotedSheet : unquotedSheet;
            if (refSheet != null && !sheetName.equals(refSheet)) {
                continue;
            }
            try {
                refs.add(new org.apache.poi.ss.util.CellReference(cellCoord));
            } catch (IllegalArgumentException e) {
                // Ignore unparseable tokens.
            }
        }
        return refs;
    }

    private static boolean isNameReferenced(String formulaText, String name) {
        String regex = "(?<!\\w)" + Pattern.quote(name) + "(?!\\w)";
        return Pattern.compile(regex).matcher(formulaText).find();
    }
}
