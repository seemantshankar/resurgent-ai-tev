package com.resurgent.tev.parser.classify;

import java.util.regex.Pattern;

/**
 * Detects soft-leaf names that are copied workbook text rather than categories.
 * Supplier lines, tax rates, quantities, specs, formula errors, and Less:/Add:
 * qualifiers stay on the row as evidence; they are never minted as leaves.
 * A short category name such as {@code Closing Stock} is allowed even when it
 * matches the row label.
 */
final class TranscribedLeaf {

    static final String REJECT_REASON = "transcribed_label";

    private static final Pattern BROKEN_REF = Pattern.compile("#ref!", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPLIER = Pattern.compile(
            "\\bsupplier\\b|pvt\\.?\\s*ltd|\\bltd\\.?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAX_RATE = Pattern.compile(
            "\\b(cgst|sgst|igst|gst|vat|tds|cess)\\b|@\\s*\\d+(\\.\\d+)?\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_OR_RATE = Pattern.compile(
            "\\d[\\d,]*\\s*(kva|kw|hp|sq\\.?\\s*ft|sqft|mm|mtr|meter|ft|ltr|kg|ton|rs\\.?/|rs\\b)|@\\s*\\d",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC_CODE = Pattern.compile(
            "\"|ø|\\bpf\\b|\\bhz\\b|\\d{3,}\\s*v\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION_OR_QUALIFIER = Pattern.compile(
            "^\\s*(less|add)\\s*[:\\-]|as per (estimate|quotation)|taken as|including gst",
            Pattern.CASE_INSENSITIVE);

    private TranscribedLeaf() {}

    /** True when {@code leafName} is raw workbook text rather than a category name. */
    static boolean isTranscribed(String leafName) {
        if (leafName == null || leafName.isBlank()) {
            return false;
        }
        String leaf = leafName.trim();
        return BROKEN_REF.matcher(leaf).find()
                || SUPPLIER.matcher(leaf).find()
                || TAX_RATE.matcher(leaf).find()
                || QUANTITY_OR_RATE.matcher(leaf).find()
                || SPEC_CODE.matcher(leaf).find()
                || NEGATION_OR_QUALIFIER.matcher(leaf).find()
                || leaf.length() > 60;
    }
}
