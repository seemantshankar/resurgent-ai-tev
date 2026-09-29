-- Formula graph: direct reads plus transitive precedents. Dependents are the
-- reverse of formula_reach. Reference edges stay unexpanded tokens.

CREATE TABLE formula_link (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id      INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (from_cell_id, to_cell_id)
);

CREATE INDEX idx_formula_link_to ON formula_link (to_cell_id);

CREATE TABLE formula_reach (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id      INTEGER NOT NULL REFERENCES cell (cell_id),
    depth           INTEGER NOT NULL CHECK (depth >= 1),
    PRIMARY KEY (from_cell_id, to_cell_id)
);

CREATE INDEX idx_formula_reach_to ON formula_reach (to_cell_id);

CREATE TABLE formula_gap (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    reason          TEXT NOT NULL,
    raw_token       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (from_cell_id, reason, raw_token)
);
