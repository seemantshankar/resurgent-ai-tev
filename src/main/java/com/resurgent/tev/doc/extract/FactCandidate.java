package com.resurgent.tev.doc.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A schema-guided field result before persistence. Carries pack field key, typed slots,
 * confidence, stated/derived flag, and zero-or-more citations.
 *
 * <p>The LLM is never the authority for {@code absoluteAmount} — that is filled by
 * deterministic normalizers after extract (Architecture §7.4).
 */
public final class FactCandidate {

    private final String key;
    private final ValueKind valueKind;
    private String valueText;
    private Double valueNumber;
    private String valueDate;
    private String unit;
    private String currency;
    private String scale;
    private String absoluteAmount;
    private String subjectEntity;
    private String lineKey;
    private Double confidence;
    private String statedOrDerived = "stated";
    private String extractionMethod = "llm_field";
    private final List<Citation> citations = new ArrayList<>();
    private final List<String> derivedFromKeys = new ArrayList<>();

    /**
     * Creates a candidate for a pack field key.
     *
     * @param key pack field key (required)
     * @param valueKind expected value kind from the pack (required)
     */
    public FactCandidate(String key, ValueKind valueKind) {
        this.key = Objects.requireNonNull(key, "key");
        this.valueKind = Objects.requireNonNull(valueKind, "valueKind");
    }

    public String key() {
        return key;
    }

    public ValueKind valueKind() {
        return valueKind;
    }

    public String valueText() {
        return valueText;
    }

    public FactCandidate valueText(String valueText) {
        this.valueText = valueText;
        return this;
    }

    public Double valueNumber() {
        return valueNumber;
    }

    public FactCandidate valueNumber(Double valueNumber) {
        this.valueNumber = valueNumber;
        return this;
    }

    public String valueDate() {
        return valueDate;
    }

    public FactCandidate valueDate(String valueDate) {
        this.valueDate = valueDate;
        return this;
    }

    public String unit() {
        return unit;
    }

    public FactCandidate unit(String unit) {
        this.unit = unit;
        return this;
    }

    public String currency() {
        return currency;
    }

    public FactCandidate currency(String currency) {
        this.currency = currency;
        return this;
    }

    public String scale() {
        return scale;
    }

    public FactCandidate scale(String scale) {
        this.scale = scale;
        return this;
    }

    public String absoluteAmount() {
        return absoluteAmount;
    }

    public FactCandidate absoluteAmount(String absoluteAmount) {
        this.absoluteAmount = absoluteAmount;
        return this;
    }

    public String subjectEntity() {
        return subjectEntity;
    }

    public FactCandidate subjectEntity(String subjectEntity) {
        this.subjectEntity = subjectEntity;
        return this;
    }

    public String lineKey() {
        return lineKey;
    }

    public FactCandidate lineKey(String lineKey) {
        this.lineKey = lineKey;
        return this;
    }

    public Double confidence() {
        return confidence;
    }

    public FactCandidate confidence(Double confidence) {
        this.confidence = confidence;
        return this;
    }

    public String statedOrDerived() {
        return statedOrDerived;
    }

    public FactCandidate statedOrDerived(String statedOrDerived) {
        this.statedOrDerived = statedOrDerived;
        return this;
    }

    public String extractionMethod() {
        return extractionMethod;
    }

    public FactCandidate extractionMethod(String extractionMethod) {
        this.extractionMethod = extractionMethod;
        return this;
    }

    public List<Citation> citations() {
        return List.copyOf(citations);
    }

    public FactCandidate addCitation(Citation citation) {
        citations.add(Objects.requireNonNull(citation, "citation"));
        return this;
    }

    /** Pack field keys this derived fact was computed from (role {@code derived_from}). */
    public List<String> derivedFromKeys() {
        return List.copyOf(derivedFromKeys);
    }

    public FactCandidate addDerivedFromKey(String fieldKey) {
        derivedFromKeys.add(Objects.requireNonNull(fieldKey, "fieldKey"));
        return this;
    }
}
