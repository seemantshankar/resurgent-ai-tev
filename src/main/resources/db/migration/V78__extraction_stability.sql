-- M4 extraction stability (#157): two-run drift recorded in workspace.db.

ALTER TABLE fact ADD COLUMN unstable_across_runs INTEGER NOT NULL DEFAULT 0
    CHECK (unstable_across_runs IN (0, 1));

CREATE INDEX idx_fact_unstable ON fact (document_version_id, unstable_across_runs)
    WHERE unstable_across_runs = 1;

CREATE TABLE unstable_reading (
    unstable_reading_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id            INTEGER NOT NULL,
    document_version_id   INTEGER NOT NULL REFERENCES document_version (document_version_id),
    field_key             TEXT NOT NULL,
    line_key              TEXT,
    parse_run_id_a        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    parse_run_id_b        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    fact_id_a             INTEGER REFERENCES fact (fact_id),
    fact_id_b             INTEGER REFERENCES fact (fact_id),
    value_a               TEXT NOT NULL,
    value_b               TEXT NOT NULL,
    page_a                INTEGER,
    page_b                INTEGER,
    instruction_text      TEXT,
    reason                TEXT NOT NULL DEFAULT 'unstable_across_runs',
    created_at            TEXT NOT NULL
);

CREATE INDEX idx_unstable_reading_version ON unstable_reading (document_version_id);
CREATE INDEX idx_unstable_reading_mandate ON unstable_reading (mandate_id);
