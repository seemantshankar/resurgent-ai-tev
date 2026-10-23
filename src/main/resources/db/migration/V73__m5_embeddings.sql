-- M5-6 (#174): clause and passage embeddings, plus the induction decision log.
-- #176 records pack and checklist decisions in this same table; it must not create
-- a second induction_decision. Rationale and the sanitized web query live in
-- detail_json so later stages can fill them without a new table.
-- vec is a little-endian float32[] blob. Re-assess replaces the parent row, and
-- the embedding cascades with it; upserts on the parent id stay idempotent.

CREATE TABLE clause_embedding (
    clause_id INTEGER PRIMARY KEY REFERENCES clause (clause_id) ON DELETE CASCADE,
    model     TEXT NOT NULL,
    dims      INTEGER NOT NULL,
    vec       BLOB NOT NULL
);

CREATE TABLE passage_embedding (
    passage_id INTEGER PRIMARY KEY REFERENCES passage (passage_id) ON DELETE CASCADE,
    model      TEXT NOT NULL,
    dims       INTEGER NOT NULL,
    vec        BLOB NOT NULL
);

CREATE TABLE induction_decision (
    decision_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id    INTEGER NOT NULL,
    parse_run_id  INTEGER REFERENCES parse_run (parse_run_id),
    stage         TEXT NOT NULL CHECK (stage IN ('induce_pack', 'induce_clauses', 'induce_checklist')),
    target_code   TEXT,
    candidate_key TEXT,
    outcome       TEXT NOT NULL CHECK (outcome IN ('promoted', 'rewritten', 'demoted_optional', 'dropped')),
    reason_code   TEXT,
    detail_json   TEXT,
    created_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_induction_decision_mandate_stage
    ON induction_decision (mandate_id, stage);
