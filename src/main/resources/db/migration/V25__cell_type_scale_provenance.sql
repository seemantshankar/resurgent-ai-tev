-- The scale a cell is typed at is not always a claim. A cue may name it (a formula
-- divisor, a "Lacs" header, a currency mark), or an additive consumer may supply it,
-- or nothing may supply it at all — in which case the stored unit scale is a carrier
-- default rather than an assertion. Recording which of those it was is the explicit
-- marker that keeps a binding from reporting the default as certain.
--
-- 'stated' is the default for rows written before this column existed: those runs
-- predate adoption and provenance, and every one of them is rewritten on the next
-- classify for the parse run.
ALTER TABLE cell_type ADD COLUMN scale_provenance TEXT NOT NULL DEFAULT 'stated';
