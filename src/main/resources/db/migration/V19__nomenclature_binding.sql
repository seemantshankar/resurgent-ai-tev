-- Layer B nomenclature bindings keyed by parse run + amount cell (#107).
-- Re-classify replaces these rows with Layer A; Candidate geometry is unchanged.
-- Peers are out of scope for this migration.

CREATE TABLE nomenclature_binding (
    binding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    verbatim TEXT NOT NULL,
    path TEXT NOT NULL,
    amount_role TEXT NOT NULL CHECK (amount_role IN ('add', 'deduct', 'total', 'helper')),
    soft_leaf INTEGER NOT NULL CHECK (soft_leaf IN (0, 1)),
    via_alias INTEGER NOT NULL CHECK (via_alias IN (0, 1)),
    confidence REAL,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
);

CREATE INDEX idx_nomenclature_binding_parse_run ON nomenclature_binding (parse_run_id);
CREATE INDEX idx_nomenclature_binding_path ON nomenclature_binding (path);
CREATE INDEX idx_nomenclature_binding_cell ON nomenclature_binding (cell_id);
