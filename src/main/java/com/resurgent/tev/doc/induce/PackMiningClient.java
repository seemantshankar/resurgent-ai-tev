package com.resurgent.tev.doc.induce;

/**
 * SPI for LLM field mining over a document cluster (S2.11 induction).
 * Domain code never sees vendor types; adapters own their wire format.
 *
 * <p>The default suite is offline via {@link FixturePackMiningClient}. A real
 * adapter (OpenAI / LlamaCloud) may be wired later behind env credentials; it
 * must still return {@link MiningResult} with per-document appearances so the
 * deterministic {@link Generalizer} can apply consensus and label-not-value.
 */
public interface PackMiningClient {

    /** Stable adapter id, e.g. {@code fixture} or {@code openai-gpt-mining}. */
    String id();

    /**
     * Mines a generic field schema for one type cluster.
     *
     * @param request industry, file role, type hint, and the cluster documents
     * @return candidate fields with labels, paths, and per-document appearances
     * @throws PackMiningException on timeout, HTTP failure, or malformed payload
     */
    MiningResult mine(MiningRequest request) throws PackMiningException;

    /** Input to {@link #mine}. */
    record MiningRequest(
            String industryTag,
            String fileRole,
            String docTypeHint,
            java.util.List<MiningDocument> documents) {

        public MiningRequest {
            documents = documents == null ? java.util.List.of() : java.util.List.copyOf(documents);
        }
    }

    /** One sample document in the cluster (layout/title signals + value tokens). */
    record MiningDocument(
            String id,
            String fileRole,
            String title,
            java.util.List<String> layoutText,
            java.util.Set<String> sampleEntityTokens) {

        public MiningDocument {
            layoutText = layoutText == null ? java.util.List.of() : java.util.List.copyOf(layoutText);
            sampleEntityTokens = sampleEntityTokens == null
                    ? java.util.Set.of()
                    : java.util.Set.copyOf(sampleEntityTokens);
        }
    }

    /**
     * LLM-mined cluster schema prior to generalization.
     *
     * @param webSearchQuery sanitized query actually sent, or the query that would
     *     have been sent when residency kept the run offline
     * @param domainRationale why the mined shape is the domain standard for this type
     */
    record MiningResult(
            String suggestedCode,
            String family,
            java.util.List<String> identitySignals,
            java.util.List<MinedField> fields,
            java.util.List<MinedTable> tables,
            java.util.List<MinedClause> clauses,
            java.util.List<MinedPassage> passages,
            String documentDateKind,
            String webSearchQuery,
            String domainRationale) {

        /** Field-only result (existing miners and tests). */
        public MiningResult(
                String suggestedCode,
                String family,
                java.util.List<String> identitySignals,
                java.util.List<MinedField> fields) {
            this(suggestedCode, family, identitySignals, fields,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    null, null, null);
        }

        public MiningResult {
            identitySignals = identitySignals == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(identitySignals);
            fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
            tables = tables == null ? java.util.List.of() : java.util.List.copyOf(tables);
            clauses = clauses == null ? java.util.List.of() : java.util.List.copyOf(clauses);
            passages = passages == null ? java.util.List.of() : java.util.List.copyOf(passages);
        }

        /** Same schema with the sanitized query and domain rationale attached. */
        public MiningResult withResearch(String query, String rationale) {
            return new MiningResult(suggestedCode, family, identitySignals, fields, tables,
                    clauses, passages, documentDateKind, query, rationale);
        }
    }

    /** One column of a mined record schedule or period matrix. */
    record MinedColumn(String key, String valueKind) {}

    /**
     * A mined schedule table in the pack YAML shape: {@code key_column}, optional
     * {@code entity_key} / {@code site_key}, and {@code period_key} on matrices.
     */
    record MinedTable(
            String name,
            String path,
            String pathRoot,
            String keyColumn,
            String entityKey,
            String siteKey,
            String periodKey,
            java.util.List<MinedColumn> columns,
            java.util.List<String> docIds,
            boolean domainStandard) {

        public MinedTable {
            columns = columns == null ? java.util.List.of() : java.util.List.copyOf(columns);
            docIds = docIds == null ? java.util.List.of() : java.util.List.copyOf(docIds);
        }
    }

    /** A mined clause type. */
    record MinedClause(
            String key,
            String label,
            String parentFamily,
            java.util.List<String> aliases,
            java.util.List<String> docIds,
            boolean domainStandard) {

        public MinedClause {
            aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
            docIds = docIds == null ? java.util.List.of() : java.util.List.copyOf(docIds);
        }
    }

    /** A mined narrative passage topic. {@code fields} names pack field or table keys. */
    record MinedPassage(
            String topic,
            java.util.List<String> headingTerms,
            java.util.List<String> fields,
            java.util.List<String> docIds,
            boolean domainStandard) {

        public MinedPassage {
            headingTerms = headingTerms == null ? java.util.List.of() : java.util.List.copyOf(headingTerms);
            fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
            docIds = docIds == null ? java.util.List.of() : java.util.List.copyOf(docIds);
        }
    }

    /**
     * One mined field candidate.
     *
     * @param docIds       cluster document ids where the field appeared (consensus)
     * @param sampleValues instance values mined from samples (entity stripping + coverage)
     * @param labels       printed label variants across the cluster (nomenclature)
     */
    record MinedField(
            String key,
            String path,
            String pathRoot,
            String valueKind,
            Boolean required,
            String promptHint,
            java.util.List<String> synonyms,
            java.util.List<String> labels,
            java.util.List<String> docIds,
            java.util.List<String> sampleValues,
            boolean domainStandard) {

        /** Field that is not marked as a domain standard. */
        public MinedField(
                String key,
                String path,
                String pathRoot,
                String valueKind,
                Boolean required,
                String promptHint,
                java.util.List<String> synonyms,
                java.util.List<String> labels,
                java.util.List<String> docIds,
                java.util.List<String> sampleValues) {
            this(key, path, pathRoot, valueKind, required, promptHint, synonyms, labels,
                    docIds, sampleValues, false);
        }

        public MinedField {
            synonyms = synonyms == null ? java.util.List.of() : java.util.List.copyOf(synonyms);
            labels = labels == null ? java.util.List.of() : java.util.List.copyOf(labels);
            docIds = docIds == null ? java.util.List.of() : java.util.List.copyOf(docIds);
            sampleValues = sampleValues == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(sampleValues);
        }
    }
}
