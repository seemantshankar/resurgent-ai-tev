-- Restore Layer A disposition (dropped in V27) plus free-text region about.
-- schedule_family holds admitted extras beyond the seven seed families (ADR 0022).

CREATE TABLE schedule_family (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);

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
    about TEXT NOT NULL,
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    cheap_pass INTEGER NOT NULL CHECK (cheap_pass IN (0, 1)),
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, candidate_id)
);

CREATE INDEX idx_packet_disposition_parse_run ON packet_disposition (parse_run_id);
CREATE INDEX idx_packet_disposition_candidate ON packet_disposition (candidate_id);
