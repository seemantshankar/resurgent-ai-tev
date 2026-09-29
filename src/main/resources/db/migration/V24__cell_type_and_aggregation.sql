-- Layer B by typing inputs and propagating through formulas.
-- Binding provenance, unbound reasons, and the evidence behind every binding:
-- how each numeric cell was typed, and which aggregation it belongs to.

-- Existing rows all came from the per-cell Layer B prompt, so 'llm_line' is the
-- truthful default rather than a placeholder.
ALTER TABLE nomenclature_binding ADD COLUMN source TEXT NOT NULL DEFAULT 'llm_line';
ALTER TABLE nomenclature_binding ADD COLUMN label_key TEXT;
ALTER TABLE nomenclature_binding ADD COLUMN aggregation_id INTEGER;

-- No CHECK: the reason set is enumerated in classify/UnboundReason and asserted
-- by a unit test, so adding a reason does not need a migration.
ALTER TABLE cell_interpretation ADD COLUMN unbound_reason TEXT;

-- One row per numeric cell that typed: what it is, at what scale, and whether
-- that came from the cell itself or from propagation through its formula.
CREATE TABLE cell_type (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    kind TEXT NOT NULL,
    scale TEXT NOT NULL,
    type_source TEXT NOT NULL,
    depth INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (parse_run_id, cell_id)
);

CREATE INDEX idx_cell_type_kind ON cell_type (parse_run_id, kind);

-- An aggregation head is a SUM over a range or a plus-minus chain. Membership is
-- read from the formula, never guessed from layout. The relative signature is the
-- R1C1-relative formula shape, which collapses a repeated row-series to one group.
CREATE TABLE aggregation (
    aggregation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    head_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id) ON DELETE CASCADE,
    relative_signature TEXT NOT NULL,
    head_label TEXT,
    resolved_kind TEXT,
    resolved_scale TEXT,
    UNIQUE (parse_run_id, head_cell_id)
);

CREATE INDEX idx_aggregation_parse_run ON aggregation (parse_run_id);
CREATE INDEX idx_aggregation_signature ON aggregation (parse_run_id, relative_signature);

CREATE TABLE aggregation_member (
    aggregation_id INTEGER NOT NULL
        REFERENCES aggregation (aggregation_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    member_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    sign TEXT NOT NULL CHECK (sign IN ('plus', 'minus')),
    amount_role TEXT NOT NULL,
    member_label TEXT,
    PRIMARY KEY (aggregation_id, ordinal)
);

CREATE INDEX idx_aggregation_member_cell ON aggregation_member (member_cell_id);
