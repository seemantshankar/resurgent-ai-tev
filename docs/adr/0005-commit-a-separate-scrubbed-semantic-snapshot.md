# Commit a separate scrubbed semantic snapshot

Sprint 3b adds heuristic schema roles, units, worksheet roles, cost-head mappings, candidate bases, trust states, and review reasons that the Sprint 3a region snapshot does not cover. We decided to commit these as a separate golden semantic snapshot so semantic regressions remain reviewable without turning the region baseline into a mixed artifact.

The semantic snapshot may contain column roles, inferred unit and currency codes, worksheet roles, canonical cost-head codes, candidate bases, trust states, and structured reason codes. It must never contain workbook-derived amounts, labels, raw values, or value-derived candidate fingerprints. Like the region snapshot governed by ADR 0004, it is generated from the private workbook, runs only where that workbook is available, and is complemented by synthetic CI fixtures for every trust branch.

**Consequences**

- Region and semantic regressions produce separate, focused diffs.
- Adding any value-derived field requires revisiting this decision before it can enter git history.
- The private-workbook semantic regression test remains an honest local-only control rather than a partial pseudonymised CI fixture.
