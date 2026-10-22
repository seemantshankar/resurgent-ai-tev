package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.extract.BasisEvidenceWrite;
import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for extracted facts, evidence, and provenance (Architecture §6.5, S2.5).
 * Re-extract replaces the fact set for a parse_run (children first). Quote resolution
 * matches {@code span.text_raw} → {@code block.text_raw} → {@code table_cell.text_raw}
 * on the cited page, then the parsed page text ({@code cloud_parse_page}) for scans with
 * no layout rows; unresolved quotes stay citable with {@code resolution='missing'}.
 *
 * <p>Connection is owned by the caller; methods never commit or roll back.
 */
public final class FactRepository {

    /**
     * Quotation grouping keys (S2.1/S2.7): parent fact field keys that group line-item
     * facts, each mapped to the {@code fact_evidence.role} naming the group. The key
     * values stay on the parent facts ({@code vendor_name}, {@code validity_date},
     * {@code tax}, {@code freight}, …); a line item only carries the role plus the
     * parent fact id in {@code rule_id}, so re-grouping never duplicates values.
     */
    public static final Map<String, String> GROUPING_ROLES = Map.of(
            "vendor_name", "grouping_vendor",
            "project_name", "grouping_package",
            "scope_text", "grouping_package",
            "validity_date", "grouping_validity",
            "tax", "grouping_tax",
            "freight", "grouping_freight");

    private final Connection connection;

    public FactRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /** One normalized fact ready for insert, with its citations and derived parents. */
    public record FactWrite(
            String fieldKey,
            String ontologyPath,
            String pathRoot,
            String subjectEntity,
            String valueKind,
            String valueText,
            Double valueNumber,
            String valueDate,
            String unit,
            String currency,
            String scale,
            String absoluteAmount,
            String langSource,
            String extractionMethod,
            String extractorId,
            Double confidence,
            String statedOrDerived,
            String lineKey,
            String groupKeysJson,
            List<Citation> citations,
            List<String> derivedFromKeys,
            String period,
            List<BasisEvidenceWrite> basisEvidence) {

        public FactWrite {
            citations = citations == null ? List.of() : List.copyOf(citations);
            derivedFromKeys = derivedFromKeys == null ? List.of() : List.copyOf(derivedFromKeys);
            basisEvidence = basisEvidence == null ? List.of() : List.copyOf(basisEvidence);
        }

        /** Full fact write with all parameters except basis evidence. */
        public FactWrite(String fieldKey, String ontologyPath, String pathRoot,
                String subjectEntity, String valueKind, String valueText, Double valueNumber,
                String valueDate, String unit, String currency, String scale,
                String absoluteAmount, String langSource, String extractionMethod,
                String extractorId, Double confidence, String statedOrDerived, String lineKey,
                String groupKeysJson, List<Citation> citations,
                List<String> derivedFromKeys, String period) {
            this(fieldKey, ontologyPath, pathRoot, subjectEntity, valueKind, valueText,
                    valueNumber, valueDate, unit, currency, scale, absoluteAmount, langSource,
                    extractionMethod, extractorId, confidence, statedOrDerived, lineKey,
                    groupKeysJson, citations, derivedFromKeys, period, List.of());
        }

        /** Fact write with no period (presence fields, line items, derived values). */
        public FactWrite(String fieldKey, String ontologyPath, String pathRoot,
                String subjectEntity, String valueKind, String valueText, Double valueNumber,
                String valueDate, String unit, String currency, String scale,
                String absoluteAmount, String langSource, String extractionMethod,
                String extractorId, Double confidence, String statedOrDerived, String lineKey,
                String groupKeysJson, List<Citation> citations,
                List<String> derivedFromKeys) {
            this(fieldKey, ontologyPath, pathRoot, subjectEntity, valueKind, valueText,
                    valueNumber, valueDate, unit, currency, scale, absoluteAmount, langSource,
                    extractionMethod, extractorId, confidence, statedOrDerived, lineKey,
                    groupKeysJson, citations, derivedFromKeys, null, List.of());
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .fieldKey(fieldKey)
                    .ontologyPath(ontologyPath)
                    .pathRoot(pathRoot)
                    .subjectEntity(subjectEntity)
                    .valueKind(valueKind)
                    .valueText(valueText)
                    .valueNumber(valueNumber)
                    .valueDate(valueDate)
                    .unit(unit)
                    .currency(currency)
                    .scale(scale)
                    .absoluteAmount(absoluteAmount)
                    .langSource(langSource)
                    .extractionMethod(extractionMethod)
                    .extractorId(extractorId)
                    .confidence(confidence)
                    .statedOrDerived(statedOrDerived)
                    .lineKey(lineKey)
                    .groupKeysJson(groupKeysJson)
                    .citations(citations)
                    .derivedFromKeys(derivedFromKeys)
                    .period(period)
                    .basisEvidence(basisEvidence);
        }

        public static final class Builder {
            private String fieldKey;
            private String ontologyPath;
            private String pathRoot;
            private String subjectEntity;
            private String valueKind;
            private String valueText;
            private Double valueNumber;
            private String valueDate;
            private String unit;
            private String currency;
            private String scale;
            private String absoluteAmount;
            private String langSource;
            private String extractionMethod;
            private String extractorId;
            private Double confidence;
            private String statedOrDerived;
            private String lineKey;
            private String groupKeysJson;
            private List<Citation> citations = new ArrayList<>();
            private List<String> derivedFromKeys = new ArrayList<>();
            private String period;
            private List<BasisEvidenceWrite> basisEvidence = new ArrayList<>();

            public Builder fieldKey(String fieldKey) { this.fieldKey = fieldKey; return this; }
            public Builder ontologyPath(String ontologyPath) { this.ontologyPath = ontologyPath; return this; }
            public Builder pathRoot(String pathRoot) { this.pathRoot = pathRoot; return this; }
            public Builder subjectEntity(String subjectEntity) { this.subjectEntity = subjectEntity; return this; }
            public Builder valueKind(String valueKind) { this.valueKind = valueKind; return this; }
            public Builder valueText(String valueText) { this.valueText = valueText; return this; }
            public Builder valueNumber(Double valueNumber) { this.valueNumber = valueNumber; return this; }
            public Builder valueDate(String valueDate) { this.valueDate = valueDate; return this; }
            public Builder unit(String unit) { this.unit = unit; return this; }
            public Builder currency(String currency) { this.currency = currency; return this; }
            public Builder scale(String scale) { this.scale = scale; return this; }
            public Builder absoluteAmount(String absoluteAmount) { this.absoluteAmount = absoluteAmount; return this; }
            public Builder langSource(String langSource) { this.langSource = langSource; return this; }
            public Builder extractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; return this; }
            public Builder extractorId(String extractorId) { this.extractorId = extractorId; return this; }
            public Builder confidence(Double confidence) { this.confidence = confidence; return this; }
            public Builder statedOrDerived(String statedOrDerived) { this.statedOrDerived = statedOrDerived; return this; }
            public Builder lineKey(String lineKey) { this.lineKey = lineKey; return this; }
            public Builder groupKeysJson(String groupKeysJson) { this.groupKeysJson = groupKeysJson; return this; }
            public Builder citations(List<Citation> citations) { this.citations = citations != null ? new ArrayList<>(citations) : new ArrayList<>(); return this; }
            public Builder addCitation(Citation citation) { if (citation != null) this.citations.add(citation); return this; }
            public Builder derivedFromKeys(List<String> derivedFromKeys) { this.derivedFromKeys = derivedFromKeys != null ? new ArrayList<>(derivedFromKeys) : new ArrayList<>(); return this; }
            public Builder addDerivedFromKey(String key) { if (key != null) this.derivedFromKeys.add(key); return this; }
            public Builder period(String period) { this.period = period; return this; }
            public Builder basisEvidence(List<BasisEvidenceWrite> basisEvidence) { this.basisEvidence = basisEvidence != null ? new ArrayList<>(basisEvidence) : new ArrayList<>(); return this; }
            public Builder addBasisEvidence(BasisEvidenceWrite evidence) { if (evidence != null) this.basisEvidence.add(evidence); return this; }

            public FactWrite build() {
                return new FactWrite(
                        fieldKey, ontologyPath, pathRoot, subjectEntity, valueKind, valueText,
                        valueNumber, valueDate, unit, currency, scale, absoluteAmount, langSource,
                        extractionMethod, extractorId, confidence, statedOrDerived, lineKey,
                        groupKeysJson, citations, derivedFromKeys, period, basisEvidence);
            }
        }
    }

    /**
     * One fact as bind sees it: the pack's own path plus the value and the page it
     * was read on, which is the context the model gets for an unknown leaf.
     */
    public record FactBindRow(
            long factId,
            String fieldKey,
            String extractionMethod,
            String ontologyPath,
            String pathRoot,
            String statedOrDerived,
            String valueText,
            Long pageIndex,
            String verbatim) {}

    /** Outcome of a replace: counts for run_stat / tests. */
    public record ReplaceStats(
            int factsInserted,
            int evidenceInserted,
            int provenanceInserted,
            int quotesResolved,
            int quotesMissing) {}

    /**
     * Replaces every fact (and its {@code fact_value} / {@code fact_evidence} children)
     * for the parse_run, then inserts the new set with evidence and provenance.
     * Callers wrap this in a transaction so a failure leaves no half-written set.
     *
     * @param parseRunId extract parse_run identity
     * @param documentVersionId version the facts are about
     * @param facts normalized candidates (derived facts should follow their parents)
     * @return insert counts
     * @throws SQLException if any insert or delete fails
     */
    public ReplaceStats replaceFacts(long parseRunId, long documentVersionId, List<FactWrite> facts)
            throws SQLException {
        deleteFactsForParseRun(parseRunId);
        if (facts == null || facts.isEmpty()) {
            return new ReplaceStats(0, 0, 0, 0, 0);
        }

        String now = Timestamps.now();
        List<Long> factIds = new ArrayList<>(facts.size());
        Map<String, List<Long>> idsByFieldKey = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact (parse_run_id, document_version_id, region_id, field_key,"
                        + " ontology_path, path_root, subject_entity, value_kind, value_text,"
                        + " value_number, value_date, unit, currency, scale, absolute_amount,"
                        + " lang_source, extraction_method, extractor_id, confidence,"
                        + " nomenclature_status, unbound_reason, stated_or_derived, line_key,"
                        + " group_keys_json, valid_from, valid_to, is_current, created_at)"
                        + " VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " 'unbound', NULL, ?, ?, ?, NULL, NULL, 1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (FactWrite f : facts) {
                ps.setLong(1, parseRunId);
                ps.setLong(2, documentVersionId);
                setNullableString(ps, 3, f.fieldKey());
                setNullableString(ps, 4, f.ontologyPath());
                setNullableString(ps, 5, f.pathRoot());
                setNullableString(ps, 6, f.subjectEntity());
                ps.setString(7, f.valueKind());
                setNullableString(ps, 8, f.valueText());
                setNullableDouble(ps, 9, f.valueNumber());
                setNullableString(ps, 10, f.valueDate());
                setNullableString(ps, 11, f.unit());
                setNullableString(ps, 12, f.currency());
                setNullableString(ps, 13, f.scale());
                setNullableString(ps, 14, f.absoluteAmount());
                setNullableString(ps, 15, f.langSource());
                ps.setString(16, f.extractionMethod());
                setNullableString(ps, 17, f.extractorId());
                setNullableDouble(ps, 18, f.confidence());
                setNullableString(ps, 19, f.statedOrDerived());
                setNullableString(ps, 20, f.lineKey());
                setNullableString(ps, 21, f.groupKeysJson());
                ps.setString(22, now);
                ps.executeUpdate();
                long factId = generatedId(ps);
                factIds.add(factId);
                if (f.fieldKey() != null) {
                    idsByFieldKey.computeIfAbsent(f.fieldKey(), k -> new ArrayList<>()).add(factId);
                }
            }
        }

        int evidenceInserted = 0;
        int provenanceInserted = 0;
        int quotesResolved = 0;
        int quotesMissing = 0;
        for (int i = 0; i < facts.size(); i++) {
            FactWrite f = facts.get(i);
            long factId = factIds.get(i);
            int ordinal = 0;
            for (Citation citation : f.citations()) {
                QuoteHit hit = resolveQuote(documentVersionId, citation);
                long provenanceId = insertProvenance(factId, documentVersionId, citation, hit);
                provenanceInserted++;
                String resolution = hit.resolved() ? "resolved" : "missing";
                if (hit.resolved()) {
                    quotesResolved++;
                } else {
                    quotesMissing++;
                }
                insertFactEvidence(factId, ordinal++, "verbatim_quote", provenanceId,
                        citation.verbatim(), resolution, null, null);
                evidenceInserted++;
            }
            for (BasisEvidenceWrite bev : f.basisEvidence()) {
                Long provenanceId = null;
                String resolution = "missing";
                if (bev.citation() != null) {
                    QuoteHit hit = resolveQuote(documentVersionId, bev.citation());
                    provenanceId = insertProvenance(factId, documentVersionId, bev.citation(), hit);
                    provenanceInserted++;
                    if (hit.resolved()) {
                        quotesResolved++;
                        resolution = "resolved";
                    } else {
                        quotesMissing++;
                        resolution = "missing";
                    }
                }
                insertFactEvidence(factId, ordinal++, bev.role(), provenanceId,
                        bev.sourceText(), resolution, bev.normalizedValue(), bev.ruleId());
                evidenceInserted++;
            }
            if ("derived".equals(f.extractionMethod()) && !f.derivedFromKeys().isEmpty()) {
                for (String parentKey : f.derivedFromKeys()) {
                    List<Long> parentIds = idsByFieldKey.get(parentKey);
                    Long parentId = parentIds == null || parentIds.isEmpty() ? null : parentIds.get(0);
                    insertFactEvidence(factId, ordinal++, "derived_from", null,
                            parentKey, "resolved", null,
                            parentId == null ? null : String.valueOf(parentId));
                    evidenceInserted++;
                }
            }
            if ("line_items".equals(f.fieldKey())) {
                // S2.1 grouping: bind the line to the quotation's parent facts via a
                // role that names the group; the value itself stays on the parent.
                for (Map.Entry<String, String> group : GROUPING_ROLES.entrySet()) {
                    List<Long> parentIds = idsByFieldKey.get(group.getKey());
                    if (parentIds == null || parentIds.isEmpty()) {
                        continue;
                    }
                    insertFactEvidence(factId, ordinal++, group.getValue(), null,
                            group.getKey(), "resolved", null,
                            String.valueOf(parentIds.get(0)));
                    evidenceInserted++;
                }
            }
            if (f.period() != null && !f.period().isBlank()) {
                // The statement the value was read for, as stated (e.g. a financial
                // year). Reserved evidence role: two years of one figure are two
                // readings of it, never a conflict (ADR 0026).
                insertFactEvidence(factId, ordinal++, "period", null,
                        f.period(), "resolved", null, null);
                evidenceInserted++;
            }
        }
        return new ReplaceStats(facts.size(), evidenceInserted, provenanceInserted,
                quotesResolved, quotesMissing);
    }

    /**
     * Inserts one passage-linked fact (a chapter data point or one row of a listed
     * table) with its evidence and provenance. The caller already removed the stage's
     * previous facts (see {@code AssessPassagesRunner}), so this is a plain insert —
     * re-running the stage replaces and never duplicates.
     *
     * @param parseRunId parse_run carrying the assessment stage
     * @param documentVersionId version the fact is about
     * @param passageId passage the fact is linked to
     * @param f normalized fact to insert
     * @return the new {@code fact_id}
     * @throws SQLException if any insert fails
     */
    public long insertPassageFact(long parseRunId, long documentVersionId, long passageId,
            FactWrite f) throws SQLException {
        long factId;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact (parse_run_id, document_version_id, region_id, field_key,"
                        + " ontology_path, path_root, subject_entity, value_kind, value_text,"
                        + " value_number, value_date, unit, currency, scale, absolute_amount,"
                        + " lang_source, extraction_method, extractor_id, confidence,"
                        + " nomenclature_status, unbound_reason, stated_or_derived, line_key,"
                        + " group_keys_json, valid_from, valid_to, is_current, created_at,"
                        + " passage_id)"
                        + " VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " 'unbound', NULL, ?, ?, ?, NULL, NULL, 1, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setLong(2, documentVersionId);
            setNullableString(ps, 3, f.fieldKey());
            setNullableString(ps, 4, f.ontologyPath());
            setNullableString(ps, 5, f.pathRoot());
            setNullableString(ps, 6, f.subjectEntity());
            ps.setString(7, f.valueKind());
            setNullableString(ps, 8, f.valueText());
            setNullableDouble(ps, 9, f.valueNumber());
            setNullableString(ps, 10, f.valueDate());
            setNullableString(ps, 11, f.unit());
            setNullableString(ps, 12, f.currency());
            setNullableString(ps, 13, f.scale());
            setNullableString(ps, 14, f.absoluteAmount());
            setNullableString(ps, 15, f.langSource());
            ps.setString(16, f.extractionMethod());
            setNullableString(ps, 17, f.extractorId());
            setNullableDouble(ps, 18, f.confidence());
            setNullableString(ps, 19, f.statedOrDerived());
            setNullableString(ps, 20, f.lineKey());
            setNullableString(ps, 21, f.groupKeysJson());
            ps.setString(22, Timestamps.now());
            ps.setLong(23, passageId);
            ps.executeUpdate();
            factId = generatedId(ps);
        }
        int ordinal = 0;
        for (Citation citation : f.citations()) {
            QuoteHit hit = resolveQuote(documentVersionId, citation);
            long provenanceId = insertProvenance(factId, documentVersionId, citation, hit);
            insertFactEvidence(factId, ordinal++, "verbatim_quote", provenanceId,
                    citation.verbatim(), hit.resolved() ? "resolved" : "missing", null, null);
        }
        for (BasisEvidenceWrite bev : f.basisEvidence()) {
            Long provenanceId = null;
            String resolution = "missing";
            if (bev.citation() != null) {
                QuoteHit hit = resolveQuote(documentVersionId, bev.citation());
                provenanceId = insertProvenance(factId, documentVersionId, bev.citation(), hit);
                resolution = hit.resolved() ? "resolved" : "missing";
            }
            insertFactEvidence(factId, ordinal++, bev.role(), provenanceId,
                    bev.sourceText(), resolution, bev.normalizedValue(), bev.ruleId());
        }
        return factId;
    }

    /**
     * Deletes the fact set for a parse_run (children first). Used by replace and tests.
     *
     * @throws SQLException if any delete fails
     */
    public void deleteFactsForParseRun(long parseRunId) throws SQLException {
        // Mark affected snapshots as stale and remove their links before deleting facts
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact_snapshot SET is_stale = 1, stale_reason = 'referenced facts were deleted during re-extraction' "
                        + "WHERE snapshot_id IN ("
                        + "    SELECT sfl.snapshot_id FROM snapshot_fact_link sfl "
                        + "    JOIN fact f ON f.fact_id = sfl.fact_id "
                        + "    WHERE f.parse_run_id = ?"
                        + ")")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM snapshot_fact_link WHERE fact_id IN ("
                        + "    SELECT fact_id FROM fact WHERE parse_run_id = ?"
                        + ")")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_value WHERE fact_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_evidence WHERE fact_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = 'fact' AND entity_id IN"
                        + " (SELECT fact_id FROM fact WHERE parse_run_id = ?)")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            ps.executeUpdate();
        }
    }

    /**
     * Quote resolution: exact match on {@code span.text_raw}, then {@code block.text_raw},
     * then {@code table_cell.text_raw} for the cited page; finally a text containment
     * match against the LlamaParse page ({@code cloud_parse_page}) — scanned documents
     * have no layout rows, so the parsed markdown is their only citable text. First hit
     * wins.
     */
    public QuoteHit resolveQuote(long documentVersionId, Citation citation) throws SQLException {
        Long pageId = findPageId(documentVersionId, citation.pageIndex());
        if (pageId != null) {
            // span
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT s.span_id, s.block_id FROM span s"
                            + " JOIN block b ON b.block_id = s.block_id"
                            + " WHERE b.page_id = ? AND s.text_raw = ? LIMIT 1")) {
                ps.setLong(1, pageId);
                ps.setString(2, citation.verbatim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new QuoteHit(true, pageId, rs.getLong("block_id"),
                                rs.getLong("span_id"), null, citation);
                    }
                }
            }
            // block
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT block_id FROM block WHERE page_id = ? AND text_raw = ? LIMIT 1")) {
                ps.setLong(1, pageId);
                ps.setString(2, citation.verbatim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new QuoteHit(true, pageId, rs.getLong("block_id"), null, null,
                                citation);
                    }
                }
            }
            // table_cell
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT tc.table_cell_id, tg.block_id FROM table_cell tc"
                            + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                            + " JOIN block b ON b.block_id = tg.block_id"
                            + " WHERE b.page_id = ? AND tc.text_raw = ? LIMIT 1")) {
                ps.setLong(1, pageId);
                ps.setString(2, citation.verbatim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new QuoteHit(true, pageId, rs.getLong("block_id"), null,
                                rs.getLong("table_cell_id"), citation);
                    }
                }
            }
            // span containment
            if (!citation.verbatim().isBlank()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT s.span_id, s.block_id FROM span s"
                                + " JOIN block b ON b.block_id = s.block_id"
                                + " WHERE b.page_id = ? AND instr(lower(s.text_raw), ?) > 0 LIMIT 1")) {
                    ps.setLong(1, pageId);
                    ps.setString(2, citation.verbatim().toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new QuoteHit(true, pageId, rs.getLong("block_id"),
                                    rs.getLong("span_id"), null, citation);
                        }
                    }
                }
                // table_cell containment
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT tc.table_cell_id, tg.block_id FROM table_cell tc"
                                + " JOIN table_grid tg ON tg.table_grid_id = tc.table_grid_id"
                                + " JOIN block b ON b.block_id = tg.block_id"
                                + " WHERE b.page_id = ? AND instr(lower(tc.text_raw), ?) > 0 LIMIT 1")) {
                    ps.setLong(1, pageId);
                    ps.setString(2, citation.verbatim().toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new QuoteHit(true, pageId, rs.getLong("block_id"), null,
                                    rs.getLong("table_cell_id"), citation);
                        }
                    }
                }
                // block containment
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT block_id FROM block WHERE page_id = ? AND instr(lower(text_raw), ?) > 0 LIMIT 1")) {
                    ps.setLong(1, pageId);
                    ps.setString(2, citation.verbatim().toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new QuoteHit(true, pageId, rs.getLong("block_id"), null, null,
                                    citation);
                        }
                    }
                }
            }
        }
        QuoteHit pageHit = resolveQuoteInParsedPages(documentVersionId, citation, pageId);
        if (pageHit != null) {
            return pageHit;
        }
        return QuoteHit.missing(citation, pageId);
    }

    /** Containment match of the verbatim against the cited page's parsed text. */
    private QuoteHit resolveQuoteInParsedPages(long documentVersionId, Citation citation,
            Long pageId) throws SQLException {
        String loose = normalizeLoose(citation.verbatim());
        String tight = normalizeTight(citation.verbatim());
        if (tight.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_markdown, page_text FROM cloud_parse_page"
                        + " WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, citation.pageIndex());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (int column = 1; column <= 2; column++) {
                        String page = rs.getString(column);
                        if (page == null || page.isBlank()) {
                            continue;
                        }
                        // loose keeps word shapes (space-normalized containment);
                        // tight drops markdown pipes and layout gaps so table cells
                        // still match a quote read across them
                        if (normalizeLoose(page).contains(loose)
                                || normalizeTight(page).contains(tight)) {
                            return new QuoteHit(true, pageId, null, null, null, citation);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String normalizeLoose(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String normalizeTight(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Retrieval surface (S2.13): fact rows with joined evidence, sorted by
     * ontology path then confidence descending. No PDF re-parse.
     *
     * @param query filter bag (all filters optional)
     * @return matching rows with evidence lists
     * @throws SQLException if the lookup fails
     */
    public List<FactRow> query(FactQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT f.fact_id, f.ontology_path, f.path_root, f.field_key, f.value_kind,"
                        + " f.value_text,"
                        + " f.value_number, f.value_date, f.unit, f.currency, f.scale,"
                        + " f.absolute_amount, f.subject_entity, f.line_key, f.document_version_id,"
                        + " dv.file_name, f.extraction_method, f.confidence, f.stated_or_derived,"
                        + " f.site_id, s.site_name, f.entity_id, f.entity_reason,"
                        + " f.nomenclature_status, f.nomenclature_reason, f.unbound_reason,"
                        + " f.is_current, f.superseded_by_fact_id"
                        + " FROM fact f"
                        + " JOIN document_version dv ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " LEFT JOIN site s ON s.site_id = f.site_id"
                        + " WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (query.mandateId() != null) {
            sql.append(" AND d.mandate_id = ?");
            params.add(query.mandateId());
        }
        if (query.documentId() != null) {
            sql.append(" AND d.document_id = ?");
            params.add(query.documentId());
        }
        if (query.documentVersionId() != null) {
            sql.append(" AND f.document_version_id = ?");
            params.add(query.documentVersionId());
        }
        if (query.pathPrefix() != null && !query.pathPrefix().isBlank()) {
            sql.append(" AND f.ontology_path LIKE ?");
            params.add(query.pathPrefix().trim() + "%");
        }
        if (query.valueKind() != null && !query.valueKind().isBlank()) {
            sql.append(" AND f.value_kind = ?");
            params.add(query.valueKind().trim().toLowerCase(Locale.ROOT));
        }
        if (query.factId() != null) {
            sql.append(" AND f.fact_id = ?");
            params.add(query.factId());
        }
        if (query.currentOnly()) {
            sql.append(" AND f.is_current = 1");
        }
        sql.append(" ORDER BY f.ontology_path IS NULL,"
                + " CASE f.path_root"
                + " WHEN 'identity' THEN 0"
                + " WHEN 'economic' THEN 1"
                + " WHEN 'technical' THEN 2"
                + " WHEN 'commercial' THEN 3"
                + " WHEN 'legal' THEN 4"
                + " WHEN 'regulatory' THEN 5"
                + " WHEN 'frame' THEN 6"
                + " ELSE 7 END,"
                + " f.ontology_path, f.confidence DESC, f.fact_id");

        Map<Long, FactRowBuilder> builders = new LinkedHashMap<>();
        List<Long> order = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                bindObject(ps, i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long factId = rs.getLong("fact_id");
                    FactRowBuilder b = new FactRowBuilder(
                            factId,
                            rs.getString("ontology_path"),
                            rs.getString("path_root"),
                            rs.getString("nomenclature_status"),
                            rs.getString("nomenclature_reason"),
                            rs.getString("unbound_reason"),
                            rs.getString("field_key"),
                            rs.getString("value_kind"),
                            rs.getString("value_text"),
                            asDouble(rs.getObject("value_number")),
                            rs.getString("value_date"),
                            rs.getString("unit"),
                            rs.getString("currency"),
                            rs.getString("scale"),
                            rs.getString("absolute_amount"),
                            rs.getString("subject_entity"),
                            rs.getString("line_key"),
                            rs.getLong("document_version_id"),
                            rs.getString("file_name"),
                            rs.getString("extraction_method"),
                            asDouble(rs.getObject("confidence")),
                            rs.getString("stated_or_derived"),
                            asLong(rs.getObject("site_id")),
                            rs.getString("site_name"),
                            asLong(rs.getObject("entity_id")),
                            rs.getString("entity_reason"),
                            rs.getInt("is_current") == 1,
                            asLong(rs.getObject("superseded_by_fact_id")));
                    builders.put(factId, b);
                    order.add(factId);
                }
            }
        }
        if (order.isEmpty()) {
            return List.of();
        }
        loadEvidence(builders);
        List<FactRow> rows = new ArrayList<>(order.size());
        for (Long id : order) {
            rows.add(builders.get(id).build());
        }
        return rows;
    }

    /**
     * Looks up one fact by its unique ID, including historical facts.
     */
    public Optional<FactRow> findFact(long factId) throws SQLException {
        List<FactRow> list = query(new FactQuery(null, null, null, null, false, null, factId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Traverses the supersession chain backwards from a fact ID, returning all
     * previous versions of this fact ordered from newest predecessor to oldest ancestor.
     */
    public List<FactRow> findSupersessionHistory(long currentFactId) throws SQLException {
        List<FactRow> history = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(currentFactId);
        long targetId = currentFactId;
        String sql = "SELECT fact_id FROM fact WHERE superseded_by_fact_id = ? ORDER BY fact_id DESC";
        while (true) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, targetId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long priorId = rs.getLong(1);
                        if (!visited.add(priorId)) {
                            break; // Cycle detected, abort loop
                        }
                        findFact(priorId).ifPresent(history::add);
                        targetId = priorId;
                    } else {
                        break;
                    }
                }
            }
        }
        return history;
    }

    /** Facts of a parse_run for bind (id, field_key, method, current path, stated flag). */
    public List<FactBindRow> selectFactsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.fact_id, f.field_key, f.extraction_method, f.ontology_path,"
                        + " f.path_root, f.stated_or_derived, f.value_text,"
                        + " (SELECT pg.page_index FROM fact_evidence e"
                        + "   LEFT JOIN provenance_doc p ON p.provenance_id = e.provenance_id"
                        + "   LEFT JOIN page pg ON pg.page_id = p.page_id"
                        + "   WHERE e.fact_id = f.fact_id ORDER BY e.ordinal LIMIT 1) AS page_index,"
                        + " (SELECT COALESCE(p.verbatim, e.source_text) FROM fact_evidence e"
                        + "   LEFT JOIN provenance_doc p ON p.provenance_id = e.provenance_id"
                        + "   WHERE e.fact_id = f.fact_id ORDER BY e.ordinal LIMIT 1) AS verbatim"
                        + " FROM fact f WHERE f.parse_run_id = ? ORDER BY f.fact_id")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FactBindRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new FactBindRow(
                            rs.getLong("fact_id"),
                            rs.getString("field_key"),
                            rs.getString("extraction_method"),
                            rs.getString("ontology_path"),
                            rs.getString("path_root"),
                            rs.getString("stated_or_derived"),
                            rs.getString("value_text"),
                            rs.getObject("page_index") == null
                                    ? null : rs.getLong("page_index"),
                            rs.getString("verbatim")));
                }
                return rows;
            }
        }
    }

    /**
     * Applies a bind decision to one fact. {@code nomenclatureStatus} is
     * {@code bound} | {@code not_applicable} | {@code unbound}; {@code nomenclatureReason}
     * says how a bound path was reached.
     */
    public void updateFactBinding(long factId, String ontologyPath, String pathRoot,
            String nomenclatureStatus, String unboundReason, String nomenclatureReason)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET ontology_path = ?, path_root = ?, nomenclature_status = ?,"
                        + " unbound_reason = ?, nomenclature_reason = ? WHERE fact_id = ?")) {
            setNullableString(ps, 1, ontologyPath);
            setNullableString(ps, 2, pathRoot);
            ps.setString(3, nomenclatureStatus);
            setNullableString(ps, 4, unboundReason);
            setNullableString(ps, 5, nomenclatureReason);
            ps.setLong(6, factId);
            ps.executeUpdate();
        }
    }

    /** Upserts a run_stat row (stage {@code doc-extract} / {@code doc-bind}). */
    public void recordRunStat(long parseRunId, String stage, String name, Double num, String text)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_stat (parse_run_id, stage, name, value_num, value_text)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (parse_run_id, stage, name) DO UPDATE SET"
                        + " value_num = excluded.value_num, value_text = excluded.value_text,"
                        + " recorded_at = CURRENT_TIMESTAMP")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, stage);
            ps.setString(3, name);
            if (num != null) {
                ps.setDouble(4, num);
            } else {
                ps.setNull(4, Types.DOUBLE);
            }
            ps.setString(5, text);
            ps.executeUpdate();
        }
    }

    /** Writes an audit_log event (e.g. {@code extract_completed}). */
    public long insertAuditLog(Long parseRunId, String eventType, String eventAt,
            String payload, String severity) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (parse_run_id, event_type, event_at, payload, severity)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (parseRunId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setLong(1, parseRunId);
            }
            ps.setString(2, eventType);
            ps.setString(3, eventAt);
            ps.setString(4, payload);
            ps.setString(5, severity);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** Sets {@code document_version.ingest_status} (e.g. {@code extracted}). */
    public void updateVersionIngestStatus(long documentVersionId, String ingestStatus)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_version SET ingest_status = ? WHERE document_version_id = ?")) {
            ps.setString(1, ingestStatus);
            ps.setLong(2, documentVersionId);
            ps.executeUpdate();
        }
    }

    /** Count of facts for a parse_run (tests / run_stat). */
    public long countFactsForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM fact WHERE parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Count of fact_evidence rows for a parse_run. */
    public long countEvidenceForParseRun(long parseRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM fact_evidence e"
                        + " JOIN fact f ON f.fact_id = e.fact_id"
                        + " WHERE f.parse_run_id = ?")) {
            ps.setLong(1, parseRunId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Removes prior review_queue rows of one category for a parse_run, so a re-extract
     * replaces its operational flag (e.g. {@code extract_empty}) instead of stacking
     * duplicates. Callers re-insert when the new run still qualifies.
     *
     * @throws SQLException if the delete fails
     */
    public void deleteReviewQueueCategory(long parseRunId, String category) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM review_queue WHERE parse_run_id = ? AND category = ?")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, category);
            ps.executeUpdate();
        }
    }

    // --- internals ---

    /** Resolved (or missing) layout anchors for one quote. */
    public record QuoteHit(
            boolean resolved,
            Long pageId,
            Long blockId,
            Long spanId,
            Long tableCellId,
            Citation citation) {

        static QuoteHit missing(Citation citation, Long pageId) {
            return new QuoteHit(false, pageId, null, null, null, citation);
        }
    }

    private static final class FactRowBuilder {
        final long factId;
        final String ontologyPath;
        final String pathRoot;
        final String nomenclatureStatus;
        final String nomenclatureReason;
        final String unboundReason;
        final String fieldKey;
        final String valueKind;
        final String valueText;
        final Double valueNumber;
        final String valueDate;
        final String unit;
        final String currency;
        final String scale;
        final String absoluteAmount;
        final String subjectEntity;
        final String lineKey;
        final long documentVersionId;
        final String fileName;
        final String extractionMethod;
        final Double confidence;
        final String statedOrDerived;
        final Long siteId;
        final String siteName;
        final Long entityId;
        final String entityReason;
        final boolean isCurrent;
        final Long supersededByFactId;
        final List<FactRow.EvidenceRow> evidence = new ArrayList<>();

        FactRowBuilder(long factId, String ontologyPath, String pathRoot,
                String nomenclatureStatus, String nomenclatureReason, String unboundReason,
                String fieldKey,
                String valueKind, String valueText, Double valueNumber, String valueDate,
                String unit, String currency, String scale, String absoluteAmount,
                String subjectEntity, String lineKey, long documentVersionId, String fileName,
                String extractionMethod, Double confidence, String statedOrDerived,
                Long siteId, String siteName, Long entityId, String entityReason,
                boolean isCurrent, Long supersededByFactId) {
            this.factId = factId;
            this.ontologyPath = ontologyPath;
            this.pathRoot = pathRoot;
            this.nomenclatureStatus = nomenclatureStatus;
            this.nomenclatureReason = nomenclatureReason;
            this.unboundReason = unboundReason;
            this.fieldKey = fieldKey;
            this.valueKind = valueKind;
            this.valueText = valueText;
            this.valueNumber = valueNumber;
            this.valueDate = valueDate;
            this.unit = unit;
            this.currency = currency;
            this.scale = scale;
            this.absoluteAmount = absoluteAmount;
            this.subjectEntity = subjectEntity;
            this.lineKey = lineKey;
            this.documentVersionId = documentVersionId;
            this.fileName = fileName;
            this.extractionMethod = extractionMethod;
            this.confidence = confidence;
            this.statedOrDerived = statedOrDerived;
            this.siteId = siteId;
            this.siteName = siteName;
            this.entityId = entityId;
            this.entityReason = entityReason;
            this.isCurrent = isCurrent;
            this.supersededByFactId = supersededByFactId;
        }

        FactRow build() {
            return new FactRow(factId, ontologyPath, pathRoot, nomenclatureStatus,
                    nomenclatureReason, unboundReason, fieldKey, valueKind, valueText,
                    valueNumber, valueDate, unit, currency, scale, absoluteAmount, subjectEntity,
                    lineKey, documentVersionId, fileName, extractionMethod, confidence,
                    statedOrDerived, evidence, siteId, siteName, entityId, entityReason,
                    isCurrent, supersededByFactId);
        }
    }

    private void loadEvidence(Map<Long, FactRowBuilder> builders) throws SQLException {
        if (builders.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT e.fact_id, e.role, e.provenance_id, e.source_text, e.resolution, e.normalized_value,"
                        + " p.verbatim, p.bbox_x0, p.bbox_y0, p.bbox_x1, p.bbox_y1,"
                        + " pg.page_index"
                        + " FROM fact_evidence e"
                        + " LEFT JOIN provenance_doc p ON p.provenance_id = e.provenance_id"
                        + " LEFT JOIN page pg ON pg.page_id = p.page_id"
                        + " WHERE e.fact_id IN (");
        List<Long> ids = new ArrayList<>(builders.keySet());
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") ORDER BY e.fact_id, e.ordinal");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FactRowBuilder b = builders.get(rs.getLong("fact_id"));
                    if (b == null) {
                        continue;
                    }
                    Long provenanceId = asLong(rs.getObject("provenance_id"));
                    Integer pageIndex = asInteger(rs.getObject("page_index"));
                    b.evidence.add(new FactRow.EvidenceRow(
                            rs.getString("role"),
                            rs.getString("verbatim") != null
                                    ? rs.getString("verbatim") : rs.getString("source_text"),
                            pageIndex,
                            asDouble(rs.getObject("bbox_x0")),
                            asDouble(rs.getObject("bbox_y0")),
                            asDouble(rs.getObject("bbox_x1")),
                            asDouble(rs.getObject("bbox_y1")),
                            provenanceId,
                            rs.getString("resolution"),
                            rs.getString("normalized_value")));
                }
            }
        }
    }

    /** SQLite JDBC returns Integer for small INTEGER values; widen safely. */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    private Long findPageId(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long insertProvenance(long factId, long documentVersionId, Citation citation,
            QuoteHit hit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO provenance_doc (entity_type, entity_id, document_version_id,"
                        + " page_id, block_id, span_id, table_cell_id, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, char_start, char_end, verbatim, quote_hash,"
                        + " confidence, is_derived)"
                        + " VALUES ('fact', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, factId);
            ps.setLong(2, documentVersionId);
            setLong(ps, 3, hit.pageId());
            setLong(ps, 4, hit.blockId());
            setLong(ps, 5, hit.spanId());
            setLong(ps, 6, hit.tableCellId());
            setDouble(ps, 7, citation.bboxX0());
            setDouble(ps, 8, citation.bboxY0());
            setDouble(ps, 9, citation.bboxX1());
            setDouble(ps, 10, citation.bboxY1());
            ps.setString(11, citation.verbatim());
            setDouble(ps, 12, null);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void insertFactEvidence(long factId, int ordinal, String role, Long provenanceId,
            String sourceText, String resolution, String normalizedValue, String ruleId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact_evidence (fact_id, ordinal, role, provenance_id, source_text,"
                        + " resolution, normalized_value, rule_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, factId);
            ps.setInt(2, ordinal);
            ps.setString(3, role);
            setLong(ps, 4, provenanceId);
            setNullableString(ps, 5, sourceText);
            ps.setString(6, resolution);
            setNullableString(ps, 7, normalizedValue);
            setNullableString(ps, 8, ruleId);
            ps.executeUpdate();
        }
    }

    private static void bindObject(PreparedStatement ps, int index, Object value)
            throws SQLException {
        if (value instanceof Long l) {
            ps.setLong(index, l);
        } else if (value instanceof String s) {
            ps.setString(index, s);
        } else {
            ps.setObject(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }
}
