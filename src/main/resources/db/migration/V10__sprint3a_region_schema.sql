-- Ticket 23: Region metadata. Region bboxes may overlap; membership is cell.region_id.
-- The invariant is coverage of occupied cells, never that region bboxes tile a worksheet.
CREATE TABLE region (
    region_id INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    region_key TEXT NOT NULL,
    start_row INTEGER NOT NULL, end_row INTEGER NOT NULL,
    start_col INTEGER NOT NULL, end_col INTEGER NOT NULL,
    header_rows TEXT,
    region_type TEXT NOT NULL DEFAULT 'unknown',
    region_conf REAL NOT NULL DEFAULT 0,
    cost_head_code TEXT,
    cost_head_label TEXT,
    serial_pattern TEXT,
    inferred_currency TEXT,
    inferred_unit TEXT,
    period_axis TEXT,
    schema_json TEXT,
    detection_reasons TEXT,
    UNIQUE (parse_run_id, region_key)
);

CREATE TABLE cell_new (
    cell_id INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    coord TEXT NOT NULL, row_num INTEGER NOT NULL, col_num INTEGER NOT NULL,
    raw_value TEXT, raw_type TEXT NOT NULL, value_type TEXT NOT NULL,
    text_value TEXT, display_value TEXT, numeric_value NUMERIC,
    bool_value INTEGER CHECK (bool_value IN (0, 1)), date_value TEXT,
    formula_text TEXT, formula_normalized TEXT, formula_state TEXT,
    cached_value TEXT, cache_state TEXT,
    coerced_from_text INTEGER NOT NULL DEFAULT 0 CHECK (coerced_from_text IN (0, 1)),
    parsed_quantity TEXT, is_error INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type TEXT, error_descendant INTEGER NOT NULL DEFAULT 0 CHECK (error_descendant IN (0, 1)),
    error_root_cell_id INTEGER REFERENCES cell (cell_id),
    row_label TEXT, col_label TEXT,
    is_merged_anchor INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1)),
    is_merged_participant INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1)),
    merged_range TEXT, value_source TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor')),
    row_hidden INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1)),
    col_hidden INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1)),
    sheet_hidden INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1)),
    is_circular INTEGER NOT NULL DEFAULT 0 CHECK (is_circular IN (0, 1)), circular_group_id INTEGER,
    row_hash TEXT, region_id INTEGER REFERENCES region (region_id), provenance_id INTEGER,
    is_scratch INTEGER NOT NULL DEFAULT 0 CHECK (is_scratch IN (0, 1)), scratch_reason TEXT,
    is_orphan INTEGER NOT NULL DEFAULT 0 CHECK (is_orphan IN (0, 1)), extraction_conf REAL DEFAULT 1.0,
    formula_skeleton TEXT, formula_skeleton_regional TEXT, coherence_score REAL, coherence_dirs TEXT,
    tags TEXT DEFAULT '{}', is_error_barrier INTEGER NOT NULL DEFAULT 0 CHECK (is_error_barrier IN (0, 1)),
    is_bold INTEGER CHECK (is_bold IN (0, 1)), has_fill INTEGER CHECK (has_fill IN (0, 1)),
    has_border INTEGER CHECK (has_border IN (0, 1)), number_format TEXT
);

INSERT INTO cell_new (
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type, text_value,
    display_value, numeric_value, bool_value, date_value, formula_text, formula_normalized,
    formula_state, cached_value, cache_state, coerced_from_text, parsed_quantity, is_error,
    error_type, error_descendant, error_root_cell_id, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden,
    is_circular, circular_group_id, row_hash, region_id, provenance_id, is_scratch, scratch_reason,
    is_orphan, extraction_conf, formula_skeleton, coherence_score, coherence_dirs, tags, is_error_barrier
)
SELECT
    cell_id, worksheet_id, coord, row_num, col_num, raw_value, raw_type, value_type, text_value,
    display_value, numeric_value, bool_value, date_value, formula_text, formula_normalized,
    formula_state, cached_value, cache_state, coerced_from_text, parsed_quantity, is_error,
    error_type, error_descendant, error_root_cell_id, row_label, col_label, is_merged_anchor,
    is_merged_participant, merged_range, value_source, row_hidden, col_hidden, sheet_hidden,
    is_circular, circular_group_id, row_hash, region_id, provenance_id, is_scratch, scratch_reason,
    is_orphan, extraction_conf, formula_skeleton, coherence_score, coherence_dirs, tags, is_error_barrier
FROM cell;

DROP TABLE cell;
ALTER TABLE cell_new RENAME TO cell;

ALTER TABLE worksheet ADD COLUMN role TEXT;
ALTER TABLE worksheet ADD COLUMN role_conf REAL;

CREATE INDEX idx_cell_region ON cell (region_id) WHERE region_id IS NOT NULL;
CREATE INDEX idx_region_key ON region (region_key);
CREATE INDEX idx_region_ws ON region (worksheet_id);
CREATE INDEX idx_region_parse_run ON region (parse_run_id);
CREATE INDEX idx_region_cost_head ON region (cost_head_code) WHERE cost_head_code IS NOT NULL;
