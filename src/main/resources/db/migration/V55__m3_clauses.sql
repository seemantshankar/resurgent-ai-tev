-- Document Intelligence M3-08: clause reading (spec #133, issue #141).
-- clause_type is the clause vocabulary: types a pack binds for its document type
-- (source 'pack', status 'active') and types proposed for unmatched clauses (source
-- 'proposed', status 'open'); auto-promotion (#142) later flips proposals to 'promoted'.
-- clause keeps each clause verbatim with its page range, status bound/open, a one-line
-- summary, lender-risk importance and a provenance anchor into the layout.
-- clause_slot keeps the key numbers (notice period, penalty, tenure, …) normalised with
-- M2's normalisers. A re-run replaces a version's clauses; provisional provenance only.

CREATE TABLE clause_type (
    clause_type_id INTEGER PRIMARY KEY AUTOINCREMENT,
    code           TEXT NOT NULL,
    label          TEXT NOT NULL,
    parent_family  TEXT,
    aliases_json   TEXT,
    pack_code      TEXT,
    pack_version   TEXT,
    source         TEXT NOT NULL CHECK (source IN ('pack', 'proposed')),
    status         TEXT NOT NULL CHECK (status IN ('active', 'open', 'promoted')),
    created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (pack_code, code)
);

CREATE INDEX idx_clause_type_code ON clause_type (code);

CREATE TABLE clause (
    clause_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    clause_type_id      INTEGER REFERENCES clause_type (clause_type_id),
    heading             TEXT,
    verbatim_text       TEXT NOT NULL,
    page_start          INTEGER,
    page_end            INTEGER,
    status              TEXT NOT NULL CHECK (status IN ('bound', 'open')),
    novelty             TEXT NOT NULL CHECK (novelty IN ('known', 'new')),
    summary             TEXT,
    importance          TEXT CHECK (importance IS NULL OR importance IN ('high', 'medium', 'low')),
    extraction_method   TEXT,  -- 'alias' (deterministic) | 'llm_open' (model read/proposal)
    provenance_id       INTEGER REFERENCES provenance_doc (provenance_id),
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_clause_version ON clause (document_version_id);

CREATE TABLE clause_slot (
    clause_slot_id INTEGER PRIMARY KEY AUTOINCREMENT,
    clause_id      INTEGER NOT NULL REFERENCES clause (clause_id) ON DELETE CASCADE,
    slot_key       TEXT NOT NULL,
    value_text     TEXT NOT NULL,
    value_number   REAL,
    unit           TEXT,
    currency       TEXT,
    scale          TEXT,
    value_date     TEXT,
    UNIQUE (clause_id, slot_key, value_text)
);
