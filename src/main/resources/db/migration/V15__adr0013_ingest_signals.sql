-- ADR 0013: restore shared cell_style flyweight, formula_normalized, and
-- cell_reference edges. Do not restore region/cost-head or other heuristic stack.

CREATE TABLE cell_style (
    style_id                INTEGER PRIMARY KEY AUTOINCREMENT,
    is_bold                 INTEGER CHECK (is_bold IN (0, 1)),
    number_format           TEXT,
    fill_fg_color           TEXT,
    fill_pattern            TEXT,
    border_top_style        TEXT,
    border_top_color        TEXT,
    border_right_style      TEXT,
    border_right_color      TEXT,
    border_bottom_style     TEXT,
    border_bottom_color     TEXT,
    border_left_style       TEXT,
    border_left_color       TEXT
);

ALTER TABLE cell ADD COLUMN style_id INTEGER REFERENCES cell_style (style_id);
ALTER TABLE cell ADD COLUMN formula_normalized TEXT;

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

CREATE INDEX idx_cell_style_id ON cell (style_id) WHERE style_id IS NOT NULL;
CREATE INDEX idx_cellref_from ON cell_reference (from_cell_id);
CREATE INDEX idx_cellref_resolved ON cell_reference (resolved_cell_id) WHERE resolved_cell_id IS NOT NULL;
CREATE INDEX idx_cellref_unresolved ON cell_reference (unresolved_reason) WHERE unresolved_reason IS NOT NULL;
CREATE INDEX idx_cellref_external ON cell_reference (external_link_id) WHERE external_link_id IS NOT NULL;
