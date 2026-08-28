# Send a number-redacted sheet to external models; keep real amounts on the cell graph

Region geometry and per-cell purpose will be proposed by an external model that must see labels and formula text, but must not see client amounts. We **build a redacted sheet in the FM Loader** (dummy numeric literals only), send that view out, and write labels, region membership, roles, and purpose back onto the existing cell rows. Amounts never leave the database and are never reconstructed from the model.

This does not reopen ADR 0004. That ADR rejected a dummy-value twin as a *committed test fixture* because classification needs real labels, and committing those labels would disclose the private workbook. A redacted sheet as *LLM input* is the opposite trade: labels must stay so the model can name regions; numbers must go so financials do not leave the loader. A full dummy twin (labels and numbers) would make the model useless. Round-tripping amounts through the model would create a second, drift-prone copy of the graph we already own.

The model does not write SQLite directly. Analyst review gates model proposals via `review_queue`. See ADR 0009 for what the loader no longer does at ingest time.

**Superseded by ADR 0009:** the prior claim that cost-head totals, scratch/support heuristics, and column-role vocabulary stay in the parser.

## v1 export decisions (2026-08-28)

These decisions apply to the first shippable `tev-parse redact` command. They refine the principle above into concrete behaviour.

### Trigger and output

- **Separate step after ingest** (not auto-redact). Production may auto-redact later once confidence is high.
- **Requires a prior ingest** of the same file into the workspace DB (`--input`, `--db`, `--mandate-id`) so file hash and parse run are guaranteed in sync.
- **Output**: `{output-dir}/{input-basename}-redacted.xlsx`. Use a dedicated `redacted/` subdirectory in production — these files are throwaway LLM input.
- **Scope (v1 test)**: `.xlsx` only; **one tab** named via `--sheet`. All tabs in production later.

### Source file

- Start from the **original client `.xlsx`**, not a rebuild from SQLite. Preserves layout, merges, column widths, colors, and hidden row/column structure so an external model can infer table membership and spatial context.
- The redacted file is **one-way**. Real amounts and future enrichment live on cell rows in SQLite keyed by coordinate. The export is never merged back for amounts.

### What changes vs stays

| Cell content | Action |
|---|---|
| Typed numeric literal (including currency-coerced numbers) | **Redact** to a shape-preserving dummy |
| Formula (`=B5*0.12`, `=SUM(A1:A10)`, `=B5*35%`) | **Keep formula text exactly** (including inline constants like `35%`) |
| Text labels | **Keep** |
| Dates | **Keep** |
| Booleans | **Keep** |
| Error literals (`#DIV/0!`) | **Keep** |
| Text that displays as an amount (e.g. `₹10,00,000`) | **Redact** to a dummy amount; **keep currency/percent/accounting format** |

### Dummy value rules

Use **Excel number format**, not digit-count alone, to preserve semantic shape for the LLM:

- Percent-formatted cells → dummy percent (e.g. `12.34%`)
- Currency / accounting (commas, `₹`, parentheses negatives) → dummy in the same style
- Plain numbers → dummy in a similar magnitude band (small stays small, large stays large-looking)
- Negatives stay negative; percents stay percents

Formula **cached/display values** may recalculate from dummy inputs when the file is opened. That is acceptable: the LLM is told numbers are fake and should lean on **formulas + labels** for meaning.

### Merged and hidden structure

- **Merged cells**: preserve merge geometry; redact only the **anchor** (top-left) numeric literal; participants stay empty as in Excel.
- **Hidden rows/columns on the exported tab**: remain hidden with structure unchanged; numeric values redacted where applicable. Whether the LLM reader includes hidden cells is a separate decision when wiring the LLM step.

### Explicitly deferred

- Auto-redact after every ingest
- All tabs in one command
- Hidden-tab policy
- `.xls` and CSV redaction
- LLM call and per-cell enrichment schema write-back
