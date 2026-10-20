package com.resurgent.tev.doc.extract;

import java.util.Objects;

/**
 * Basis evidence attached to an extracted fact candidate or write:
 * explains the unit, currency, or scale choices citing the layout or document statement.
 *
 * @param role role in fact_evidence ('unit', 'currency', 'scale')
 * @param sourceText verbatim token or statement text from the document
 * @param normalizedValue canonical normalized value (e.g. 'kWp', 'INR', 'lakh')
 * @param citation optional citation locating the token or statement
 * @param ruleId optional rule name explaining the binding
 */
public record BasisEvidenceWrite(
        String role,
        String sourceText,
        String normalizedValue,
        Citation citation,
        String ruleId) {

    public BasisEvidenceWrite {
        Objects.requireNonNull(role, "role");
    }

    public static BasisEvidenceWrite of(
            String role, String sourceText, String normalizedValue, Citation citation) {
        return new BasisEvidenceWrite(role, sourceText, normalizedValue, citation, null);
    }
}
