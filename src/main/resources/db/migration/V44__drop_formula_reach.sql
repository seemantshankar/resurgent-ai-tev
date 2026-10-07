-- formula_reach held every cell paired with every cell it depends on, directly or through other cells: 49 million
-- rows (2.2 GB) for a 19,000-cell workbook, written at ingest and never read by the pipeline. formula_link holds
-- the direct reads, and "what feeds this cell, and how many steps away" is a recursive query over it; the
-- database guide gives it.
DROP INDEX IF EXISTS idx_formula_reach_to;
DROP TABLE IF EXISTS formula_reach;
