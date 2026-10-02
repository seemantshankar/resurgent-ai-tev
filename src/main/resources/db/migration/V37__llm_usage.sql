-- Track LLM usage metrics per parse run for model evals and cost analysis
CREATE TABLE llm_usage (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN ('region-layout', 'layer-a', 'layer-b')),
    calls INTEGER NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cost_usd REAL,
    cost_missing INTEGER NOT NULL DEFAULT 0,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage)
);

CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
