# Restore lean-trimmed ingest signals

V14 trimmed the cell contract to coord, typed values, formula text + cache, merges, and hidden flags. That lean cut was temporary. FM Loader again persists, on every successful ingest: shared cell appearance (`cell_style` + `cell.style_id`), `formula_normalized`, and `cell_reference` edges.

Appearance and number formats are read off the cell (Excel paint). Reference edges and formula normalisation are parser judgment (POI + salvage on `FormulaParseException`, per ADR 0003) — still not sheet meaning, but no longer “bytes in, cells in DB only.”

**Cell appearance (flyweight):** distinct paint is stored once in `cell_style`; each cell holds `style_id` (null if unavailable). A style row carries: `is_bold`, `number_format`, fill foreground colour + pattern, and per-side border style + colour (top/right/bottom/left). Prefer `#rrggbb` when POI provides XSSF RGB; else palette index; null if unavailable. `has_fill` / `has_border` are derived from that row, not first-class ingest columns. Out of this contract: font name/size, fill background, italic/underline/font colour unless later amended.

**`formula_normalized`:** canonical formula text for comparison and tokenization; whitespace and case cleaned; quoted literals untouched; no skeletons, no evaluation. Our normalizer, not POI’s stream (ADR 0003).

**Reference edge:** one reference token per formula; ranges stored unexpanded (membership or expansion at query time); a blank target does not invent a cell; salvage yields unresolved edges on parse failure.

**Still out at ingest:** regions, cost-heads, quantity parsing, header labels, formula skeletons, worksheet-role scoring, or any other sheet-meaning interpretation (ADR 0009 / 0012).

Supersedes in part ADR 0009’s V13 database note that dropping `cell_reference` was lasting, and its “on-demand or at ingest” wording for reference edges — edges are part of the ingest contract again. Richer than pre-V14 boolean style columns + `tags`: shared `cell_style` instead of duplicating paint on every cell or stuffing colours into JSON. Does not reopen LLM region geometry or the pre-LLM heuristic stack (ADR 0012).
