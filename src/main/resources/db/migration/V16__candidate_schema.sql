-- ADR 0016: persist Candidates with the parse run. Structural membership only —
-- not a revival of the dropped semantic region / cost-head tables (V13).

CREATE TABLE candidate (
    candidate_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
        'coverage_parent', 'child', 'parallel', 'overlap', 'related')),
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    bbox_min_row INTEGER,
    bbox_min_col INTEGER,
    bbox_max_row INTEGER,
    bbox_max_col INTEGER,
    internal_whitespace TEXT,
    anchors TEXT,
    structural_signatures TEXT,
    isolated_hidden_worksheet INTEGER NOT NULL DEFAULT 0
        CHECK (isolated_hidden_worksheet IN (0, 1)),
    structural_confidence REAL,
    structural_confidence_rationale TEXT,
    explanation TEXT,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_candidate_one_coverage_parent
    ON candidate (parse_run_id, worksheet_id)
    WHERE candidate_kind = 'coverage_parent';

CREATE INDEX idx_candidate_parse_run ON candidate (parse_run_id);
CREATE INDEX idx_candidate_worksheet ON candidate (worksheet_id);
CREATE INDEX idx_candidate_parent ON candidate (parent_candidate_id)
    WHERE parent_candidate_id IS NOT NULL;

CREATE TABLE candidate_member (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (candidate_id, cell_id)
);

CREATE INDEX idx_candidate_member_cell ON candidate_member (cell_id);

CREATE TABLE candidate_related (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    related_candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    relationship_kind TEXT NOT NULL,
    PRIMARY KEY (candidate_id, related_candidate_id, relationship_kind),
    CHECK (candidate_id != related_candidate_id)
);

CREATE INDEX idx_candidate_related_target ON candidate_related (related_candidate_id);
