---
feature: doc-extract-m2
status: implemented
updated: 2026-10-13
branch: feat/doc-extract-m2
commits: ec2f552, 6abdd6d
---

# M2: Document Extract, Classify, Persist, Retrieve

Spec for Epic #128 milestone **M2 — Packs + extract + classification + retrieval**. Architecture baseline is `Project Docs/Document_Intelligence_Architecture.md` (§6.5, §7, §8 pack format, §14–15). Authoring rules in architecture §8.2 / §9.1 Pass C are superseded by epic #128 (auto induction). M1 foundation is landed (PR #130 / issue #129): immutable `document_version`, blobs, `page`/`block`/`span`/`table_*`, `provenance_doc` shell, CLI `tev-doc ingest|layout`.

**Phase boundary (explicit):** the **Excel FM Loader / cell graph is already built and must not be extended or rewritten here.** This phase is **document-side only**:

1. **Ingest** — files → versions, blobs, pages (M1; keep green)
2. **Classify** — document → `doc_type_code` + pack selection
3. **Extract** — schema-guided fields → candidates
4. **Persist (DB ingest)** — `fact` / `fact_evidence` / `provenance_doc` / pack registry
5. **Retrieve** — query facts + evidence for any downstream consumer

**DB topology (architecture D2+D3):** one **`workspace.db` per mandate** already holds the Excel cell graph and M1 document/layout tables. M2 migrations (`V47` facts/packs) go into that **same** file. Binaries stay in `blobs/{sha256}/`. No second document database.

Downstream (discrepancy matrix, report bullets, CMA, pitch decks) **consumes** these rows; it is not implemented in this phase.

**Product context (BRD `TEV Automation BRD.md` v1.2):** supporting docs feed bank TEV work (quotations, net-worth, DPR, sanctions…). Keep industry-generic packs and citable provenance. Analyst-in-the-loop remains a **consumer** rule (report/FM), not a pack-authoring rule.

## Report

## [S1] Problem

Client packs already sit in `workspace.db` as layout graphs (Bhagyanagar: 64 versions, 1046 pages, 11k+ blocks), but nothing classifies them into document types, extracts typed facts, persists citable evidence, or lets other tools retrieve those facts. There is no industry-generic pack layer — so extract cannot run without a human schema, and there is no path from “PPA PDF” to `Commercial > Offtake > Tariff = 4.50` with page + bbox + verbatim.

The Excel side is finished (FM Loader). This phase must not grow Excel logic. It must make **non-Excel documents** queryable at the same quality bar: classify → extract → persist → retrieve, for any industry, without analyst-authored packs.

## [S2] Design

### S2.1 Doc type packs (data, not code)

Packs are the only place industry vocabulary lives. Java core never learns “tariff” or “ADR”.

**Authoring rule (product):** analysts do **not** write or approve packs. Packs are induced automatically (S2.11) and published after quality gates. The three packs below are **seed goldens** for the engine (regression fixtures + bootstrap where they already match). New industries get packs by induction, not by a code release or a human content task.

Seed packs ship as YAML under `src/main/resources/docpacks/`, loadable again from `--pack-dir`. Induced packs live in a pack store beside the workspace (S2.11). No `platform.db` in M2.

| Pack code | Family | File role | Purpose |
|---|---|---|---|
| `identity.promoter_net_worth` | certificate | `certificate` | CA net-worth certificates (person or company) |
| `quotation.vendor` | quotation | `quotation` | Vendor offers / BoQs with line items and totals |
| `agreement.ppa` | agreement | `agreement` | Power purchase agreements / offtake contracts |

Pack shape (architecture §8.1, trimmed to M2 fields):

```yaml
code: agreement.ppa
version: 1.0.0
family: agreement
file_role: agreement
identity_signals:
  any_of: ["Power Purchase Agreement", "PPA"]
fields:
  - key: tariff
    path: Commercial > Offtake > Tariff
    path_root: commercial
    value_kind: money
    required: true
    slots: [currency, scale, unit]
    prompt_hint: "Tariff / PPA rate in ₹ per kWh (or energy unit as stated)"
  - key: tenor
    path: Commercial > Offtake > Tenor
    path_root: commercial
    value_kind: duration
    required: true
  # ...
tables: []          # quotation pack defines row maps here (M2)
checklist: []       # reserved for M3; may list paths already
report_sections: [financial_viability, risk]
```

**Loader contract**

- `PackLoader.load(Path|classpath)` → `DocTypePack` record.
- Validate: unique `code`, semver `version`, every `fields[].path` non-empty, `value_kind` in the closed set from architecture §6.5, `path_root` in the spine roots, `required` boolean, no duplicate `fields[].key`.
- Invalid pack fails the command with a non-zero exit and a line-level message — never a half-loaded pack.
- `DocTypePack.toExtractSchema()` builds the JSON Schema fragment sent to `ExtractClient` (object of field keys; each leaf carries `value_kind`, `required`, and short `prompt_hint`). Multi-value leaves are arrays. Quotation packs add a `line_items` array of row objects.

**Authoring the three packs (initial leaves)**

`identity.promoter_net_worth`

| key | path | value_kind | required |
|---|---|---|---|
| subject_name | identity > Legal Name | text | true |
| as_of_date | economic > Promoter Net Worth > As Of | date | true |
| assets_total | economic > Promoter Net Worth > Assets | money | true |
| liabilities_total | economic > Promoter Net Worth > Liabilities | money | true |
| net_worth | economic > Promoter Net Worth > Net Worth | money | true |
| issuer_name | frame > Signature Block > Name | text | false |
| issuer_membership_no | frame > Signature Block > Membership | text | false |
| currency / scale | (slots on money fields) | — | — |

`net_worth` may be **stated** or **derived** (`assets − liabilities`). Store `extraction_method='derived'` and `fact_evidence.role='derived_from'` pointing at the two parent facts when the certificate does not print a total.

`quotation.vendor`

| key | path | value_kind | required |
|---|---|---|---|
| vendor_name | identity > Legal Name | text | true |
| quote_date | frame > Document Title > Date | date | false |
| validity_date | commercial > Offtake term > Validity | date | false |
| project_name | technical > Location | text | false |
| scope_text | technical > Capacity | text | false |
| line_items | (row facts, economic) | list | true |
| subtotal | economic > Project Cost > Subtotal | money | false |
| tax | economic > Project Cost > Tax | money | false |
| freight | economic > Project Cost > Freight | money | false |
| gross_total | economic > Project Cost > Quoted Gross | money | true |
| payment_terms | commercial > Offtake term > Payment | text | false |

Each `line_items[]` row emits **one fact** (`path_root='economic'`, `ontology_path='economic > Project Cost > Line Item'`) plus `subject_entity` = vendor and a `line_key` in `value_text` when a description column exists (architecture §7.5). Grouping keys (vendor, package, validity, tax/freight flags) stay on the parent facts and `fact_evidence.role`.

`agreement.ppa`

| key | path | value_kind | required |
|---|---|---|---|
| seller_name | commercial > Offtake > Seller | text | true |
| buyer_name | commercial > Offtake > Buyer | text | true |
| tariff | commercial > Offtake > Tariff | money | true |
| tenor | commercial > Offtake > Tenor | duration | true |
| cod | technical > COD | date | false |
| capacity_mw | technical > Capacity | quantity | true |
| governing_law | legal > Governing law | text | false |

`unit` on `tariff` is never invented: if the document does not state ₹/kWh (or equivalent), leave `unit` empty and evidence `resolution='ambiguous'` (architecture §7.4).

### S2.2 Meaning tables (migration)

New migration `V47__doc_facts.sql` (M1 left `provenance_doc` empty on purpose):

- `fact`, `fact_value`, `fact_evidence` per architecture §6.5, with these M2 tightenings:
  - Drop the meaningless `UNIQUE (parse_run_id, document_version_id, fact_id)`. Re-run **replaces** facts for `(parse_run_id)` (delete children then insert) — same discipline as cell interpretation snapshots.
  - `fact.extraction_method` closed set stays `deterministic | llm_field | llm_open | human | derived`.
  - `nomenclature_status` defaults `unbound` at extract; `bind` upgrades to `bound` | `not_applicable`.
  - Do **not** create `clause` / `clause_slot` / checklist tables here if absent — M3 owns them. If a future migration already added them, leave them alone.
- Extend `run_stat.stage` CHECK with `doc-extract` and `doc-bind` (today FM stages only). Extend `llm_usage.stage` the same way so LlamaExtract spend is attributable.
- `parse_run` identity stays the M1 contract (`source_file_id`, `parser_version`, `config_hash`). Extract uses `config_hash = "extract:" + pack.code + "@" + pack.version + ":" + extractorBundleVersion`. Re-extract with the same identity replaces that run’s facts.

### S2.3 ExtractClient SPI + LlamaExtract adapter

```java
public interface ExtractClient {
  String id(); // "llama-extract-2.5-agentic", "fixture-extract"
  ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema);
  // extractOpen / extractLayout reserved for M3/M5 — declare only if needed for compile stability
}

public record ExtractRequest(
    Path pageBundleDir,          // blobs/{sha}/ — original + pages/
    Path originalFile,           // original.pdf preferred for LlamaExtract
    List<Integer> pageIndices,
    String langHint,
    String docTypeCode,
    String instructions,
    Map<String, String> options) {}

public record ExtractResult(
    String extractorId,
    JsonNode payload,            // raw adapter payload, stored for audit
    List<FactCandidate> facts,
    List<Citation> citations,
    ExtractStats stats) {}
```

`FactCandidate` carries: `key` (pack field key), `valueKind`, raw/normalized value slots (`valueText`, `valueNumber`, `valueDate`, `unit`, `currency`, `scale`), `confidence`, `statedOrDerived`, and zero-or-more `Citation` (page index, bbox if any, `verbatim`).

**LlamaExtractAdapter (primary)**

- Config via env only: `LLAMA_CLOUD_API_KEY` (required for live extract), optional `LLAMA_EXTRACT_BASE_URL`, `LLAMA_EXTRACT_MODEL`, `LLAMA_EXTRACT_TIER`.
- Wire format of the HTTP body/response is owned inside the adapter; domain code never sees LlamaCloud types. The adapter:
  1. Submits `originalFile` + compiled pack schema + short instructions (English; include `prompt_hint`s).
  2. Maps returned fields into `FactCandidate`s.
  3. Maps each citation quote into `Citation` with page index; bbox when the service returns geometry, else bbox stays null and `provenance_doc` still stores `verbatim` + `page_id`.
  4. Records the call's spend into `ExtractStats` → `llm_usage` (stage `doc-extract`).
- **Parse tier is never a blanket `agentic`.** The runner resolves the effective tier (the `--tier` override, else the layout `ParseTierSelector` result) and passes it as the `llama_parse_tier` adapter option; the adapter puts exactly that tier in `configuration.tier`. With no option the floor is `LLAMA_EXTRACT_TIER`, else `cost_effective` — a document that reaches the adapter untiered is billed at 3 credits/page, not 10–45.
- When a LlamaParse job backs the extract (`llama_parse_job_id`), the extract call's own spend is `extract_credits` and the parse job's credits are attributed separately (`llama_parse_credits`); without a reused job the extract's total `credits` are recorded, so parse+extract spend is never double-counted.
- Failures: timeout, 4xx, or malformed payload throw `ExtractException`; the extract command marks `parse_run.status='failed'|'partial'`, writes `audit_log`, and does not leave a half-written fact set.
- Cost guard: `extract.max_usd_per_document` and `extract.timeout_ms` honored before/around the call.

**FixtureExtractClient (offline tests)**

- Deterministic map from field key → canned value + quote (provided per test fixture or a small JSON sidecar next to a golden PDF). Used by unit tests and `DocumentExtractTest`. Never selected when `LLAMA_CLOUD_API_KEY` is set unless `--extractor fixture` is explicit.

**Routing (M2 subset of §7.3)**

- Default primary: LlamaExtract.
- `--extractor fixture|llama-extract` on the CLI overrides config.
- `--tier fast|cost_effective|agentic|agentic_plus` overrides the layout-selected parse tier; with no override the `ParseTierSelector` result is used for both the LlamaParse run and the extract call.
- No local OCR path in M2: versions with `ocr_required=1` may still be sent to LlamaExtract (it takes pixels); if the adapter reports empty text and no fields, record `partial` + `review_queue` category `extract_empty` rather than inventing values.

### S2.4 Normalizers (deterministic, after extract)

Shared with the Excel scale rules (architecture §7.4):

- **Money**: parse `₹`, `Rs.`, `INR`, `lakh`/`lac`, `crore`, `million`, `billion` → `scale` + `absolute_amount` using the same multipliers as cell reading (`unit` 1, `thousand` 1e3, `lakh` 1e5, `million` 1e6, `crore` 1e7, `billion` 1e9). Keep `value_text` as the normalized display string and the raw quote in evidence.
- **Dates**: `12/03/2026`, `12-Mar-2026`, `March 2026`, `31.03.2026`, `FY 2025-26` → ISO in `value_date` when precise; month-only → `value_text` with `value_date=null`; never guess day.
- **Duration**: `25 years`, `25 Yr`, `25` + unit label → `value_number` + `unit` in days/years as stated.
- **Percent / rate**: do not invent `unit`. Unstated unit → `unit=null`, evidence `ambiguous`.
- **Names**: keep source script in evidence `source_text`; `fact.value_text` is the display name as returned (no forced transliteration in M2).

Normalizers run in Java on `FactCandidate` before persistence. The LLM is never the authority for `absolute_amount`.

### S2.5 Fact + evidence persistence

Per successful extract of a `document_version` + pack:

1. Ensure `parse_run` (M1 identity + extract `config_hash`).
2. Replace any previous fact set for that `parse_run`.
3. For each normalized `FactCandidate` insert `fact` row with:
   - `parse_run_id`, `document_version_id`, `region_id=null` (regions are M3),
   - `ontology_path` / `path_root` from the pack field (bound later; may pre-fill as `unbound` until `bind`),
   - `subject_entity` when the pack defines one (e.g. vendor, promoter name),
   - `value_kind` and value columns, `extraction_method='llm_field'` or `'derived'`,
   - `extractor_id`, `confidence`, `is_current=1`.
4. Insert `fact_evidence` rows (`role` at least `verbatim_quote`; `table_cell` when the citation matched a `table_cell` by bbox overlap; `derived_from` for derived net worth).
5. Insert `provenance_doc` rows (`entity_type='fact'`, `entity_id`, `document_version_id`, `page_id` resolved from page index, `block_id`/`span_id`/`table_cell_id` when resolvable, bbox when known, `verbatim` exact).
6. Set `document_version.ingest_status='extracted'`.
7. Write `run_stat` (facts_total, facts_bound, fields_missing, fields_ambiguous) and `audit_log` (`extract_completed`).

**Quote resolution rule:** if the adapter returns a quote, try exact match in `span.text_raw` then `block.text_raw` then `table_cell.text_raw` for the cited page; first hit fills ids. Unresolved quotes keep `verbatim` + page and `fact_evidence.resolution='missing'` for geometry — still citable in the UI text sense.

**Multi-value fields** (when a pack leaf is a list): one `fact` per element with the same `ontology_path`, `subject_entity` disambiguating the element (director name, line description). Do not pack JSON into `value_text`.

### S2.6 Bind (pack-path only)

`tev-doc bind` is deterministic in M2 (decision: fields + pack-path bind; clause Pass A/B and checklist stay in M3):

1. Load the pack used at extract.
2. For each `fact` of the run whose `value` field key matches a pack field: set `ontology_path`, `path_root`, `nomenclature_status='bound'`.
3. Derived facts bind to the pack path of their primary leaf (`net_worth` → Promoter Net Worth).
4. Facts with no pack field (stray / extra) keep `nomenclature_status='unbound'` + `unbound_reason='no_pack_field'`.
5. Emit `run_stat` `doc-bind` counts and print a summary (bound / unbound / not_applicable).

No LLM in M2 bind. No soft-leaf invention. Ontology node rows are **not** required to exist before bind — `ontology_path` is a living path string (architecture §9). Optional helper: ensure spine node rows for paths referenced by packs so later checklist/report joins have a stable spine (layer=`spine`, `frozen=1`) — only for paths declared in the three packs.

### S2.7 Table handling (quotations)

1. Prefer layout `table_grid`/`table_cell` when present (M1 already writes a few).
2. Ask LlamaExtract for `line_items[]` (description, qty, unit_rate, amount).
3. Align each line to a `table_cell` by quote substring / bbox overlap when possible; alignment failure is not fatal.
4. Emit one fact per line + parent total facts (`subtotal` / `tax` / `freight` / `gross_total`).
5. Never replace layout geometry with extractor geometry (architecture §7.5).

### S2.8 CLI (document pipeline only)

```bash
# M1 — keep as-is
tev-doc ingest  --mandate <id> --file <path> --logical-doc <name> ...
tev-doc layout  --mandate <id> --version <document_version_id>

# M2
tev-doc classify --mandate <id> --version <document_version_id> [--pack-dir ...] \
  [--industry auto|<tag>] [--force]

tev-doc extract  --mandate <id> --version <document_version_id> --db <workspace.db> \
  [--pack agreement.ppa] [--extractor llama-extract|fixture] [--pages 1-5,8]

tev-doc bind     --mandate <id> --run <parse_run_id> --db <workspace.db> [--pack ...]

tev-doc induce   --mandate <id> --db <workspace.db> [--industry auto|<tag>] \
  [--file-role ...] [--doc-type agreement.ppa] [--versions 1,2,5] [--dry-run]

tev-doc facts    --mandate <id> [--path <ontology path prefix>] [--document <id>] \
  [--kind money|...] [--current] [--json]
```

- picocli under `com.resurgent.tev.doc.cli`, same style as `DocIngestCommand` / `DocLayoutCommand`.
- `classify` writes `document.doc_type_code` + selected `pack_code`/`pack_version` and a confidence + reasons (signals used).
- `extract` prints pack, extractor id, facts written, missing required fields, ambiguous evidence count, and the parse_run id.
- `extract --pages` scopes the run to a 1-based page selection (e.g. `1-5,8`); it is validated against the document page count (out-of-range exits 3) and sent as LlamaExtract `configuration.target_pages`. Citations keep original page numbers, so provenance is unaffected. Omit for all pages.
- `bind` prints bound/unbound counts and the ontology paths touched.
- `facts` prints or JSON-exports retrievable facts **with evidence** (verbatim, page, bbox when known) — this is the retrieval API for all later consumers (discrepancy engine, report bullets, syndication docs).
- Both extract/bind accept `--db`; blob root resolution matches ingest (`--blob-root` or beside the db).
- Exit codes: `0` success (including partial with warnings printed), `3` invalid arguments/pack, `1` runtime failure.

**No Excel/FM verbs in `tev-doc`.** FM Loader keeps its own CLI.

### S2.9 Config keys (env / optional config.json)

| Key | Default | Meaning |
|---|---|---|
| `LLAMA_CLOUD_API_KEY` | unset | enables `llama-extract` |
| `LLAMA_EXTRACT_BASE_URL` | vendor default | override endpoint |
| `LLAMA_EXTRACT_TIER` | `cost_effective` | extract tier floor when no `--tier`/selector result is passed |
| `LLAMA_PARSE_TIER` | `cost_effective` | parse client floor when called without an explicit tier |
| `extract.primary` | `llama-extract` | primary adapter id |
| `extract.max_usd_per_document` | none | abort/flag when exceeded |
| `extract.timeout_ms` | 180000 | per document extract call |
| `extract.timeout_ms` | — | secrets never in `workspace.db` or packs |

### S2.10 Testing boundaries

- Unit: pack loader validation, schema compile, classify scoring, normalizers (money scale table, Indian dates, duration, unstated unit), quote resolver, bind mapping, induction generalization filters, fact query filters.
- Adapter: LlamaExtractClient against a local HTTP stub (request shape + response → FactCandidate mapping). No live LlamaCloud in the default suite; a `@Tag("live-extract")` test may run when `LLAMA_CLOUD_API_KEY` is present (same pattern as `DecisionPoolReplayTest`).
- Golden fixtures in `src/test/resources/docpacks/`: tiny born-digital PDFs (net-worth style certificate, 3-line quotation, short PPA excerpt) plus `*.expected.json` consumed by `FixtureExtractClient`. Add a tiny **non-solar** wording fixture (hotel/manufacturing labels) for induction T13.
- Integration: `ingest → classify → extract → bind` on those PDFs produces facts with `provenance_doc.verbatim`, bound paths for pack fields, and **re-extract replaces** rather than duplicates. `facts` retrieval returns evidence without re-parsing PDFs.
- Existing FM Loader suite must stay green (read-only guard — **no FM feature work in this phase**).

### S2.11 Industry pack induction (automatic, industry-generic)

**Product rule:** no analyst authoring or approval. The platform induces packs from sample documents; quality comes from generalization + automated gates, not from a human sign-off.

```text
ingest+layout corpus
  → cluster by type signals (file_role, title, layout shape, identity_signals)
  → LLM mines each cluster: generic field schema for that doc type
  → generalize (strip company/instance noise)
  → quality gates
  → publish pack@version to pack store
  → (optional) re-extract sample with the new pack and record coverage
```

**Induction prompt contract (LLM):**

> These documents are type *X* in industry *Y*. Author a field schema that would fit **any** company of that type in that industry. Field keys and ontology paths must come from **printed labels and roles**, never from instance values or company names. Prefer the frozen spine paths; invent an industry leaf only when the spine has no home.

**Generalization filters (deterministic, after LLM):**

1. **Label not value** — drop any candidate key/path whose name matches a person/company/amount in the sample set; rewrite to the label token (`Bhagyanagar Solar Net Worth` → `net_worth` / `economic > Promoter Net Worth`).
2. **Cross-document consensus** — a field is required in the pack only if it appears in ≥ N documents of the cluster (default N=2, or 1 when the cluster has a single document but the label matches a spine leaf). One-off company facts never enter the pack (they stay mandate soft leaves at extract).
3. **Spine-first** — map to spine leaves (`identity > Legal Name`, `economic > Project Cost`, `technical > COD`, …). New industry leaves only under known roots with `layer='industry'`.
4. **Nomenclature table** — pack includes `identity_signals` and field `prompt_hint` synonyms drawn from labels across the cluster (e.g. `net worth`, `NW as on`, `परिसंपत्ति`) so a sibling company’s wording still matches.

**Quality gates (auto-publish only if all pass):**

| Gate | Fail behavior |
|---|---|
| Pack schema compiles (`PackLoader` + `toExtractSchema`) | reject pack, log reason, leave type `open` |
| Reload from store is byte-identical | reject |
| Required fields ≥ 1 and each has path + value_kind | reject |
| Dry-run extract on ≥ 1 sample document returns ≥ `min_required_coverage` of required fields (default 0.6) | publish as `optional`-heavy pack (lower `required` flags) or reject if zero fields resolve |
| No field key contains a sample entity name / PAN / CIN | reject |

**Pack store + registry (M2):**

```text
{db-parent}/packs/
  {industry_tag}/{code}@{version}.yaml
  registry.json          # or SQLite table doc_type_pack in workspace.db
```

Registry row: `code`, `version`, `family`, `industry_tag`, `file_hash`, `source_mandates_json`, `induction_run_id`, `created_at`, `status` (`published` | `rejected`). Re-induction of the same `(industry_tag, code)` produces a **new version** (semver bump: major if required fields change, minor otherwise). Old packs stay readable; extract records `pack_code` + `pack_version` on the parse_run config_hash.

**CLI:**

```bash
tev-doc induce --mandate <id> [--industry auto|<tag>] [--file-role ...] \
  [--doc-type agreement.ppa] [--versions 1,2,5,11] [--db workspace.db] [--dry-run]
```

- `--dry-run` prints the proposed pack YAML + gate results without publishing.
- `--industry auto` uses existing industry detection (FM discover already has a detector; reuse or extend).
- `--versions` scopes a (live) run to specific `document_version_id`s, so induction over a large corpus is opt-in and a sample run stays cheap.
- With no `--file-role`/`--doc-type`/`--versions` filter, `induce` targets the **unknown** documents only — the types classification could not place — and induces one pack per cluster. This closes the loop: classify stays `unknown` → induce mints the pack.
- Induction may span one mandate or a directory of already-ingested versions; multi-mandate induction (stronger consensus) is M6 but the API is the same.

**What induction does *not* do:**

- Does not invent values, only schema + hints.
- Does not create per-company fields.
- Does not require a human approve button. Rejected packs are logged and the corpus still extracts with open/unbound candidates until a later induction succeeds.

**Seed packs vs induced packs:** seed YAML in `docpacks/` is loaded first (bootstrap). If induction runs on the same industry and produces a compatible pack, the induced version is used for extract (`--pack` / auto-select by `doc_type_code` + industry tag). Seeds remain test fixtures even when superseded in a workspace.

### S2.12 Document classification

Classification is a **document-side** step between layout and extract (architecture `classify` stage, M2 subset). Goal: attach `doc_type_code` + pack selection so extract knows which schema to use.

**Inputs:** `file_role` (M1), file name, first-page / heading text from layout blocks, pack `identity_signals`, optional `--industry` / industry detector, pack store + seed packs.

**Scoring (deterministic first, LLM only if ambiguous):**

1. Filter packs by `file_role` / family match.
2. Score `identity_signals` (any_of phrases) against page-1 text_norm + title blocks.
3. If one pack wins by margin → `doc_type_code = pack.code`, `status='classified'`.
4. If tie / low score → one LLM classify call (labels + signals, not full body) → still record `confidence` + `reasons` in `document.raw_metadata` or a small `doc_classification` side table if needed.
5. If nothing matches → `doc_type_code='unknown'` (open path; induce later). **Never invent a pack.**

**Writes:** `document.doc_type_code`, classification confidence, chosen `pack_code` + `pack_version` (for extract default). Re-classify with `--force` overwrites; extract stores the pack identity it actually used on the parse_run `config_hash`.

**Out of this step:** ontology binding, facts, checklist, report language.

#### S2.12a Live LLM wiring (implemented)

The two ambiguous-path LLM steps and the induction miner run through a single document-side
port, `com.resurgent.tev.doc.llm.DocLlmClient` (OpenRouter chat-completions adapter by
default). It is constructed from `OPENROUTER_API_KEY` + a model id
(`DOC_LLM_MODEL`, else `Excel_Enrichment_Model_id`), with an optional `DOC_LLM_BASE_URL`
and `DOC_LLM_TIMEOUT_MS` (default 240000). When unconfigured, the CLI keeps its offline
path (declining tie-break; deterministic heuristic miner), so the suite and a keyless run
never hit the network.

- `LlmClassifyTieBreak` implements `ClassifyTieBreak` and is wired into
  `DocClassifyRunner` only when a model is configured. It sends file role/name, the
  truncated page-1 labels/signals, and the scored candidates **with each pack's
  `identity_signals`** — never the full document. The reply must name a candidate pack or
  decline; an invented code, malformed reply, or call failure all decline, so the document
  stays `unknown` and a pack is never invented. Usage is written to `llm_usage` under a
  `0.1.0-doc-classify` `parse_run`; confidence + reasons stay in `doc_classification`.
- `LlmPackMiningClient` implements `PackMiningClient` and is wired into `DocInduceRunner`
  when a model is configured. It mines the cluster's printed labels into a generic schema
  and **attributes each field's documents deterministically** by matching the field's
  labels against each document's layout text, so the `Generalizer`'s consensus and
  entity-stripping run on real appearances. Induction writes `llm_usage` (stage
  `doc-induce`), `run_stat`, a `doc_type_pack` mirror row, and an `audit_log` entry.

`classify`, `extract`, and `bind` all resolve packs from seed YAML **plus** every pack
published under `{db-parent}/packs`, and `extract` defaults to the version's classified
pack when `--pack` is omitted. A deterministic `FixtureDocLlmClient`-style fake backs the
unit tests, so the default suite has no live calls.

> Known data limit: a scanned document with no parsed blocks (e.g. an image-only PDF) has
> no page-1 signals; its tie-break correctly declines and it stays `unknown`. Extract can
> still run with an explicit `--pack`, and the tier selector treats `ocr_required` as
> `agentic` (cost scales with page count).

### S2.13 Retrieval (DB read API)

Retrieval is first-class in this phase: other tools must **not** scrape PDFs again.

**Contract (`tev-doc facts` and a small Java API `FactQuery`):**

| Filter | Meaning |
|---|---|
| `--mandate` | required scope |
| `--path` | ontology path prefix (`Commercial > Offtake`) |
| `--document` / `--version` | limit to one logical doc or version |
| `--kind` | value_kind filter |
| `--current` | only `is_current=1` |
| `--json` | machine-readable export |

**Each row returned:** `fact_id`, `ontology_path`, `path_root`, `value_*`, `unit`/`currency`/`scale`, `subject_entity`, `document_version_id`, `file_name`, `extraction_method`, `confidence`, plus **evidence list** (`role`, `verbatim`, `page_index`, bbox when known, `provenance_id`).

Sorted by path then confidence desc. This is the stable read surface for Phase-1 discrepancy checks, report bullet compilation, and later syndication documents — those consumers stay outside `tev-doc`.

## [S3] Out of Scope

- **Any Excel FM Loader / cell-graph work** (already delivered; do not extend, wrap for new logic, or rewrite)
- Client-FM discrepancy matrix / Phase-1 checks / quotation-vs-FM reconciliation engines (consumers of `tev-doc facts`, later phase)
- Report section bullets, prose, CMA, Board Notes, pitch decks, syndication strategy (consumers)
- Manual pack authoring / analyst pack approval (explicitly rejected — packs are induced, S2.11)
- Clause table population, open-clause Pass B/C, embeddings (M3) — M3 Pass C must **auto**-promote into packs, not gate on a human
- Checklist engine, coverage report, review-queue for unbound/conflict (M3) — operational `review_queue` rows for extract/induct failures only
- `fact_snapshot` / section staleness / controlled refresh (M4)
- OCR, handwriting, stamps (M5); dense-table extraction upgrades (M5) — the PVsyst **pack** is induced like any other pack
- `platform.db` multi-mandate pack registry, cost routing, offline OCR fallback (M6)
- Cross-FM joins (later consumer work — read via `facts` API, no Excel changes)
- Hard-deleting client source files
- DOCX/XLSX **document** ingest adapters (workbooks stay on FM Loader path)
- Generic RAG chatbot

## Tasks

- [x] T1: Migration `V47__doc_facts.sql` — fact / fact_value / fact_evidence + run_stat/llm_usage stage extension (+ `doc_type_pack` registry if kept in SQLite) — acceptance: applies to a fresh workspace and a migrated FM workspace; no behavior change without extract (covers: S2.2)
- [x] T2: PackLoader + DocTypePack validation + `toExtractSchema()` — acceptance: valid YAML loads; invalid packs fail with actionable errors; schema fragment lists every pack field (covers: S2.1)
- [x] T3: Seed packs `identity.promoter_net_worth`, `quotation.vendor`, `agreement.ppa` under `src/main/resources/docpacks/` as engine goldens — acceptance: all three load; required/optional leaves and paths match S2.1 tables; used by tests, not as the only authoring path (covers: S2.1)
- [x] T4: ExtractClient SPI + records + FixtureExtractClient — acceptance: fixture returns deterministic FactCandidates with quotes for the golden PDFs (covers: S2.3)
- [x] T5: LlamaExtractAdapter behind the SPI with env config, timeout, and audit payload retention — acceptance: HTTP-stub test proves request/response mapping; missing key selects fixture or fails clearly when llama is forced (covers: S2.3)
- [x] T6: Deterministic normalizers (money/date/duration/percent) shared scale table with Excel rules — acceptance: unit tests cover lakh/crore, `31.03.2026`, `25 years`, and unstated-unit rate (covers: S2.4)
- [x] T7: Extract service — parse_run identity, fact/fact_evidence/provenance_doc writes, quote resolution, derived net-worth path, run replace on re-extract — acceptance: integration on golden PDFs writes citable facts and re-extract does not duplicate (covers: S2.2; S2.5; S2.7; depends: T1, T2, T4, T6)
- [x] T8: Bind service (pack-path only) + spine ensure for pack paths — acceptance: bind marks pack fields bound with ontology_path/path_root; extras stay unbound with reason (covers: S2.6; depends: T3, T7)
- [x] T9: CLI `tev-doc extract` and `tev-doc bind` — acceptance: commands run end-to-end on golden fixtures, print summaries, write run_stat/audit_log/llm_usage; existing FM Loader tests remain green (no FM changes) (covers: S2.8; S2.9; depends: T5, T7, T8)
- [x] T10: Golden integration suite `DocumentExtractBindTest` — acceptance: ingest→classify→extract→bind on three synthetic documents yields required fields bound for each pack, with verbatim provenance (covers: S2.10; depends: T9)
- [x] T11: Pack induction service — type clustering, LLM field mining, generalization filters (label-not-value, consensus, spine-first) — acceptance: unit tests strip company/PAN-like tokens and keep industry-generic fields; single-doc cluster can still map spine labels (covers: S2.11)
- [x] T12: Pack store + registry + `tev-doc induce` CLI with quality gates and `--dry-run` — acceptance: induce publishes a versioned YAML pack only when all gates pass; failed gates leave the corpus extractable and log why; re-induction bumps version (covers: S2.11; depends: T2, T11)
- [x] T13: Induced-pack integration — induce on a non-solar synthetic cluster (e.g. hotel folio / manufacturing quotation wording) then extract+bind without hand-written pack — acceptance: no analyst step; published pack fields bind; required coverage gate measured (covers: S2.11; S2.10; depends: T12, T9)
- [x] T14: `tev-doc classify` — file_role + identity_signals + tie-break LLM; writes `doc_type_code` + pack selection + reasons — acceptance: golden docs classify to the right pack; unknown type stays `unknown` and extract can still run open; `--force` re-classifies (covers: S2.12; depends: T2, T3)
- [x] T15: Retrieval — `tev-doc facts` + `FactQuery` API with filters and evidence join — acceptance: JSON export lists value + verbatim + page/bbox for golden extracts; path/document/kind filters work; no PDF re-parse on read (covers: S2.13; depends: T7)
