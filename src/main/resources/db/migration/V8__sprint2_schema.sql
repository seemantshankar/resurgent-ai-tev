-- Ticket 14: V8 migration - reference graph schema and cell table rebuild.
-- ADR 0002 compliance: BOOLEAN -> INTEGER CHECK(0|1); BIGSERIAL -> INTEGER PRIMARY KEY AUTOINCREMENT.

PRAGMA foreign_keys = OFF;

CREATE TABLE cell_new (
    cell_id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id            INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    coord                   TEXT NOT NULL,
    row_num                 INTEGER NOT NULL,
    col_num                 INTEGER NOT NULL,
    raw_value               TEXT,
    raw_type                TEXT NOT NULL,
    value_type              TEXT NOT NULL,
    text_value              TEXT,
    display_value           TEXT,
    numeric_value           NUMERIC,
    bool_value              INTEGER CHECK (bool_value IN (0, 1)),
    date_value              TEXT,
    formula_text            TEXT,
    formula_normalized      TEXT,
    formula_state           TEXT,
    cached_value            TEXT,
    cache_state             TEXT,
    coerced_from_text       INTEGER NOT NULL DEFAULT 0 CHECK (coerced_from_text IN (0, 1)),
    parsed_quantity         TEXT,
    is_error                INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type              TEXT,
    error_descendant        INTEGER NOT NULL DEFAULT 0 CHECK (error_descendant IN (0, 1)),
    error_root_cell_id      INTEGER REFERENCES cell (cell_id),
    row_label               TEXT,
    col_label               TEXT,
    is_merged_anchor        INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1)),
    is_merged_participant   INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1)),
    merged_range            TEXT,
    value_source            TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor')),
    row_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1)),
    col_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1)),
    sheet_hidden            INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1)),
    is_circular             INTEGER NOT NULL DEFAULT 0 CHECK (is_circular IN (0, 1)),
    circular_group_id       TEXT,
    row_hash                TEXT,
    region_id               INTEGER,
    provenance_id           INTEGER,
    is_scratch              INTEGER NOT NULL DEFAULT 0 CHECK (is_scratch IN (0, 1)),
    scratch_reason          TEXT,
    is_orphan               INTEGER NOT NULL DEFAULT 0 CHECK (is_orphan IN (0, 1)),
    extraction_conf         REAL DEFAULT 1.0,
    formula_skeleton        TEXT,
    coherence_score         REAL,
    coherence_dirs          TEXT,
    tags                    TEXT DEFAULT '{}'
);

INSERT INTO cell_new (
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_normalized, formula_state, cached_value, cache_state, coerced_from_text,
    parsed_quantity, is_error, error_type, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden
)
SELECT
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_normalized, formula_state, cached_value, cache_state, coerced_from_text,
    parsed_quantity, is_error, error_type, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden
FROM cell;

DROP TABLE cell;
ALTER TABLE cell_new RENAME TO cell;

PRAGMA foreign_keys = ON;

-- New tables for reference graph and error roots
CREATE TABLE cell_reference (
    cell_reference_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    from_cell_id        INTEGER NOT NULL REFERENCES cell (cell_id),
    token_index         INTEGER NOT NULL,
    raw_token           TEXT NOT NULL,
    ref_kind            TEXT NOT NULL,
    target_sheet_name   TEXT,
    target_worksheet_id INTEGER REFERENCES worksheet (worksheet_id),
    target_range        TEXT,
    resolved_cell_id    INTEGER REFERENCES cell (cell_id),
    external_link_id    INTEGER REFERENCES external_link (external_link_id),
    abs_row             INTEGER CHECK (abs_row IN (0, 1)),
    abs_col             INTEGER CHECK (abs_col IN (0, 1)),
    row_offset          INTEGER,
    col_offset          INTEGER,
    is_whole_column     INTEGER NOT NULL DEFAULT 0 CHECK (is_whole_column IN (0, 1)),
    is_whole_row        INTEGER NOT NULL DEFAULT 0 CHECK (is_whole_row IN (0, 1)),
    unresolved_reason   TEXT
);

CREATE TABLE cell_error_root (
    cell_id             INTEGER NOT NULL REFERENCES cell (cell_id),
    error_root_cell_id  INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (cell_id, error_root_cell_id)
);

-- Workbook calculation metadata extension
ALTER TABLE workbook ADD COLUMN calculation_mode TEXT;
ALTER TABLE workbook ADD COLUMN full_calc_on_load INTEGER CHECK (full_calc_on_load IN (0, 1));
ALTER TABLE workbook ADD COLUMN calc_chain_present INTEGER CHECK (calc_chain_present IN (0, 1));
ALTER TABLE workbook ADD COLUMN iterative_calc INTEGER CHECK (iterative_calc IN (0, 1));
ALTER TABLE workbook ADD COLUMN iterative_count INTEGER;
ALTER TABLE workbook ADD COLUMN error_cell_count INTEGER;

-- Indexes
CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord);
CREATE INDEX idx_cell_numeric ON cell (numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text ON cell (text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_error ON cell (is_error, error_type);
CREATE INDEX idx_cell_circular ON cell (circular_group_id) WHERE circular_group_id IS NOT NULL;
CREATE INDEX idx_cellref_from ON cell_reference (from_cell_id);
CREATE INDEX idx_cellref_resolved ON cell_reference (resolved_cell_id) WHERE resolved_cell_id IS NOT NULL;
CREATE INDEX idx_cellref_unresolved ON cell_reference (unresolved_reason) WHERE unresolved_reason IS NOT NULL;
CREATE INDEX idx_cellref_external ON cell_reference (external_link_id) WHERE external_link_id IS NOT NULL;
CREATE INDEX idx_error_root ON cell_error_root (error_root_cell_id);
