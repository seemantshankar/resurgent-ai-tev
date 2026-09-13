-- Controlled vocabulary for LLM Packet classification (#105).
-- Spine / industry packs / mandate overlay live here; Candidate geometry is unchanged.

CREATE TABLE nomenclature_node (
    node_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    name TEXT NOT NULL,
    parent_path TEXT,
    layer TEXT NOT NULL CHECK (layer IN ('spine', 'industry', 'mandate_soft')),
    frozen INTEGER NOT NULL CHECK (frozen IN (0, 1)),
    leaf INTEGER NOT NULL CHECK (leaf IN (0, 1)),
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_nomenclature_node_spine_path
    ON nomenclature_node (path)
    WHERE layer = 'spine';

CREATE UNIQUE INDEX idx_nomenclature_node_industry_path
    ON nomenclature_node (industry_tag, path)
    WHERE layer = 'industry';

CREATE UNIQUE INDEX idx_nomenclature_node_mandate_path
    ON nomenclature_node (mandate_id, path)
    WHERE layer = 'mandate_soft';

CREATE INDEX idx_nomenclature_node_parent ON nomenclature_node (parent_path);
CREATE INDEX idx_nomenclature_node_mandate ON nomenclature_node (mandate_id);

CREATE TABLE nomenclature_alias (
    alias_id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias_text TEXT NOT NULL,
    leaf_path TEXT NOT NULL,
    layer TEXT NOT NULL CHECK (layer IN ('spine', 'industry', 'mandate_soft')),
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_nomenclature_alias_spine_text
    ON nomenclature_alias (alias_text)
    WHERE layer = 'spine';

CREATE UNIQUE INDEX idx_nomenclature_alias_industry_text
    ON nomenclature_alias (industry_tag, alias_text)
    WHERE layer = 'industry';

CREATE UNIQUE INDEX idx_nomenclature_alias_mandate_text
    ON nomenclature_alias (mandate_id, alias_text)
    WHERE layer = 'mandate_soft';

CREATE INDEX idx_nomenclature_alias_leaf ON nomenclature_alias (leaf_path);

CREATE TABLE mandate_industry (
    mandate_id INTEGER PRIMARY KEY,
    industry_tag TEXT NOT NULL,
    confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1)),
    inferred INTEGER NOT NULL CHECK (inferred IN (0, 1)),
    updated_at TEXT NOT NULL
);
