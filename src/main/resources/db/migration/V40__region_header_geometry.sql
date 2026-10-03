-- Where each region keeps its column-header bands and row-label columns, as the LLM read it off
-- the grid. The label resolver assembles every cell's row/column text from this; a region with
-- no row here falls back to walking the grid.
CREATE TABLE region_header_geometry (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    geometry_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
