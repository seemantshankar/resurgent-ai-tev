# Enrichment input v2: grid view, cell index, and island hints

Generic chat UIs that accept `.xlsx` uploads often convert the workbook to a markdown text dump. Row and column addresses are stripped, so models **estimate** bounding boxes from line counts and hallucinate cell IDs. TEV enrichment must never rely on that path.

We already send every filled cell as `address<TAB>value` from POI (real coordinates). That fixes the Qwen-style failure but still forces the model to reconstruct a 2D sheet from a flat list — hard on tabs with thousands of cells.

**Decision:** enrichment prompt v2 (`enrichment-v2` / `enrichment-v2-regions-only`) adds three deterministic layers from Java before the LLM call:

1. **Sparse grid view** — rows that contain at least one filled cell, rendered with column letters and pipe separators so adjacency matches the Excel UI. Each value is prefixed with its coordinate (`B13:516.15`).
2. **Cell index (NDJSON)** — one JSON object per filled cell: `coord`, `row`, `col`, `kind` (`amount` | `formula` | `text`), `display`, optional `formula`, optional `mergedRange`. The model must cite only addresses present in this index.
3. **Island hints** — 8-connected components over filled cells, then merged for L-shaped tables (column-header strip above a body with an empty corner, left row labels beside amounts, one blank row of vertical spacing). Each hint has exact `bounds` and `cellCount`. The model classifies and may merge/split further; it must not invent coordinates outside the index. Prompt v2.1 requires header/title rows inside the table box (no standalone year-header regions).

**Ownership split (unchanged from ADR 0009):** Java owns geometry; the external model owns meaning (purpose, type, display name, labels). Island hints are disposable input hints, not ingest-time region detection and not official workbook truth.

Flat TSV (`enrichment-v1`) remains in code for tests but is no longer the default prompt version.

**Validation:** existing partition QA (overlap, unassigned, scratch referenced by required) stays. v2 adds no new problem codes in this slice; chunking for tabs whose sparse grid exceeds a row cap is deferred.

**Deferred:** multimodal screenshots, automatic row-window chunking, DB write-back.

**Refines:** ADR 0010 (enrichment v1 JSON report shape is unchanged; only the model input format changes).
