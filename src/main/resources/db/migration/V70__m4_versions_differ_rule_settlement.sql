-- Document Intelligence M4-06: Transition versions-differ rows to Tier 1 Rule settlement (ADR 0028 §5, issue #168).
-- Backfills existing versions-differ rows in conflict with settlement = 'rule', chosen_side = 'left',
-- rule_id = 'version_precedence_v1', rules_version = '1.0'.
-- Also ensures right-side losing facts have is_current = 0 and superseded_by_fact_id = left_fact_id.

UPDATE conflict
SET settlement = 'rule',
    chosen_side = 'left',
    rule_id = 'version_precedence_v1',
    rules_version = '1.0',
    reasoning = 'same document type on different document dates: newer version supersedes older version under version precedence ladder'
WHERE kind = 'versions-differ' AND settlement IS NULL;

UPDATE fact
SET is_current = 0,
    superseded_by_fact_id = (
        SELECT c.left_fact_id FROM conflict c
        WHERE c.right_fact_id = fact.fact_id AND c.kind = 'versions-differ'
        ORDER BY c.conflict_id DESC LIMIT 1
    )
WHERE fact_id IN (
    SELECT c.right_fact_id FROM conflict c
    WHERE c.kind = 'versions-differ' AND c.left_fact_id IS NOT NULL
);
