# Resolve document version precedence and per-fact supersession

_Extends [ADR 0026](0026-settle-conflicts-in-three-visible-tiers.md)._

In M3, when the same document type appeared with different document dates (such as an original quotation and a revised quotation), the disagreement was recorded in `conflict` with `kind = 'versions-differ'` and `settlement = NULL`, deferring winner selection to M4. In addition, extracted facts in `fact` were always inserted with `is_current = 1` and never linked to their superseding facts.

Real credit appraisal mandates receive documents in waves: original draft agreements followed by executed agreements, amendments modifying specific terms, and updated net-worth certificates. Wiping out unamended terms or blindly choosing a "latest document" loses critical facts, while failing to track what previous reports relied on causes silent inaccuracies when documents are updated.

**Decision.**

1. **Precedence ladder for document versions.** When establishing which document version is newer, the system applies the following strict order of precedence:
   1. Self-declarations stated within the document text (e.g., "Amendment No. 2", "Revised", "supersedes our letter dated…", explicit version number).
   2. Stated document date (execution/signing for agreements, issue date for certificates, report date for DPR/PVsyst, offer date for quotations).
   3. Upload date (`ingested_at`), used strictly as a tie-breaker when stated dates are equal or missing. A later upload never makes a document with an older stated date newer.
   4. File system metadata, treated as weak and marked as such.
   When the signals contradict each other, ADR 0026's visible settlement tiers apply. The exact same file uploaded twice is detected by SHA-256 hash as a duplicate and ignored.

2. **Latest wins per fact, not per document.** An amendment supersedes only the facts it explicitly restates or alters. Unaffected facts from the base agreement or earlier documents remain current (`is_current = 1`). A newer certificate for the same promoter, or a revised quotation from the same vendor for the same site, supersedes the older fact.

3. **Immutable fact rows and direct supersession pointers.** Fact rows are immutable per extraction run. When an existing fact is superseded:
   - The new fact is inserted with `is_current = 1`.
   - The superseded older fact row has its `is_current` updated to `0` and `superseded_by_fact_id` set to the new `fact_id`.
   - Historical facts are never overwritten or deleted. `FactQuery.current()` returns only `is_current = 1` facts, while `FactQuery.includingHistory()` traverses the supersession chain.

4. **Fact identity tuple.** A fact matches an existing fact for supersession evaluation within a mandate by the canonical tuple:
   - Bound facts: `(mandate_id, ontology_path, coalesce(subject_entity, ''), coalesce(site_id, 0), coalesce(period, ''), coalesce(line_key, ''))`.
   - Unbound facts fallback: `(mandate_id, doc_type_code, field_key, coalesce(subject_entity, ''), coalesce(site_id, 0), coalesce(period, ''), coalesce(line_key, ''))`.

5. **Transitioning `versions-differ` to Tier 1 Rule settlement.** In `conflict`, `kind = 'versions-differ'` rows are settled under Tier 1 (`settlement = 'rule'`, `chosen_side = 'left'` for the winning newer side, `rule_id = 'version_precedence_v1'`). The losing right-side fact is marked `is_current = 0` and linked to `superseded_by_fact_id`.

6. **Fact snapshots and stale detection.** Generated outputs (TEV sections, CMA tables, discrepancy matrices) capture an immutable `fact_snapshot` linking the output to the exact `fact_id`s and values read. When a document is added, re-extracted, or superseded, a freshness pass flags any snapshot referencing superseded facts as `is_stale = 1` with a detailed `stale_reason`.

7. **Basis recording and non-money comparisons.** Extraction populates `fact_evidence` with roles `unit`, `currency`, and `scale`. `ConflictScan.scanFm` compares non-money quantity/rate/percent pairs using canonical unit equality with ~1% tolerance, recording skips with reasons whenever units differ (e.g., kWp vs MWp) without attempting implicit unit conversion.
