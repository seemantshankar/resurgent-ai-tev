-- Sprint 1 schema completion (ticket 02): remaining tables mapped per ADR 0002.
-- JSONB -> TEXT with Jackson validation; BOOLEAN -> INTEGER CHECK(0|1);
-- INT[] -> TEXT containing JSON array; TIMESTAMPTZ -> ISO-8601 TEXT (UTC).

CREATE TABLE workbook (
    workbook_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id      INTEGER NOT NULL REFERENCES source_file (source_file_id),
    application_name    TEXT,
    application_version TEXT,
    sheet_count         INTEGER NOT NULL,
    sheet_names         TEXT,                       -- JSON array of sheet names
    defined_names       TEXT,                       -- JSON array
    properties          TEXT,                       -- JSON object
    is_protected        INTEGER NOT NULL DEFAULT 0 CHECK (is_protected IN (0, 1)),
    created_at          TEXT,
    modified_at         TEXT
);

CREATE TABLE external_link (
    external_link_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    workbook_id         INTEGER NOT NULL REFERENCES workbook (workbook_id),
    link_type           TEXT NOT NULL,              -- 'external' | 'hyperlink' | 'ole'
    target_path         TEXT NOT NULL,
    status              TEXT NOT NULL,              -- 'active' | 'broken' | 'unchecked'
    checked_at          TEXT
);

CREATE TABLE provenance (
    provenance_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type         TEXT NOT NULL,              -- 'cell' | 'worksheet' | 'workbook' | 'parse_run'
    entity_id           INTEGER NOT NULL,
    source_file_id      INTEGER NOT NULL REFERENCES source_file (source_file_id),
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    location            TEXT NOT NULL,
    raw_value           TEXT,
    confidence          REAL,
    is_derived          INTEGER NOT NULL DEFAULT 0 CHECK (is_derived IN (0, 1)),
    notes               TEXT
);

CREATE TABLE audit_log (
    audit_log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    event_type          TEXT NOT NULL,
    event_at            TEXT NOT NULL,
    payload             TEXT,                       -- JSON object
    severity            TEXT NOT NULL               -- 'info' | 'warning' | 'error'
);

CREATE TABLE review_queue (
    review_queue_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    category            TEXT NOT NULL,
    summary             TEXT NOT NULL,
    detail              TEXT,                       -- JSON object
    status              TEXT NOT NULL DEFAULT 'Pending',
    is_escalated        INTEGER NOT NULL DEFAULT 0 CHECK (is_escalated IN (0, 1)),
    created_at          TEXT NOT NULL,
    resolved_at         TEXT
);

CREATE TABLE ingest_rejection (
    ingest_rejection_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id      INTEGER REFERENCES source_file (source_file_id),
    mandate_id          INTEGER NOT NULL,
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,
    reason              TEXT NOT NULL,
    detail              TEXT,                       -- JSON object
    rejected_at         TEXT NOT NULL
);

CREATE INDEX idx_workbook_source_file        ON workbook (source_file_id);
CREATE INDEX idx_external_link_workbook      ON external_link (workbook_id);
CREATE INDEX idx_provenance_entity           ON provenance (entity_type, entity_id);
CREATE INDEX idx_provenance_source_file      ON provenance (source_file_id);
CREATE INDEX idx_audit_log_parse_run         ON audit_log (parse_run_id);
CREATE INDEX idx_review_queue_parse_run      ON review_queue (parse_run_id);
CREATE INDEX idx_ingest_rejection_source_file ON ingest_rejection (source_file_id);
CREATE INDEX idx_ingest_rejection_file       ON ingest_rejection (mandate_id, file_hash);
