-- Layer B: one nomenclature path per main/helper cell.
-- Formula errors and scratch cells are not rows here.
-- amount_role is set only on economic money amounts.

CREATE TABLE nomenclature_binding (
    binding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    path_root TEXT NOT NULL CHECK (path_root IN ('economic', 'identity', 'frame')),
    path TEXT NOT NULL,
    amount_role TEXT CHECK (amount_role IS NULL OR amount_role IN ('add', 'deduct', 'total', 'helper')),
    verbatim TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
);

CREATE INDEX idx_nomenclature_binding_parse_run ON nomenclature_binding (parse_run_id);
CREATE INDEX idx_nomenclature_binding_path ON nomenclature_binding (path);
CREATE INDEX idx_nomenclature_binding_cell ON nomenclature_binding (cell_id);
