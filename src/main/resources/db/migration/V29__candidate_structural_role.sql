-- Structural role on narrow Candidates: main / helper / scratch.
-- Coverage parents keep NULL. Geometry relationship, not Layer A triage.

ALTER TABLE candidate ADD COLUMN structural_role TEXT
    CHECK (structural_role IS NULL OR structural_role IN ('main', 'helper', 'scratch'));
