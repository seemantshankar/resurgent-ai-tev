-- Cell interpretation snapshot after classify (#116).
-- One row per persisted cell in the parse run; evidence filled by #117.

CREATE TABLE cell_interpretation (
    interpretation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    value_origin TEXT NOT NULL CHECK (value_origin IN ('literal', 'formula')),
    resulting_value TEXT,
    result_source TEXT NOT NULL CHECK (result_source IN ('literal', 'formula_cache', 'missing_cache')),
    formula_text TEXT,
    formula_state TEXT,
    cache_state TEXT,
    is_error INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type TEXT,
    nomenclature_path TEXT,
    amount_role TEXT CHECK (amount_role IS NULL OR amount_role IN ('add', 'deduct', 'total', 'helper')),
    soft_leaf INTEGER CHECK (soft_leaf IS NULL OR soft_leaf IN (0, 1)),
    via_alias INTEGER CHECK (via_alias IS NULL OR via_alias IN (0, 1)),
    nomenclature_status TEXT NOT NULL CHECK (
        nomenclature_status IN ('bound', 'unbound', 'not_applicable')),
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
);

CREATE INDEX idx_cell_interpretation_status
    ON cell_interpretation (parse_run_id, nomenclature_status);
CREATE INDEX idx_cell_interpretation_path
    ON cell_interpretation (parse_run_id, nomenclature_path);

CREATE TABLE cell_interpretation_evidence (
    evidence_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    role TEXT NOT NULL CHECK (role IN (
        'row_header', 'column_header', 'period', 'basis', 'currency', 'scale', 'unit')),
    source_cell_id INTEGER REFERENCES cell (cell_id),
    source_text TEXT,
    ordinal INTEGER NOT NULL,
    resolution TEXT NOT NULL CHECK (resolution IN ('resolved', 'missing', 'ambiguous')),
    normalized_value TEXT,
    rule_id TEXT,
    FOREIGN KEY (parse_run_id, cell_id)
        REFERENCES cell_interpretation (parse_run_id, cell_id) ON DELETE CASCADE
);

CREATE INDEX idx_cell_interpretation_evidence_cell
    ON cell_interpretation_evidence (parse_run_id, cell_id, ordinal);
