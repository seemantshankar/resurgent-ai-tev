-- Ticket 13 follow-up: audit_log.parse_run_id becomes nullable so ingest
-- rejections (which happen before any parse_run row exists) can be audited
-- too, not just successful parse runs. SQLite has no ALTER COLUMN, so the
-- table is rebuilt: new table without the NOT NULL, copy rows, swap in.

CREATE TABLE audit_log_new (
    audit_log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    event_type          TEXT NOT NULL,
    event_at            TEXT NOT NULL,
    payload             TEXT,                       -- JSON object
    severity            TEXT NOT NULL               -- 'info' | 'warning' | 'error'
);

INSERT INTO audit_log_new (audit_log_id, parse_run_id, event_type, event_at, payload, severity)
SELECT audit_log_id, parse_run_id, event_type, event_at, payload, severity FROM audit_log;

DROP TABLE audit_log;
ALTER TABLE audit_log_new RENAME TO audit_log;

CREATE INDEX idx_audit_log_parse_run ON audit_log (parse_run_id);
