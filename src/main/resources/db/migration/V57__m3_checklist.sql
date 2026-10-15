-- Document Intelligence M3-10: spine checklist and statuses (spec #133, issue #143).
-- Checklist DEFINITIONS are versioned data alongside packs; a mandate's workspace
-- records the exact items it was assessed against in checklist_item (definition
-- code + version + scope + both levels + basis), one checklist_result per item
-- and scope (per entity for entity-granularity items) with the status, the
-- recorded reason, the cited page evidence and the ask-the-client wording.
-- checklist_override is the analyst's optional add/remove with a REQUIRED reason;
-- nothing ever requires it and it never blocks an assessment. checklist_summary
-- persists counts by status, overall and per entity. provenance_doc rows for
-- provided results use entity_type 'checklist_result'. A re-run replaces the
-- mandate's checklist wholesale (assessment history is M4).

CREATE TABLE checklist_item (
    checklist_item_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id        INTEGER NOT NULL,
    item_key          TEXT NOT NULL,
    label             TEXT NOT NULL,
    checklist_code    TEXT NOT NULL,      -- definition code, e.g. 'in.spine'
    checklist_version TEXT NOT NULL,      -- definition semver
    scope             TEXT NOT NULL CHECK (scope IN ('spine', 'industry')),
    country           TEXT NOT NULL,
    industry          TEXT,
    level             TEXT NOT NULL CHECK (level IN ('document', 'data_point')),
    granularity       TEXT NOT NULL CHECK (granularity IN ('mandate', 'entity', 'site')),
    entity_role       TEXT CHECK (entity_role IS NULL OR entity_role IN (
                          'borrower', 'promoter', 'group_company')),
    document_type     TEXT NOT NULL,      -- pack code of the expected document
    field_key         TEXT,               -- data point: pack field key
    ontology_path     TEXT,               -- data point: ontology path
    mandatory         INTEGER NOT NULL DEFAULT 1 CHECK (mandatory IN (0, 1)),
    basis_kind        TEXT NOT NULL CHECK (basis_kind IN ('regulation', 'practice', 'observed')),
    basis_text        TEXT NOT NULL CHECK (length(trim(basis_text)) > 0),
    origin            TEXT NOT NULL CHECK (origin IN ('drafted', 'observed', 'promoted')),
    client_request    TEXT NOT NULL,      -- ask-the-client template
    created_at        TEXT NOT NULL,
    UNIQUE (mandate_id, item_key),
    CHECK (level != 'data_point' OR field_key IS NOT NULL OR ontology_path IS NOT NULL)
);

CREATE INDEX idx_checklist_item_mandate ON checklist_item (mandate_id);

CREATE TABLE checklist_result (
    checklist_result_id INTEGER PRIMARY KEY AUTOINCREMENT,
    checklist_item_id   INTEGER NOT NULL REFERENCES checklist_item (checklist_item_id),
    assessment_run_id   INTEGER REFERENCES assessment_run (assessment_run_id),
    entity_id           INTEGER REFERENCES entity (entity_id),
    site_id             INTEGER REFERENCES site (site_id),
    status              TEXT NOT NULL CHECK (status IN (
                            'provided', 'unclear', 'not_provided', 'conflicting', 'not_applicable')),
    reason              TEXT NOT NULL,
    document_version_id INTEGER REFERENCES document_version (document_version_id),
    page_index          INTEGER,          -- cited page; NOT NULL for every provided status
    confidence          REAL,
    client_request      TEXT,             -- filled wording, set when not_provided
    provenance_id       INTEGER REFERENCES provenance_doc (provenance_id),
    checked_at          TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_checklist_result_scope ON checklist_result (
    checklist_item_id, COALESCE(entity_id, -1), COALESCE(site_id, -1));
CREATE INDEX idx_checklist_result_item ON checklist_result (checklist_item_id);

CREATE TABLE checklist_override (
    checklist_override_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id            INTEGER NOT NULL,
    item_key              TEXT NOT NULL,
    action                TEXT NOT NULL CHECK (action IN ('add', 'remove')),
    reason                TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    created_at            TEXT NOT NULL
);

CREATE INDEX idx_checklist_override_mandate ON checklist_override (mandate_id, item_key);

CREATE TABLE checklist_summary (
    checklist_summary_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id           INTEGER NOT NULL,
    assessment_run_id    INTEGER REFERENCES assessment_run (assessment_run_id),
    scope_kind           TEXT NOT NULL CHECK (scope_kind IN ('overall', 'entity', 'site')),
    entity_id            INTEGER REFERENCES entity (entity_id),
    site_id              INTEGER REFERENCES site (site_id),
    status               TEXT NOT NULL CHECK (status IN (
                             'provided', 'unclear', 'not_provided', 'conflicting', 'not_applicable')),
    item_count           INTEGER NOT NULL,
    recorded_at          TEXT NOT NULL
);

CREATE INDEX idx_checklist_summary_mandate ON checklist_summary (mandate_id);
