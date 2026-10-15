-- Document Intelligence M3-01: document dates + assessment run records (spec #133, issue #134).
-- document_version gains the document date as stated in the document (by the pack-declared
-- date kind), its precision (day / month / year — partial dates are never invented to a day),
-- its source (stated / file metadata / none) and a provenance anchor for the stated case.
-- The upload date stays the existing ingested_at. No winning version is chosen (M4).
-- assessment_run is the per-stage run record: one row per (mandate, stage); a re-run replaces
-- it because assessment history is M4. The run_stat / llm_usage stage CHECKs are rebuilt to
-- admit the M3 stage names (SQLite cannot widen a CHECK in place — same discipline as V47).

ALTER TABLE document_version ADD COLUMN document_date TEXT;
ALTER TABLE document_version ADD COLUMN date_precision TEXT
    CHECK (date_precision IS NULL OR date_precision IN ('day', 'month', 'year'));
ALTER TABLE document_version ADD COLUMN date_source TEXT
    CHECK (date_source IS NULL OR date_source IN ('stated', 'file_metadata', 'none'));
ALTER TABLE document_version ADD COLUMN date_kind TEXT;
ALTER TABLE document_version ADD COLUMN date_provenance_id INTEGER
    REFERENCES provenance_doc (provenance_id);

CREATE TABLE assessment_run (
    assessment_run_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id        INTEGER NOT NULL,
    stage             TEXT NOT NULL CHECK (stage IN (
                          'assess_profile', 'assess_passages', 'assess_clauses',
                          'assess_conflicts', 'assess_checklist')),
    status            TEXT NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    parse_run_id      INTEGER REFERENCES parse_run (parse_run_id),
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    metrics_json      TEXT,
    UNIQUE (mandate_id, stage)
);

-- Extend run_stat stages for the M3 assessment stages.
CREATE TABLE run_stat_v49 (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce',
        'assess_profile', 'assess_passages', 'assess_clauses',
        'assess_conflicts', 'assess_checklist')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
);

INSERT INTO run_stat_v49 (stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at)
SELECT stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at FROM run_stat;

DROP TABLE run_stat;
ALTER TABLE run_stat_v49 RENAME TO run_stat;
CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);

-- Extend llm_usage stages so M3 assessment spend is attributable.
CREATE TABLE llm_usage_v49 (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'region-layout', 'layer-a', 'layer-b', 'other',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce',
        'assess_profile', 'assess_passages', 'assess_clauses',
        'assess_conflicts', 'assess_checklist')),
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

INSERT INTO llm_usage_v49 (
    usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
    prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at)
SELECT usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
       prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at
FROM llm_usage;

DROP TABLE llm_usage;
ALTER TABLE llm_usage_v49 RENAME TO llm_usage;
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);
