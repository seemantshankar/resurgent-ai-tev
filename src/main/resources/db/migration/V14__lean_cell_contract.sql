-- FM Loader lean cell contract: drop cosmetic normalization and unused label/style columns.

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
    formula_state           TEXT,
    cached_value            TEXT,
    cache_state             TEXT,
    coerced_from_text       INTEGER NOT NULL DEFAULT 0 CHECK (coerced_from_text IN (0, 1)),
    is_error                INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type              TEXT,
    is_merged_anchor        INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1)),
    is_merged_participant   INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1)),
    merged_range            TEXT,
    value_source            TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor')),
    row_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1)),
    col_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1)),
    sheet_hidden            INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1))
);

INSERT INTO cell_new (
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_state, cached_value, cache_state, coerced_from_text, is_error, error_type,
    is_merged_anchor, is_merged_participant, merged_range, value_source,
    row_hidden, col_hidden, sheet_hidden
)
SELECT
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type,
    text_value, display_value, numeric_value, bool_value, date_value, formula_text,
    formula_state, cached_value, cache_state, coerced_from_text, is_error, error_type,
    is_merged_anchor, is_merged_participant, merged_range, value_source,
    row_hidden, col_hidden, sheet_hidden
FROM cell;

DROP TABLE cell;
ALTER TABLE cell_new RENAME TO cell;

CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord);
CREATE INDEX idx_cell_numeric ON cell (numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text ON cell (text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_error ON cell (is_error, error_type);
CREATE INDEX idx_cell_value_source ON cell (value_source);
