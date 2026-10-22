-- Document Intelligence M4: output fact snapshots and freshness evaluation (spec #162, issue #163).
-- fact_snapshot records the frozen fact state consumed by a downstream product or comparison.
-- snapshot_fact_link binds individual fact ids and their values at snapshot creation.
-- The stage CHECK constraints in assessment_run, run_stat, and llm_usage are rebuilt to admit
-- stage 'assess_freshness' and metric 'doc-drift' (#157).

CREATE TABLE fact_snapshot (
    snapshot_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id          INTEGER NOT NULL,
    output_type         TEXT NOT NULL,
    output_key          TEXT NOT NULL,
    is_stale            INTEGER NOT NULL DEFAULT 0 CHECK (is_stale IN (0,1)),
    stale_at            TEXT,
    stale_reason        TEXT,
    created_at          TEXT NOT NULL,
    UNIQUE (mandate_id, output_type, output_key)
);

CREATE INDEX idx_fact_snapshot_mandate ON fact_snapshot (mandate_id, output_type, output_key);
CREATE INDEX idx_fact_snapshot_stale ON fact_snapshot (is_stale);

CREATE TABLE snapshot_fact_link (
    snapshot_id         INTEGER NOT NULL REFERENCES fact_snapshot (snapshot_id) ON DELETE CASCADE,
    fact_id             INTEGER NOT NULL REFERENCES fact (fact_id),
    recorded_value      TEXT NOT NULL,
    recorded_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id, fact_id)
);

CREATE INDEX idx_snapshot_fact_link_fact ON snapshot_fact_link (fact_id);

-- Widen assessment_run to admit stage 'assess_freshness'.
CREATE TABLE assessment_run_v69 (
    assessment_run_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id        INTEGER NOT NULL,
    stage             TEXT NOT NULL CHECK (stage IN (
                          'assess_profile', 'assess_passages', 'assess_clauses',
                          'assess_conflicts', 'assess_checklist', 'assess_freshness')),
    status            TEXT NOT NULL CHECK (status IN (
                          'running', 'completed', 'partial', 'failed')),
    llm_failures      INTEGER NOT NULL DEFAULT 0,
    parse_run_id      INTEGER REFERENCES parse_run (parse_run_id),
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    metrics_json      TEXT,
    UNIQUE (mandate_id, stage)
);

INSERT INTO assessment_run_v69 (
    assessment_run_id, mandate_id, stage, status, llm_failures, parse_run_id,
    started_at, finished_at, metrics_json)
SELECT assessment_run_id, mandate_id, stage, status, llm_failures, parse_run_id,
       started_at, finished_at, metrics_json
FROM assessment_run;

DROP TABLE assessment_run;
ALTER TABLE assessment_run_v69 RENAME TO assessment_run;

-- Widen run_stat stages to admit 'assess_freshness' and 'doc-drift'.
CREATE TABLE run_stat_v69 (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce', 'induce-clauses',
        'induce-checklists', 'doc-drift', 'assess_profile', 'assess_passages', 'assess_clauses',
        'assess_conflicts', 'assess_checklist', 'assess_freshness')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
);

INSERT INTO run_stat_v69 (
    stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at)
SELECT stat_id, parse_run_id, stage, name, value_num, value_text, recorded_at
FROM run_stat;

DROP TABLE run_stat;
ALTER TABLE run_stat_v69 RENAME TO run_stat;
CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);

-- Widen llm_usage stages to admit 'assess_freshness' and 'doc-drift'.
CREATE TABLE llm_usage_v69 (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'region-layout', 'layer-a', 'layer-b', 'other',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce', 'induce-clauses',
        'induce-checklists', 'doc-drift', 'assess_profile', 'assess_passages', 'assess_clauses',
        'assess_conflicts', 'assess_checklist', 'assess_freshness')),
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
    cost_unit TEXT NOT NULL DEFAULT 'usd' CHECK (cost_unit IN ('usd', 'credits')),
    UNIQUE (parse_run_id, stage, model_id)
);

INSERT INTO llm_usage_v69 (
    usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
    prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at, cost_unit)
SELECT usage_id, parse_run_id, stage, model_id, calls, failed_calls, failovers,
       prompt_tokens, completion_tokens, cost_usd, cost_missing, latency_ms_total, recorded_at, cost_unit
FROM llm_usage;

DROP TABLE llm_usage;
ALTER TABLE llm_usage_v69 RENAME TO llm_usage;
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);
