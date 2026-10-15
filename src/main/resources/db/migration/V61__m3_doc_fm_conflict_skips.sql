-- M3-14 (#147): comparisons refused by the read-only FM adapter remain visible.
-- V59 already permits doc-fm conflicts with a cell citation on the model side.
CREATE TABLE conflict_skip (
    skip_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id INTEGER NOT NULL,
    assessment_run_id INTEGER REFERENCES assessment_run (assessment_run_id),
    kind TEXT NOT NULL CHECK (kind = 'doc-fm'),
    data_point TEXT NOT NULL,
    ontology_path TEXT NOT NULL,
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    page_index INTEGER,
    fm_source TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    created_at TEXT NOT NULL
);
CREATE INDEX idx_conflict_skip_mandate ON conflict_skip (mandate_id);
