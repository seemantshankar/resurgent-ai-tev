package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.embed.Cosine;
import com.resurgent.tev.doc.embed.EmbeddingVectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link ClauseLabelNormalizer}, then merges groups whose open-clause
 * embedding centroids are cosine-similar. Literal or LLM grouping still happens
 * first; this step is what lets differently worded open clauses promote together
 * when their stored vectors agree. Groups with no embedding are left as the
 * delegate grouped them.
 */
final class CosinePreclusterNormalizer implements ClauseLabelNormalizer {

    /**
     * Minimum cosine between two open-clause groups before they are one cluster.
     * High enough that a shared party name does not glue unrelated clauses, low
     * enough that a paraphrased covenant still meets.
     */
    static final double THRESHOLD = 0.72;

    private final ClauseLabelNormalizer delegate;
    private final Map<String, float[]> centroidByCode;

    CosinePreclusterNormalizer(ClauseLabelNormalizer delegate, Map<String, float[]> centroidByCode) {
        this.delegate = delegate;
        this.centroidByCode = centroidByCode == null ? Map.of() : centroidByCode;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public NormalizationResult normalize(NormalizationRequest request) {
        NormalizationResult base = delegate.normalize(request);
        List<MutableGroup> groups = new ArrayList<>();
        for (LabelGroup group : base.groups()) {
            groups.add(MutableGroup.from(group));
        }
        boolean merged = true;
        while (merged) {
            merged = false;
            int bestI = -1;
            int bestJ = -1;
            double best = THRESHOLD;
            for (int i = 0; i < groups.size(); i++) {
                float[] left = groups.get(i).centroid(centroidByCode);
                if (left == null) {
                    continue;
                }
                for (int j = i + 1; j < groups.size(); j++) {
                    float[] right = groups.get(j).centroid(centroidByCode);
                    if (right == null) {
                        continue;
                    }
                    double score = Cosine.similarity(left, right);
                    if (score >= best) {
                        best = score;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            if (bestI >= 0) {
                groups.get(bestI).absorb(groups.remove(bestJ));
                merged = true;
            }
        }
        List<LabelGroup> out = new ArrayList<>();
        for (MutableGroup group : groups) {
            out.add(group.toLabelGroup());
        }
        return new NormalizationResult(out);
    }

    private static final class MutableGroup {
        private String key;
        private String label;
        private String parentFamily;
        private final List<String> aliases = new ArrayList<>();
        private final List<String> memberCodes = new ArrayList<>();

        private static MutableGroup from(LabelGroup group) {
            MutableGroup mutable = new MutableGroup();
            mutable.key = group.key();
            mutable.label = group.label();
            mutable.parentFamily = group.parentFamily();
            mutable.aliases.addAll(group.aliases());
            mutable.memberCodes.addAll(group.memberCodes());
            return mutable;
        }

        private void absorb(MutableGroup other) {
            if ((parentFamily == null || parentFamily.isBlank())
                    && other.parentFamily != null && !other.parentFamily.isBlank()) {
                parentFamily = other.parentFamily;
            }
            addAlias(other.label);
            for (String alias : other.aliases) {
                addAlias(alias);
            }
            for (String code : other.memberCodes) {
                if (!memberCodes.contains(code)) {
                    memberCodes.add(code);
                }
            }
        }

        private void addAlias(String alias) {
            if (alias == null || alias.isBlank()) {
                return;
            }
            for (String existing : aliases) {
                if (existing.equalsIgnoreCase(alias)) {
                    return;
                }
            }
            aliases.add(alias);
        }

        private float[] centroid(Map<String, float[]> byCode) {
            List<float[]> vectors = new ArrayList<>();
            for (String code : memberCodes) {
                float[] vector = byCode.get(code);
                if (vector != null) {
                    vectors.add(vector);
                }
            }
            return EmbeddingVectors.meanUnit(vectors);
        }

        private LabelGroup toLabelGroup() {
            return new LabelGroup(key, label, parentFamily, aliases, memberCodes);
        }
    }
}
