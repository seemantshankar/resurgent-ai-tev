package com.resurgent.tev.parser.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Versioned, classpath-backed tuning values for region boundary scoring. */
public final class RegionWeights {

    private static final String RESOURCE = "/region-weights.json";
    private static final RegionWeights DEFAULT = load();

    private final Map<String, Integer> signals;
    private final Map<String, Integer> classification;
    private final String contentHash;

    private RegionWeights(Map<String, Integer> signals, Map<String, Integer> classification,
            String contentHash) {
        this.signals = Map.copyOf(signals);
        this.classification = Map.copyOf(classification);
        this.contentHash = contentHash;
    }

    public static RegionWeights defaults() {
        return DEFAULT;
    }

    public int signal(String name) {
        Integer value = signals.get(name);
        if (value == null) {
            throw new IllegalStateException("missing region weight: " + name);
        }
        return value;
    }

    public String contentHash() {
        return contentHash;
    }

    /** Versioned weights for region classification rather than region boundary detection. */
    public int classification(String name) {
        Integer value = classification.get(name);
        if (value == null) {
            throw new IllegalStateException("missing classification weight: " + name);
        }
        return value;
    }

    private static RegionWeights load() {
        try (InputStream stream = RegionWeights.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing required resource " + RESOURCE);
            }
            byte[] content = stream.readAllBytes();
            Map<String, Object> root = new ObjectMapper().readValue(content, new TypeReference<>() {});
            Object rawSignals = root.get("signals");
            if (!(rawSignals instanceof Map<?, ?> map)) {
                throw new IllegalStateException("region weights resource has no signals object");
            }
            Map<String, Integer> signals = numericMap(map, "signals");
            for (String required : new String[] {"titleStyle", "columnProfileShift", "serialReset",
                    "skeletonDrift", "sectionMarker", "formulaAnchorChange", "blankRowsWithHeader",
                    "coherentSpacer", "hiddenRowsInSummedRange", "protectedTotalOrMerge"}) {
                if (!signals.containsKey(required)) {
                    throw new IllegalStateException("region weights resource missing " + required);
                }
            }
            Object rawClassification = root.get("classification");
            if (!(rawClassification instanceof Map<?, ?> classificationMap)) {
                throw new IllegalStateException("region weights resource has no classification object");
            }
            Map<String, Integer> classification = numericMap(classificationMap, "classification");
            for (String required : new String[] {"headerToken", "statementShape", "costHeadAlias",
                    "verticalForm", "scratchPattern", "serialPattern"}) {
                if (!classification.containsKey(required)) {
                    throw new IllegalStateException("region weights classification missing " + required);
                }
            }
            return new RegionWeights(signals, classification, sha256(content));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load region weights resource", e);
        }
    }

    private static Map<String, Integer> numericMap(Map<?, ?> source, String section) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Number number)) {
                throw new IllegalStateException("region weights " + section + " must be numeric");
            }
            result.put(name, number.intValue());
        }
        return result;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
