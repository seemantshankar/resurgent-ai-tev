-- Layer B line peers (#108) and ProjectFact bindings (#109).

CREATE TABLE nomenclature_binding_peer (
    peer_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    peer_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    peer_reason TEXT NOT NULL CHECK (peer_reason IN ('anti_double_count')),
    path_resolved INTEGER NOT NULL CHECK (path_resolved IN (0, 1)),
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id, peer_cell_id, peer_reason)
);

CREATE INDEX idx_binding_peer_parse_run ON nomenclature_binding_peer (parse_run_id);
CREATE INDEX idx_binding_peer_cell ON nomenclature_binding_peer (parse_run_id, cell_id);

CREATE TABLE project_fact_field (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE project_fact_binding (
    fact_binding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER REFERENCES cell (cell_id),
    verbatim TEXT NOT NULL,
    fact_path TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, candidate_id, fact_path, verbatim)
);

CREATE INDEX idx_project_fact_binding_parse_run ON project_fact_binding (parse_run_id);
CREATE INDEX idx_project_fact_binding_cell ON project_fact_binding (parse_run_id, cell_id);
