# Enrichment input v2: grid view, cell index, and island hints

Generic chat UIs that accept `.xlsx` uploads often convert the workbook to a markdown text dump. Row and column addresses are stripped, so models **estimate** bounding boxes from line counts and hallucinate cell IDs. TEV enrichment must never rely on that path.

We already send every filled cell as `address<TAB>value` from POI (real coordinates). That fixes the Qwen-style failure but still forces the model to reconstruct a 2D sheet from a flat list — hard on tabs with thousands of cells.

**Decision:** enrichment prompt v2 (`enrichment-v2` / `enrichment-v2-regions-only`) adds three deterministic layers from Java before the LLM call:

1. **Sparse grid view** — rows that contain at least one filled cell, rendered with column letters and pipe separators so adjacency matches the Excel UI. Each value is prefixed with its coordinate (`B13:516.15`).
2. **Cell index (NDJSON)** — one JSON object per filled cell: `coord`, `row`, `col`, `kind` (`amount` | `formula` | `text`), `display`, optional `formula`, optional `mergedRange`. The model must cite only addresses present in this index.
3. **Island hints (not sent to the model as of v2.3)** — Java can still detect 8-connected filled-cell clusters for tests. Prompt v2.1–v2.2 included those bounds in the LLM input; the model copied them as regions instead of reading formulas. v2.3 drops island JSON from the prompt. v2.4: a separated block is Scratch unless a **main/Required table formula references those cells**. An island that only *reads* the main table stays Scratch. Scratch is forbidden when a Required formula references the region.

**Ownership split (unchanged from ADR 0009):** Java owns coordinates and the redacted grid. The external model owns meaning (which cells are one table, purpose, type, display name, labels). Filled-cell clusters are not region proposals and must not be shown to the model as if they were.

Flat TSV (`enrichment-v1`) remains in code for tests but is no longer the default prompt version.

**Validation:** existing partition QA (overlap, unassigned, scratch referenced by required) stays. v2 adds no new problem codes in this slice; chunking for tabs whose sparse grid exceeds a row cap is deferred. After `unassigned_cell`, a repair call (ADR 0010) sends only leftovers and nearby boxes on a cropped grid/index; island hints stay omitted.

**Deferred:** multimodal screenshots, automatic row-window chunking, DB write-back.

**Refines:** ADR 0010 (enrichment v1 JSON report shape is unchanged; only the model input format changes).
