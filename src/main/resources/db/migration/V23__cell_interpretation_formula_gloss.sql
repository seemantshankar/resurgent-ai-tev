-- Formula gloss prose for Cell interpretations (#119).
-- Separate from formula facts and deterministic dependency annotations.

ALTER TABLE cell_interpretation ADD COLUMN formula_gloss TEXT;
