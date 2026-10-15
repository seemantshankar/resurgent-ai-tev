-- Document Intelligence M3-03: mandate entities (spec #133, issue #136).
-- entity / entity_alias: the borrower, its promoters and its group companies, identified by
-- the assess_profile stage from MOA/AOA, DPR, mandate letter and similar documents. Spelling
-- variants ("ABC Pvt Ltd" / "ABC Private Limited") are ONE entity; every identification and
-- alias keeps its page provenance via provenance_doc with entity_type 'entity'.
-- fact gains the entity reference plus entity_reason — the recorded reason a fact stays
-- untagged when its entity cannot be determined (never guessed). M2's fact.subject_entity
-- free text stays as the source wording.

CREATE TABLE entity (
    entity_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id INTEGER NOT NULL,
    role       TEXT NOT NULL CHECK (role IN ('borrower', 'promoter', 'group_company')),
    name       TEXT NOT NULL,
    reason     TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (mandate_id, name)
);

CREATE INDEX idx_entity_mandate ON entity (mandate_id);

CREATE TABLE entity_alias (
    entity_alias_id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id       INTEGER NOT NULL REFERENCES entity (entity_id),
    alias           TEXT NOT NULL,
    provenance_id   INTEGER REFERENCES provenance_doc (provenance_id),
    UNIQUE (entity_id, alias)
);

CREATE INDEX idx_entity_alias_entity ON entity_alias (entity_id);

ALTER TABLE fact ADD COLUMN entity_id INTEGER REFERENCES entity (entity_id);
ALTER TABLE fact ADD COLUMN entity_reason TEXT;
CREATE INDEX idx_fact_entity ON fact (entity_id);
