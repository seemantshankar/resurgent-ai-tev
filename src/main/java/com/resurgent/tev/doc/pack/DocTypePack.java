package com.resurgent.tev.doc.pack;

import com.resurgent.tev.doc.extract.ValueKind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A versioned, industry-generic document type pack (Architecture §8.1).
 * Packs are data, not code: Java core never learns industry vocabulary.
 */
public final class DocTypePack {

    /** Closed spine roots for {@code path_root} (Architecture §6.6). */
    public static final List<String> SPINE_ROOTS = List.of(
            "identity", "economic", "technical", "commercial", "legal", "regulatory", "frame");

    /** Closed set of money/quantity scales shared with Excel cell reading. */
    public static final List<String> SCALES = List.of(
            "unit", "thousand", "lakh", "million", "crore", "billion");

    /**
     * Date kinds a pack can declare for its document type (M3 document dates):
     * execution / signing for agreements, issue for certificates and letters,
     * report for DPR / PVsyst, offer for quotations, and {@code date} as the
     * generic fallback.
     */
    public static final List<String> DOCUMENT_DATE_KINDS = List.of(
            "execution", "signing", "issue", "report", "offer", "date");

    private final String code;
    private final String version;
    private final String family;
    private final String fileRole;
    private final List<String> industryTags;
    private final List<String> identitySignals;
    private final List<PackField> fields;
    private final List<Map<String, Object>> tables;
    private final List<String> checklist;
    private final List<String> reportSections;
    private final String documentDateKind;
    private final ClauseSection clauseSection;

    /**
     * Creates a validated pack. Use {@link PackLoader} for YAML; this constructor
     * is the in-memory form (induction, tests).
     */
    public DocTypePack(
            String code,
            String version,
            String family,
            String fileRole,
            List<String> industryTags,
            List<String> identitySignals,
            List<PackField> fields,
            List<Map<String, Object>> tables,
            List<String> checklist,
            List<String> reportSections) {
        this(code, version, family, fileRole, industryTags, identitySignals, fields, tables,
                checklist, reportSections, null, ClauseSection.NONE);
    }

    /**
     * Creates a validated pack with the document-date kind its type carries (M3).
     *
     * @param documentDateKind one of {@link #DOCUMENT_DATE_KINDS}, or null when the
     *     pack does not declare one (the assessment falls back to the generic kind)
     */
    public DocTypePack(
            String code,
            String version,
            String family,
            String fileRole,
            List<String> industryTags,
            List<String> identitySignals,
            List<PackField> fields,
            List<Map<String, Object>> tables,
            List<String> checklist,
            List<String> reportSections,
            String documentDateKind) {
        this(code, version, family, fileRole, industryTags, identitySignals, fields, tables,
                checklist, reportSections, documentDateKind, ClauseSection.NONE);
    }

    /**
     * Creates a validated pack with its document-date kind and clause section (M3-08).
     *
     * @param clauseSection the clause section the pack declares, null when none
     */
    public DocTypePack(
            String code,
            String version,
            String family,
            String fileRole,
            List<String> industryTags,
            List<String> identitySignals,
            List<PackField> fields,
            List<Map<String, Object>> tables,
            List<String> checklist,
            List<String> reportSections,
            String documentDateKind,
            ClauseSection clauseSection) {
        this.code = Objects.requireNonNull(code, "code");
        this.version = Objects.requireNonNull(version, "version");
        this.family = Objects.requireNonNull(family, "family");
        this.fileRole = Objects.requireNonNull(fileRole, "fileRole");
        this.industryTags = industryTags == null ? List.of() : List.copyOf(industryTags);
        this.identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.tables = tables == null ? List.of() : tables.stream().map(Map::copyOf).toList();
        this.checklist = checklist == null ? List.of() : List.copyOf(checklist);
        this.reportSections = reportSections == null ? List.of() : List.copyOf(reportSections);
        this.documentDateKind = documentDateKind == null || documentDateKind.isBlank()
                ? null
                : documentDateKind.trim().toLowerCase(Locale.ROOT);
        this.clauseSection = clauseSection == null ? ClauseSection.NONE : clauseSection;
    }

    public String code() {
        return code;
    }

    public String version() {
        return version;
    }

    public String family() {
        return family;
    }

    public String fileRole() {
        return fileRole;
    }

    public List<String> industryTags() {
        return industryTags;
    }

    public List<String> identitySignals() {
        return identitySignals;
    }

    public List<PackField> fields() {
        return fields;
    }

    public List<Map<String, Object>> tables() {
        return tables;
    }

    public List<String> checklist() {
        return checklist;
    }

    public List<String> reportSections() {
        return reportSections;
    }

    /** Field by pack key, or empty. */
    public Optional<PackField> field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /** The document-date kind this type carries ({@code execution}/…), or null when undeclared. */
    public String documentDateKind() {
        return documentDateKind;
    }

    /** The clause section this pack declares (never null; {@link ClauseSection#NONE} when absent). */
    public ClauseSection clauseSection() {
        return clauseSection;
    }

    /** True when the pack declares clauses — only such document types are read for them. */
    public boolean declaresClauses() {
        return clauseSection.declared();
    }

    /** True when unmatched clauses are stored open (Pass B) for this type. */
    public boolean clauseOpenExtraction() {
        return clauseSection.declared() && clauseSection.openExtraction();
    }

    /** The clause types bound for this document type, in declaration order. */
    public List<PackClauseType> clauseTypes() {
        return clauseSection.types();
    }

    /** Required fields only. */
    public List<PackField> requiredFields() {
        return fields.stream().filter(PackField::required).toList();
    }

    /**
     * Compiles the pack into a JSON Schema fragment for {@code ExtractClient}.
     * Multi-value leaves are arrays; quotation packs add a {@code line_items} array
     * of row objects from {@code tables}.
     *
     * @return JSON Schema object fragment (never null)
     */
    public com.fasterxml.jackson.databind.JsonNode toExtractSchema() {
        return SchemaCompiler.compile(this);
    }

    /** Identity string used in extract {@code config_hash}: {@code code@version}. */
    public String identity() {
        return code + "@" + version;
    }

    /**
     * Maps a pack YAML {@code value_kind} wire name to the closed enum.
     *
     * @throws IllegalArgumentException on unknown kind
     */
    public static ValueKind parseValueKind(String wire) {
        return ValueKind.fromWire(wire);
    }

    /**
     * Validates a spine root name.
     *
     * @throws IllegalArgumentException when not in {@link #SPINE_ROOTS}
     */
    public static void requireSpineRoot(String pathRoot) {
        String root = pathRoot == null ? "" : pathRoot.toLowerCase(Locale.ROOT);
        if (!SPINE_ROOTS.contains(root)) {
            throw new IllegalArgumentException(
                    "path_root must be one of " + SPINE_ROOTS + ", got: " + pathRoot);
        }
    }

    /**
     * Validates a scale name against the shared Excel scale table.
     *
     * @throws IllegalArgumentException when not in {@link #SCALES}
     */
    public static void requireScale(String scale) {
        String s = scale == null ? "" : scale.toLowerCase(Locale.ROOT);
        if (!SCALES.contains(s)) {
            throw new IllegalArgumentException("scale must be one of " + SCALES + ", got: " + scale);
        }
    }

    /**
     * Validates a document-date kind against the closed M3 vocabulary.
     *
     * @throws IllegalArgumentException when not in {@link #DOCUMENT_DATE_KINDS}
     */
    public static void requireDocumentDateKind(String kind) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (!DOCUMENT_DATE_KINDS.contains(k)) {
            throw new IllegalArgumentException(
                    "document_date.kind must be one of " + DOCUMENT_DATE_KINDS + ", got: " + kind);
        }
    }
}
