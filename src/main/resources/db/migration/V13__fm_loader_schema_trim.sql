-- ADR 0009: FM Loader ingest-only schema. Drop the heuristic semantic stack
-- (regions, cost heads, reference graph, formula skeleton columns) and rebuild
-- cell/worksheet with only ingest-persisted columns.

DROP TABLE IF EXISTS cost_head_contribution_cell;
DROP TABLE IF EXISTS cost_head_contribution;
DROP TABLE IF EXISTS cost_head_mapping;
DROP TABLE IF EXISTS cost_head_candidate;
DROP TABLE IF EXISTS duplicate_proposal;
DROP TABLE IF EXISTS manual_contribution;
DROP TABLE IF EXISTS cost_head_mapping_decision;
DROP TABLE IF EXISTS cost_head_total_decision;
DROP TABLE IF EXISTS duplicate_decision;
DROP TABLE IF EXISTS cost_head;
DROP TABLE IF EXISTS cell_reference;
DROP TABLE IF EXISTS cell_error_root;

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
    row_label               TEXT,
    col_label               TEXT,
    is_merged_anchor        INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1)),
    is_merged_participant   INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1)),
    merged_range            TEXT,
    value_source            TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor')),
    row_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1)),
    col_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1)),
    sheet_hidden            INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1)),
    is_bold                 INTEGER CHECK (is_bold IN (0, 1)),
    has_fill                INTEGER CHECK (has_fill IN (0, 1)),
    has_border              INTEGER CHECK (has_border IN (0, 1)),
    number_format           TEXT,
    tags                    TEXT DEFAULT '{}'
);

INSERT INTO cell_new (
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_normalized, formula_state, cached_value, cache_state, coerced_from_text,
    parsed_quantity, is_error, error_type, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden,
    is_bold, has_fill, has_border, number_format, tags
)
SELECT
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_normalized, formula_state, cached_value, cache_state, coerced_from_text,
    parsed_quantity, is_error, error_type, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden,
    is_bold, has_fill, has_border, number_format, COALESCE(tags, '{}')
FROM cell;

DROP TABLE cell;
ALTER TABLE cell_new RENAME TO cell;

DROP TABLE IF EXISTS region;

CREATE TABLE worksheet_new (
    worksheet_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    sheet_name          TEXT NOT NULL,
    sheet_index         INTEGER NOT NULL,
    sheet_state         TEXT,
    bbox_min_row        INTEGER,
    bbox_min_col        INTEGER,
    bbox_max_row        INTEGER,
    bbox_max_col        INTEGER,
    dimensions_declared TEXT,
    real_content_rows   INTEGER,
    declared_merged     INTEGER
);

INSERT INTO worksheet_new (
    worksheet_id, parse_run_id, sheet_name, sheet_index, sheet_state,
    bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,
    dimensions_declared, real_content_rows, declared_merged
)
SELECT
    worksheet_id, parse_run_id, sheet_name, sheet_index, sheet_state,
    bbox_min_row, bbox_min_col, bbox_max_row, bbox_max_col,
    dimensions_declared, real_content_rows, declared_merged
FROM worksheet;

DROP TABLE worksheet;
ALTER TABLE worksheet_new RENAME TO worksheet;

CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord);
CREATE INDEX idx_cell_numeric ON cell (numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text ON cell (text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_error ON cell (is_error, error_type);
CREATE INDEX idx_cell_row_label ON cell (row_label);
CREATE INDEX idx_cell_col_label ON cell (col_label);
CREATE INDEX idx_cell_value_source ON cell (value_source);
