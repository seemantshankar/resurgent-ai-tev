-- Document Intelligence M3-12: conflicts between documents (spec #133, issue #145,
-- ADR 0026). The assessment compares the same data point across documents within the
-- same entity, site and period and records each disagreement as one conflict row:
-- both sides' sources, pages, values, quotes and fact ids (nothing is ever
-- overwritten — the losing value stays on the row and in the fact table) plus its
-- settlement:
--   'rule' — settled by the versioned source-precedence rule set (rule_id recorded),
--   'ai'   — settled by AI judgement with written reasoning (no action required),
--   'open' — unsettled; the checklist item shows Conflicting and is flagged.
-- kind 'versions-differ': the same data point of the same document type on two
-- different document dates — a revision, not a conflict (settlement NULL; M4 picks
-- the winner, newest first). A re-run replaces the mandate's conflict rows.

CREATE TABLE conflict (
    conflict_id              INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id               INTEGER NOT NULL,
    assessment_run_id        INTEGER REFERENCES assessment_run (assessment_run_id),
    kind                     TEXT NOT NULL CHECK (kind IN (
                                 'doc-doc', 'intra-doc', 'doc-fm', 'versions-differ')),
    data_point               TEXT NOT NULL CHECK (length(trim(data_point)) > 0),
    field_key                TEXT,
    ontology_path            TEXT,
    entity_id                INTEGER REFERENCES entity (entity_id) ON DELETE SET NULL,
    site_id                  INTEGER REFERENCES site (site_id) ON DELETE SET NULL,
    period                   TEXT,
    verdict                  TEXT NOT NULL CHECK (length(trim(verdict)) > 0),
    left_fact_id             INTEGER,
    left_document_version_id INTEGER REFERENCES document_version (document_version_id),
    left_page_index          INTEGER,
    left_value               TEXT NOT NULL CHECK (length(trim(left_value)) > 0),
    left_quote               TEXT,
    right_fact_id            INTEGER,
    right_document_version_id INTEGER REFERENCES document_version (document_version_id),
    right_page_index         INTEGER,
    right_value              TEXT NOT NULL CHECK (length(trim(right_value)) > 0),
    right_quote              TEXT,
    settlement               TEXT CHECK (settlement IS NULL OR settlement IN ('rule', 'ai', 'open')),
    chosen_side              TEXT CHECK (chosen_side IS NULL OR chosen_side IN ('left', 'right')),
    rule_id                  TEXT,
    rules_version            TEXT,
    reasoning                TEXT NOT NULL CHECK (length(trim(reasoning)) > 0),
    created_at               TEXT NOT NULL,
    -- Every conflict carries a settlement; versions-differ rows carry none (M4 decides).
    CHECK (kind = 'versions-differ' OR settlement IS NOT NULL),
    -- A chosen side exists exactly when a value was chosen (rule or AI tiers).
    CHECK ((settlement IN ('rule', 'ai')) = (chosen_side IS NOT NULL)),
    -- Rule settlements record which versioned rule decided, and its version.
    CHECK ((settlement = 'rule') = (rule_id IS NOT NULL)),
    CHECK (rule_id IS NULL OR rules_version IS NOT NULL)
);

CREATE INDEX idx_conflict_mandate ON conflict (mandate_id);
CREATE INDEX idx_conflict_mandate_kind ON conflict (mandate_id, kind);
CREATE INDEX idx_conflict_mandate_data_point ON conflict (mandate_id, data_point);
