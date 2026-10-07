-- How a quantity, count or rate cell got its unit of measurement when a model named it: `stated` when the
-- unit is in the cell's labels, region description or sheet name (evidence is the words copied from
-- them), `inferred` when the model worked it out from the subject. A cell with a unit and no row here had
-- it from its own labels or the learned dictionary.
CREATE TABLE cell_unit_basis (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    unit TEXT NOT NULL,
    basis TEXT NOT NULL CHECK (basis IN ('stated', 'inferred')),
    evidence TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, cell_id)
);
