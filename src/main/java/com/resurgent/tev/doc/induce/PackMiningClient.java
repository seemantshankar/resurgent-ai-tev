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

    /** LLM-mined cluster schema prior to generalization. */
    record MiningResult(
            String suggestedCode,
            String family,
            java.util.List<String> identitySignals,
            java.util.List<MinedField> fields) {

        public MiningResult {
            identitySignals = identitySignals == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(identitySignals);
            fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
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
            java.util.List<String> sampleValues) {

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
