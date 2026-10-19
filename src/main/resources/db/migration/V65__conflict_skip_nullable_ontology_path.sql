-- M3-14 (#147, CodeRabbit review): a money fact the doc-bind stage never ran over
-- carries no shared path at all. Its refusal is still worth a row, so the skip table
-- stores the missing path as NULL instead of the insert failing and rolling back the
-- whole assess_conflicts transaction. SQLite has no ALTER COLUMN: rebuild and swap.

CREATE TABLE conflict_skip_new (
    skip_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id INTEGER NOT NULL,
    assessment_run_id INTEGER REFERENCES assessment_run (assessment_run_id),
    kind TEXT NOT NULL CHECK (kind = 'doc-fm'),
    data_point TEXT NOT NULL,
    ontology_path TEXT,
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    page_index INTEGER,
    fm_source TEXT,
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    created_at TEXT NOT NULL
);

INSERT INTO conflict_skip_new (skip_id, mandate_id, assessment_run_id, kind, data_point,
        ontology_path, document_version_id, page_index, fm_source, reason, created_at)
SELECT skip_id, mandate_id, assessment_run_id, kind, data_point,
        ontology_path, document_version_id, page_index, fm_source, reason, created_at
FROM conflict_skip;

DROP TABLE conflict_skip;
ALTER TABLE conflict_skip_new RENAME TO conflict_skip;

CREATE INDEX idx_conflict_skip_mandate ON conflict_skip (mandate_id);
