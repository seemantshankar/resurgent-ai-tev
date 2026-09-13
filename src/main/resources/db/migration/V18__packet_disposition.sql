-- Layer A Packet disposition keyed by parse run + Candidate (#106).
-- Re-classify replaces these rows; Candidate geometry is unchanged.

CREATE TABLE packet_disposition (
    disposition_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    schedule_family TEXT NOT NULL,
    triage TEXT NOT NULL CHECK (triage IN ('main', 'scratch', 'orphan')),
    relevance TEXT NOT NULL CHECK (relevance IN ('primary', 'supporting', 'noise')),
    row_labels TEXT,
    column_headers TEXT,
    packet_default_head TEXT,
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    cheap_pass INTEGER NOT NULL CHECK (cheap_pass IN (0, 1)),
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, candidate_id)
);

CREATE INDEX idx_packet_disposition_parse_run ON packet_disposition (parse_run_id);
CREATE INDEX idx_packet_disposition_candidate ON packet_disposition (candidate_id);
