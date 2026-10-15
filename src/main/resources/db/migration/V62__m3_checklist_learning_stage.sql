-- M3-15 (#148): induction-family checklist learning, not a sixth assess stage.
-- Rebuild the stage CHECKs, preserving all historical stats, usage and indexes.
CREATE TABLE run_stat_v62 (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce', 'induce-clauses',
        'induce-checklists', 'assess_profile', 'assess_passages', 'assess_clauses',
        'assess_conflicts', 'assess_checklist')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
);
INSERT INTO run_stat_v62 SELECT * FROM run_stat;
DROP TABLE run_stat;
ALTER TABLE run_stat_v62 RENAME TO run_stat;
CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);

CREATE TABLE llm_usage_v62 (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'region-layout', 'layer-a', 'layer-b', 'other',
        'doc-classify', 'doc-extract', 'doc-bind', 'doc-induce', 'induce-clauses',
        'induce-checklists', 'assess_profile', 'assess_passages', 'assess_clauses',
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
INSERT INTO llm_usage_v62 SELECT * FROM llm_usage;
DROP TABLE llm_usage;
ALTER TABLE llm_usage_v62 RENAME TO llm_usage;
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);
