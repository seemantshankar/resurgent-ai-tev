-- Ticket 05 + Ticket 06: structural enrichment and external-reference support.

-- Worksheet real bounding box and diagnostics.
ALTER TABLE worksheet ADD COLUMN bbox_min_row INTEGER;
ALTER TABLE worksheet ADD COLUMN bbox_min_col INTEGER;
ALTER TABLE worksheet ADD COLUMN bbox_max_row INTEGER;
ALTER TABLE worksheet ADD COLUMN bbox_max_col INTEGER;
ALTER TABLE worksheet ADD COLUMN dimensions_declared TEXT;
ALTER TABLE worksheet ADD COLUMN real_content_rows INTEGER;
ALTER TABLE worksheet ADD COLUMN declared_merged INTEGER;

-- Merged-cell flags and value ownership.
ALTER TABLE cell ADD COLUMN is_merged_anchor INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1));
ALTER TABLE cell ADD COLUMN is_merged_participant INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1));
ALTER TABLE cell ADD COLUMN merged_range TEXT;
ALTER TABLE cell ADD COLUMN value_source TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor'));

-- Hidden flags per cell.
ALTER TABLE cell ADD COLUMN row_hidden INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1));
ALTER TABLE cell ADD COLUMN col_hidden INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1));
ALTER TABLE cell ADD COLUMN sheet_hidden INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1));

-- External reference support.
ALTER TABLE external_link ADD COLUMN link_index INTEGER;
ALTER TABLE cell ADD COLUMN external_ref TEXT;
ALTER TABLE cell ADD COLUMN external_link_id INTEGER REFERENCES external_link (external_link_id);
-- JSON array of local sheet refs
ALTER TABLE cell ADD COLUMN sheet_refs TEXT;
-- JSON array of defined-name refs
ALTER TABLE cell ADD COLUMN defined_name_refs TEXT;

-- Indexes for the new filters.
CREATE INDEX idx_cell_value_source ON cell (value_source);
CREATE INDEX idx_cell_external_ref ON cell (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX idx_external_link_index ON external_link (workbook_id, link_index);
