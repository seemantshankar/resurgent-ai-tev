-- Track timing and duration for each pipeline stage per parse run
CREATE TABLE run_timing (
    timing_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN ('ingest', 'discover', 'region-layout', 'layer-a', 'layer-b')),
    started_at TEXT NOT NULL,
    finished_at TEXT NOT NULL,
    duration_millis INTEGER NOT NULL,
    item_count INTEGER,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage)
);

CREATE INDEX idx_run_timing_parse_run ON run_timing (parse_run_id);
CREATE INDEX idx_run_timing_stage ON run_timing (stage);
