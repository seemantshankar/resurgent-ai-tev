-- Document Intelligence M3-04: mandate sites (spec #133, issue #137).
-- The profile stage identifies the mandate's sites from its documents (PPAs, quotations,
-- DPR, PVsyst, ...). Variant spellings merge into one site via the loose site score, with
-- the LLM seam confirming ambiguous scores; every mention records why it matched, on which
-- document it was seen. A site row is the canonical site; site_alias rows are the spellings
-- seen per document. Fact gains the site it is about; mandate-wide facts (e.g. borrower PAN)
-- keep site_id NULL (additive; entity tagging is a sibling ticket).

CREATE TABLE site (
    site_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id INTEGER NOT NULL,
    site_name  TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_site_mandate ON site (mandate_id);

CREATE TABLE site_alias (
    alias_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    site_id             INTEGER NOT NULL REFERENCES site (site_id),
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    alias_text          TEXT NOT NULL,   -- the spelling as seen in that document
    match_score         REAL,            -- loose score when merged, 1.0 for the first mention
    match_reason        TEXT NOT NULL,   -- why the mention matched (never empty)
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_site_alias_site ON site_alias (site_id);
CREATE INDEX idx_site_alias_version ON site_alias (document_version_id);

ALTER TABLE fact ADD COLUMN site_id INTEGER REFERENCES site (site_id);

CREATE INDEX idx_fact_site ON fact (site_id);
