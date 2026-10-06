-- A decision model's independent opinion on each region (main, scratch or orphan), recorded beside
-- Layer A's. `escalated` marks a region that region layout called scratch and that was sent to
-- Layer A because this opinion did not confirm it.
CREATE TABLE region_triage_opinion (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    choice TEXT NOT NULL CHECK (choice IN ('main', 'scratch', 'orphan')),
    confidence REAL NOT NULL,
    p_main REAL,
    p_scratch REAL,
    p_orphan REAL,
    escalated INTEGER NOT NULL CHECK (escalated IN (0, 1)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
