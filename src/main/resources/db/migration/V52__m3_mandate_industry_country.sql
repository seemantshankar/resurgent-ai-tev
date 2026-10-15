-- Document Intelligence M3-05: mandate industries and country (spec #133, issue #138).
-- The profile stage records the mandate's industries — M2's per-document industry detection
-- (the packs' industry_tags) aggregated to the mandate, several allowed — and its country.
-- Each row carries the reason it was accepted and a provenance anchor into the document
-- page it was read from. An industry the installed packs have not seen is kept exactly as
-- the documents describe it and marked is_new_industry instead of being forced into the
-- nearest known one. Re-running the assessment replaces these rows (assessment history
-- is M4). provenance_doc.entity_type is free TEXT; this stage writes 'mandate_industry'
-- and 'mandate_country'.

CREATE TABLE mandate_industry_record (
    industry_record_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id           INTEGER NOT NULL,
    industry             TEXT NOT NULL,          -- as recorded, e.g. 'solar power', 'green hydrogen'
    industry_key         TEXT NOT NULL,          -- normalised key, e.g. 'solar power'
    is_new_industry      INTEGER NOT NULL DEFAULT 0
                         CHECK (is_new_industry IN (0, 1)),
    reason               TEXT NOT NULL,
    document_version_id  INTEGER REFERENCES document_version (document_version_id),
    provenance_id        INTEGER REFERENCES provenance_doc (provenance_id),
    created_at           TEXT NOT NULL,
    UNIQUE (mandate_id, industry_key)
);

CREATE INDEX idx_mandate_industry_record_mandate ON mandate_industry_record (mandate_id);

CREATE TABLE mandate_country_record (
    country_record_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id           INTEGER NOT NULL UNIQUE,
    country              TEXT NOT NULL,          -- e.g. 'India'
    country_key          TEXT NOT NULL,          -- normalised key, e.g. 'india'
    reason               TEXT NOT NULL,
    document_version_id  INTEGER REFERENCES document_version (document_version_id),
    provenance_id        INTEGER REFERENCES provenance_doc (provenance_id),
    created_at           TEXT NOT NULL
);
