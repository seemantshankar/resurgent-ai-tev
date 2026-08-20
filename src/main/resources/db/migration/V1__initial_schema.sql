-- Sprint 1 walking skeleton (ticket 01): minimal intake schema.
-- PostgreSQL semantic contract mapped per ADR 0002:
-- BIGSERIAL -> INTEGER PRIMARY KEY AUTOINCREMENT, TIMESTAMPTZ -> ISO-8601 TEXT, JSONB -> TEXT.
-- The schema_migration bookkeeping table is created by the migrator, not here.

CREATE TABLE source_file (
    source_file_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id      INTEGER NOT NULL,
    file_name       TEXT NOT NULL,
    file_hash       TEXT NOT NULL,
    file_type       TEXT NOT NULL,      -- 'fm_xlsx' | 'fm_xls' | 'fm_csv'
    ingested_at     TEXT NOT NULL,
    parser_version  TEXT NOT NULL,
    UNIQUE (mandate_id, file_hash)
);

CREATE TABLE parse_run (
    parse_run_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id  INTEGER NOT NULL REFERENCES source_file (source_file_id),
    mandate_id      INTEGER NOT NULL,
    parser_version  TEXT NOT NULL,
    config_hash     TEXT,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    status          TEXT NOT NULL,      -- 'success' | 'partial' | 'failed' | 'rejected'
    metrics         TEXT,
    warnings        TEXT,
    errors          TEXT,
    UNIQUE (source_file_id, parser_version, config_hash)
);

CREATE TABLE worksheet (
    worksheet_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id    INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    sheet_name      TEXT NOT NULL,      -- verbatim
    sheet_index     INTEGER NOT NULL
);

CREATE TABLE cell (
    cell_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id    INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    coord           TEXT NOT NULL,
    row_num         INTEGER NOT NULL,
    col_num         INTEGER NOT NULL,
    raw_value       TEXT,               -- stringified source value
    raw_type        TEXT NOT NULL,      -- number|text|bool|date|empty|formula|error
    value_type      TEXT NOT NULL,      -- number|text|quantity_text|date|bool|empty|error|formula
    text_value      TEXT,
    display_value   TEXT
);

CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord);
