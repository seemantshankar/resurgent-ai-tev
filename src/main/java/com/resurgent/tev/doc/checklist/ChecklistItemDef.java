package com.resurgent.tev.doc.checklist;

import java.util.List;

/**
 * One checklist item definition (GLOSSARY "Checklist item"): either an expected
 * <b>document</b> (by document type / pack code) or an expected <b>data point</b>
 * inside one (by pack field key / ontology path). Entity-granularity items are
 * expected once per entity. Every item carries its stated basis — the named
 * regulation or bank practice that requires it — or it is never stored.
 *
 * @param key stable snake_case item key
 * @param label human label
 * @param level {@code document} | {@code data_point}
 * @param granularity {@code mandate} | {@code entity} | {@code site}
 * @param entityRole entity role this item is expected for ({@code promoter}/…), or
 *     null when every entity of the granularity applies
 * @param documentType the pack code of the expected document (both levels name the
 *     document type; a data point is expected inside its document type)
 * @param fieldKey the pack field key of the expected data point, or null
 * @param ontologyPath the ontology path of the expected data point, or null
 * @param mandatory whether the item is mandatory (informational — every item is
 *     still assessed and reported)
 * @param basisKind {@code regulation} | {@code practice} | {@code observed}
 * @param basisText the named regulation or bank practice, never blank
 * @param origin {@code drafted} | {@code observed} | {@code promoted}
 * @param clientRequest ask-the-client wording template ({@code {entity}} and
 *     {@code {item}} are substituted when the item is Not provided)
 * @param note item-level note recorded with the item (e.g. why it became
 *     optional — M3-15), or null; it takes precedence over the definition note
 *     when the result is persisted
 */
public record ChecklistItemDef(
        String key,
        String label,
        String level,
        String granularity,
        String entityRole,
        String documentType,
        String fieldKey,
        String ontologyPath,
        boolean mandatory,
        String basisKind,
        String basisText,
        String origin,
        String clientRequest,
        String note) {

    /** True when this item expects a whole document. */
    public boolean isDocumentLevel() {
        return "document".equals(level);
    }

    /** True when this item expects a data point inside a document type. */
    public boolean isDataPointLevel() {
        return "data_point".equals(level);
    }

    /** Level and granularity for readable reasons, e.g. {@code document/entity}. */
    public String levelScope() {
        return level + "/" + granularity;
    }

    static List<String> basisKinds() {
        return List.of("regulation", "practice", "observed");
    }
}
