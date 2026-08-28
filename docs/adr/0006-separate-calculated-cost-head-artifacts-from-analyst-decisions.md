# Separate calculated cost-head artifacts from analyst decisions

**Superseded by ADR 0009.** Cost-head rollup and trust artifacts are removed from the loader.

Sprint 3b produces immutable cost-head mappings, contributions, and candidate totals for each parse run, while analyst decisions persist separately against stable source identities. We chose this split so parser or configuration changes never rewrite the historical explanation, mapping acceptance can survive an unchanged source file and region key, and total acceptance carries forward only when the candidate fingerprint proves that the arithmetic is unchanged.

One canonical cost head exists per mandate and locked code, with multiple region- or cell-backed contributions. A candidate becomes trusted only through deterministic high-confidence gates or explicit analyst acceptance; manual corrections are provenance-bearing contributions rather than overwrites. The parse-run-local review queue remains a worklist over these durable records, not the source of truth for analyst decisions.

**Consequences**

- Mapping acceptance and total acceptance have different stable keys and lifecycles.
- Re-parsing creates new calculated artifacts but may reuse compatible durable decisions.
- The schema needs explicit mapping, candidate, contribution/evidence, manual-contribution, and typed decision records rather than a single mutable `cost_head` row.
