-- Why a money cell has the scale it has when nothing stated it: the workbook's other numbers point to it.
-- `scale_basis = inferred` in cell_reading covers both this and a scale taken from the sheets that read the
-- cell; a row here marks the magnitude inference, with the numbers it rests on. A money cell with no scale and
-- no row here had no stated scale and no clear answer.
CREATE TABLE cell_scale_inference (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    scale TEXT NOT NULL,
    evidence TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, cell_id)
);
