-- Document Intelligence M3-11: industry checklists, multi-industry and Not applicable
-- (spec #133, issue #144). Checklist definitions stay versioned data beside the
-- workspace (the #143 store); a definition the model first-drafts for a new industry
-- is labelled on the record so the analyst can see why its items exist. checklist_item
-- gains one additive column: the label of the definition the item came from (e.g.
-- 'new industry: first mandate'), null for learned or bespoke definitions. Everything
-- else the ticket needs — industry/country scoping, per-site summaries, the
-- not_applicable status — already exists in V57 (the status CHECK admits it).

ALTER TABLE checklist_item ADD COLUMN note TEXT;
