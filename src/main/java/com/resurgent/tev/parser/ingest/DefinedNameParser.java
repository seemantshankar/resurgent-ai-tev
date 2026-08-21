package com.resurgent.tev.parser.ingest;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Collects every defined name in a workbook.
 *
 * <p>Both workbook-scoped and sheet-scoped names are included; the returned map
 * keys are the user-visible name names and the values are the formulas/refs they
 * point to.
 */
public final class DefinedNameParser {

    private DefinedNameParser() {}

    /**
     * Returns an ordered map of {@code name -> refersToFormula} for every defined
     * name in the workbook.
     */
    public static Map<String, String> parse(Workbook workbook) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Name name : workbook.getAllNames()) {
            String formula = name.getRefersToFormula();
            names.put(name.getNameName(), formula);
        }
        return names;
    }
}
