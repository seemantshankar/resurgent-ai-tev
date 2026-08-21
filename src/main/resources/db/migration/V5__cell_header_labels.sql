-- Ticket 05: denormalize row/column header labels onto each cell for semantic queries.

ALTER TABLE cell ADD COLUMN row_label TEXT;
ALTER TABLE cell ADD COLUMN col_label TEXT;

CREATE INDEX idx_cell_row_label ON cell (row_label);
CREATE INDEX idx_cell_col_label ON cell (col_label);
