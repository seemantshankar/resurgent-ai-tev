-- Drop unused region-to-region edge table.
-- RelatedCandidateLinker (formula_reference links between Candidates) was removed
-- when classify was rebuilt; nothing writes or reads this table. Formula edges
-- live on formula_link / cell_reference; hierarchy lives on candidate.parent_candidate_id.
DROP TABLE IF EXISTS candidate_related;
