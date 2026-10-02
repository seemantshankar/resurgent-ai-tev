-- Make LLM stats comparable across runs and models.
--
-- V37 kept one lumped llm_usage row per parse run (stage 'layer-a' for the whole run, no model).
-- That cannot say which model did what, so llm_usage is rebuilt with one row per
-- (parse run, stage, model) plus failure/fallback counts and total latency. Rows written
-- before this migration covered every model and stage at once; they are kept with
-- model_id = '*'.
--
-- run_stat holds the context a comparison needs: the settings a run used (models, batch
-- sizes, decision-model threshold) and what each stage processed (sheets, candidates, cells).
CREATE TABLE llm_usage_v39 (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN ('region-layout', 'layer-a', 'layer-b', 'other')),
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

INSERT INTO llm_usage_v39 (
    usage_id, parse_run_id, stage, model_id, calls, prompt_tokens, completion_tokens,
    cost_usd, cost_missing, recorded_at)
SELECT usage_id, parse_run_id, stage, '*', calls, prompt_tokens, completion_tokens,
       cost_usd, cost_missing, recorded_at
FROM llm_usage;

DROP TABLE llm_usage;
ALTER TABLE llm_usage_v39 RENAME TO llm_usage;
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);

CREATE TABLE run_stat (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
);

CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);
