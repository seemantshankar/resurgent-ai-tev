-- Document Intelligence M2: meaning tables for extracted facts (Architecture §6.5).
-- fact / fact_value / fact_evidence + pack registry. M1 left provenance_doc empty on purpose.
-- Re-run replaces facts for a parse_run (delete children then insert) — same discipline as
-- cell interpretation snapshots. No clause / clause_slot / checklist tables here (M3).
-- Quotation grouping keys (S2.1: vendor, package, validity, tax/freight flags) stay on the
-- parent facts; line-item facts carry fact_evidence rows whose role names the group
-- (grouping_vendor / grouping_package / grouping_validity / grouping_tax / grouping_freight).

CREATE TABLE fact (
    fact_id             INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    region_id           INTEGER,                    -- regions are M3; column reserved
    field_key           TEXT,                       -- pack field key ('tariff', 'net_worth', ...)
    ontology_path       TEXT,                       -- living path; NULL until bind
    path_root           TEXT CHECK (path_root IS NULL OR path_root IN (
                            'identity','economic','technical','commercial','legal','regulatory','frame')),
    subject_entity      TEXT,
    value_kind          TEXT NOT NULL CHECK (value_kind IN (
                            'money','quantity','rate','percent','date','duration','text','enum','boolean','list')),
    value_text          TEXT,
    value_number        REAL,
    value_date          TEXT,
    unit                TEXT,
    currency            TEXT,
    scale               TEXT CHECK (scale IS NULL OR scale IN (
                            'unit','thousand','lakh','million','crore','billion')),
    absolute_amount     TEXT,
    lang_source         TEXT,
    extraction_method   TEXT NOT NULL CHECK (extraction_method IN (
                            'deterministic','llm_field','llm_open','human','derived')),
    extractor_id        TEXT,
    confidence          REAL,
    nomenclature_status TEXT NOT NULL DEFAULT 'unbound'
                        CHECK (nomenclature_status IN ('bound','unbound','not_applicable')),
    unbound_reason      TEXT,
    stated_or_derived   TEXT CHECK (stated_or_derived IS NULL OR stated_or_derived IN (
                            'stated','derived')),
    line_key            TEXT,                       -- quotation line description / grouping key
    group_keys_json     TEXT,                       -- vendor, package, validity, tax/freight flags
    valid_from          TEXT,
    valid_to            TEXT,
    is_current          INTEGER NOT NULL DEFAULT 1 CHECK (is_current IN (0,1)),
    superseded_by_fact_id INTEGER REFERENCES fact (fact_id),
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_fact_parse_run ON fact (parse_run_id);
CREATE INDEX idx_fact_version ON fact (document_version_id);
CREATE INDEX idx_fact_path ON fact (ontology_path);
CREATE INDEX idx_fact_field_key ON fact (field_key);
CREATE INDEX idx_fact_current ON fact (is_current);

CREATE TABLE fact_value (
    fact_value_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    fact_id             INTEGER NOT NULL REFERENCES fact (fact_id),
    value_text          TEXT,
    value_number        REAL,
    value_date          TEXT,
    unit                TEXT,
    currency            TEXT,
    scale               TEXT,
    absolute_amount     TEXT,
    change_reason       TEXT,
    document_version_id INTEGER NOT NULL,
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_fact_value_fact ON fact_value (fact_id);

CREATE TABLE fact_evidence (
    fact_id             INTEGER NOT NULL REFERENCES fact (fact_id),
    ordinal             INTEGER NOT NULL,
    role                TEXT NOT NULL CHECK (role IN (
                            'verbatim_quote','table_cell','label','period','unit','currency','scale',
                            'signature_block','stamp','handwriting','derived_from',
                            'grouping_vendor','grouping_package','grouping_validity',
                            'grouping_tax','grouping_freight')),
    provenance_id       INTEGER REFERENCES provenance_doc (provenance_id),
    source_text         TEXT,
    resolution          TEXT NOT NULL CHECK (resolution IN ('resolved','missing','ambiguous')),
    normalized_value    TEXT,
    rule_id             TEXT,
    PRIMARY KEY (fact_id, ordinal)
);

CREATE INDEX idx_fact_evidence_provenance ON fact_evidence (provenance_id);

-- Pack registry (M2: SQLite beside the workspace; platform.db is M6).
CREATE TABLE doc_type_pack (
    pack_id             INTEGER PRIMARY KEY AUTOINCREMENT,
    code                TEXT NOT NULL,
    version             TEXT NOT NULL,
    family              TEXT NOT NULL,
    file_role           TEXT,
    industry_tag        TEXT,
    file_hash           TEXT NOT NULL,
    yaml_path           TEXT,                       -- relative under packs/ or classpath
    source_mandates_json TEXT,
    induction_run_id    TEXT,
    status              TEXT NOT NULL DEFAULT 'published'
                        CHECK (status IN ('published','rejected','optional')),
    created_at          TEXT NOT NULL,
    UNIQUE (code, version)
);

CREATE INDEX idx_doc_type_pack_code ON doc_type_pack (code, version);

-- Classification result side-table (S2.12 reasons/confidence).
CREATE TABLE doc_classification (
    document_version_id INTEGER PRIMARY KEY REFERENCES document_version (document_version_id),
    doc_type_code       TEXT,                       -- pack code or 'unknown'
    pack_code           TEXT,
    pack_version        TEXT,
    confidence          REAL,
    reasons_json        TEXT,
    industry_tag        TEXT,
    classified_at       TEXT NOT NULL,
    classifier          TEXT
);

-- Extend run_stat stages for the document pipeline.
CREATE TABLE run_stat_v47 (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
);

INSERT INTO run_stat_v47 (stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at)
SELECT stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at FROM run_stat;

DROP TABLE run_stat;
ALTER TABLE run_stat_v47 RENAME TO run_stat;
CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);

-- Extend llm_usage stages so LlamaExtract spend is attributable.
CREATE TABLE llm_usage_v47 (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'region-layout', 'layer-a', 'layer-b', 'other',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce')),
    model_id TEXT NOT NULL,
    calls INTEGER NOT NULL,
    failed_calls INTEGER NOT NULL DEFAULT 0,
    failovers INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cost_usd REAL,
    cost_missing INTEGER NOT NULL DEFAULT 0,
    latency_ms_total INTEGER NOT NULL DEFAULT 0,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, model_id)
);

INSERT INTO llm_usage_v47 (
    usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
    prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at)
SELECT usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
       prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at
FROM llm_usage;

DROP TABLE llm_usage;
ALTER TABLE llm_usage_v47 RENAME TO llm_usage;
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);
