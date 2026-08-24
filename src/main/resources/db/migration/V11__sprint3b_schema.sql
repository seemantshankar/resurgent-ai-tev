-- Sprint 3b target schema. V11 may also wipe parser-owned operational rows
-- (handled by WorkspaceDatabase) before these statements run.

ALTER TABLE region ADD COLUMN inferred_currency_conf REAL NOT NULL DEFAULT 0;
ALTER TABLE region ADD COLUMN inferred_unit_conf REAL NOT NULL DEFAULT 0;
ALTER TABLE region ADD COLUMN semantic_region_type TEXT;

ALTER TABLE cell ADD COLUMN is_support INTEGER NOT NULL DEFAULT 0 CHECK (is_support IN (0, 1));
ALTER TABLE cell ADD COLUMN support_reason TEXT;

ALTER TABLE review_queue ADD COLUMN subject_kind TEXT;
ALTER TABLE review_queue ADD COLUMN subject_key TEXT;
ALTER TABLE review_queue ADD COLUMN confidence REAL;
ALTER TABLE review_queue ADD COLUMN carried_from_decision_id INTEGER;

CREATE TABLE cost_head (
    cost_head_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id      INTEGER NOT NULL,
    code            TEXT NOT NULL,
    label           TEXT,
    classification  TEXT,
    UNIQUE (mandate_id, code)
);

CREATE TABLE cost_head_mapping (
    cost_head_mapping_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id         INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    source_file_id       INTEGER NOT NULL REFERENCES source_file (source_file_id),
    cost_head_id         INTEGER NOT NULL REFERENCES cost_head (cost_head_id),
    region_id            INTEGER NOT NULL REFERENCES region (region_id),
    region_key           TEXT NOT NULL,
    match_method         TEXT NOT NULL CHECK (match_method IN ('exact_alias', 'fuzzy_proposal', 'carried')),
    match_score          REAL,
    runner_up_margin     REAL,
    confidence           REAL NOT NULL,
    reasons              TEXT NOT NULL,
    source_label         TEXT,
    UNIQUE (parse_run_id, region_id, cost_head_id)
);

CREATE TABLE cost_head_candidate (
    cost_head_candidate_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id          INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    source_file_id        INTEGER NOT NULL REFERENCES source_file (source_file_id),
    cost_head_id          INTEGER NOT NULL REFERENCES cost_head (cost_head_id),
    candidate_fingerprint TEXT NOT NULL,
    amount                NUMERIC,
    currency              TEXT,
    unit                  TEXT,
    automatic_trust_eligible INTEGER NOT NULL CHECK (automatic_trust_eligible IN (0, 1)),
    confidence            REAL NOT NULL,
    reasons               TEXT NOT NULL,
    UNIQUE (parse_run_id, cost_head_id, candidate_fingerprint)
);

CREATE TABLE cost_head_contribution (
    cost_head_contribution_id INTEGER PRIMARY KEY AUTOINCREMENT,
    cost_head_candidate_id INTEGER NOT NULL REFERENCES cost_head_candidate (cost_head_candidate_id),
    cost_head_mapping_id    INTEGER REFERENCES cost_head_mapping (cost_head_mapping_id),
    region_id             INTEGER REFERENCES region (region_id),
    anchor_cell_id        INTEGER REFERENCES cell (cell_id),
    basis                  TEXT NOT NULL CHECK (basis IN (
                              'explicit_total_anchor', 'structural_total', 'leaf_sum', 'manual')),
    source_amount          NUMERIC NOT NULL,
    source_currency       TEXT,
    source_unit           TEXT,
    normalized_amount     NUMERIC,
    normalized_currency   TEXT,
    normalized_unit       TEXT,
    confidence            REAL NOT NULL,
    reasons               TEXT NOT NULL
);

CREATE TABLE cost_head_contribution_cell (
    cost_head_contribution_id INTEGER NOT NULL REFERENCES cost_head_contribution (cost_head_contribution_id),
    cell_id               INTEGER NOT NULL REFERENCES cell (cell_id),
    participation         TEXT NOT NULL CHECK (participation IN ('included', 'excluded')),
    reason                TEXT,
    PRIMARY KEY (cost_head_contribution_id, cell_id)
);

CREATE TABLE duplicate_proposal (
    duplicate_proposal_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id          INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    left_region_id        INTEGER NOT NULL REFERENCES region (region_id),
    right_region_id       INTEGER NOT NULL REFERENCES region (region_id),
    method                TEXT NOT NULL CHECK (method IN ('exact_row_hash', 'shifted_block_signature')),
    score                 REAL NOT NULL,
    reasons               TEXT NOT NULL,
    UNIQUE (parse_run_id, left_region_id, right_region_id, method)
);

CREATE TABLE duplicate_decision (
    duplicate_decision_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id        INTEGER NOT NULL REFERENCES source_file (source_file_id),
    left_region_key       TEXT NOT NULL,
    right_region_key      TEXT NOT NULL,
    decision              TEXT NOT NULL CHECK (decision IN ('Duplicate', 'Distinct')),
    superseded_region_key TEXT,
    actor                 TEXT NOT NULL,
    reason                TEXT,
    decided_at            TEXT NOT NULL,
    supersedes_id         INTEGER REFERENCES duplicate_decision (duplicate_decision_id)
);

CREATE TABLE manual_contribution (
    manual_contribution_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id        INTEGER NOT NULL REFERENCES source_file (source_file_id),
    cost_head_id          INTEGER NOT NULL REFERENCES cost_head (cost_head_id),
    adjusts_contribution_id INTEGER REFERENCES cost_head_contribution (cost_head_contribution_id),
    amount                NUMERIC NOT NULL,
    currency              TEXT NOT NULL,
    unit                  TEXT NOT NULL,
    reason                TEXT NOT NULL,
    actor                 TEXT NOT NULL,
    status                TEXT NOT NULL CHECK (status IN ('Pending', 'Accepted', 'Rejected', 'Withdrawn')),
    created_at            TEXT NOT NULL,
    decided_at            TEXT
);

CREATE TABLE cost_head_mapping_decision (
    mapping_decision_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id       INTEGER NOT NULL REFERENCES source_file (source_file_id),
    region_key           TEXT NOT NULL,
    cost_head_code       TEXT NOT NULL,
    source_label         TEXT,
    decision             TEXT NOT NULL CHECK (decision IN (
                            'Accepted', 'Rejected', 'Insufficient evidence', 'Unable to validate')),
    actor                TEXT NOT NULL,
    reason               TEXT,
    decided_at           TEXT NOT NULL,
    supersedes_id        INTEGER REFERENCES cost_head_mapping_decision (mapping_decision_id)
);

CREATE TABLE cost_head_total_decision (
    total_decision_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id       INTEGER NOT NULL REFERENCES source_file (source_file_id),
    cost_head_code       TEXT NOT NULL,
    candidate_fingerprint TEXT NOT NULL,
    decision             TEXT NOT NULL CHECK (decision IN (
                            'Accepted', 'Rejected', 'Insufficient evidence', 'Unable to validate')),
    actor                TEXT NOT NULL,
    reason               TEXT,
    decided_at           TEXT NOT NULL,
    supersedes_id       INTEGER REFERENCES cost_head_total_decision (total_decision_id)
);

CREATE INDEX idx_cost_head_mandate ON cost_head (mandate_id);
CREATE INDEX idx_cost_head_mapping_region_key ON cost_head_mapping (source_file_id, region_key);
CREATE INDEX idx_cost_head_mapping_cost_head ON cost_head_mapping (cost_head_id);
CREATE INDEX idx_cost_head_candidate_cost_head ON cost_head_candidate (cost_head_id, automatic_trust_eligible);
CREATE INDEX idx_cost_head_candidate_fingerprint ON cost_head_candidate (source_file_id, candidate_fingerprint);
CREATE INDEX idx_cost_head_contribution_candidate ON cost_head_contribution (cost_head_candidate_id);
CREATE INDEX idx_cost_head_contribution_cell ON cost_head_contribution_cell (cell_id);
CREATE INDEX idx_duplicate_proposal_parse_run ON duplicate_proposal (parse_run_id);
CREATE INDEX idx_cost_head_mapping_decision ON cost_head_mapping_decision (source_file_id, region_key, cost_head_code);
CREATE INDEX idx_cost_head_total_decision ON cost_head_total_decision (source_file_id, cost_head_code, candidate_fingerprint);
