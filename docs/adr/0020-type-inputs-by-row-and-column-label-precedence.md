# Type inputs by row and column label precedence

ADR 0019 types hardcoded inputs from their own row labels and bans "header wording
as column kind" as part of the abandoned presentation heuristic stack. That reading
holds for a vertical schedule, where one row is one line item. It fails for a data
block that is a matrix: the room-category block on `SALESPROJECTION` has entity rows
(`Deluxe Rooms`, `Executive Suite Rooms`, `Presidential Suite`) crossed with
attribute columns (`ROOMS FOR SALE`, `NO.OF GUEST PER ROOM PER NIGHT`,
`AVERAGE TARIFF (in Rs.)`, `TOTAL ROOM SALES(Rs. In Lacs)`).

Read from the row label alone, `Deluxe Rooms` is typed `quantity` because it contains
the noun "Rooms", so the ₹5,000 tariff in that row becomes a quantity while the
identical tariff on `Presidential Suite` — a row without the noun — becomes money.
One word of prose splits the block. The resulting `SUM(I12:I24)` mixes quantity and
money, refuses with `KIND_CONFLICT`, and the refusal cascades through
`SALESPROJECTION!E74` into the P&L: a product of a percent and an unresolved money
operand was typed `percent` from the percent factor alone and frozen, so
`P  L!D23`, `J23`, `J33` and `I25` bound as `percent` instead of `money`.

**Decision.**

1. A row label is read first. When it states its own unit — a percentage, a rate, a
   currency, a counted unit — that unit wins, and the column is not consulted. A row
   that names `Percentage`, `in Rs.`, `Tariff` or an explicit `No. of` / `Nos.`
   marker is authoritative for its own numbers.
2. Only when the row is a pure entity name (`Deluxe Rooms`) does the column header
   supply the missing dimension. `Deluxe Rooms` over `AVERAGE TARIFF (in Rs.)` is
   money; over `ROOMS FOR SALE` it is a quantity.
3. The column header is the **Candidate-scoped resolved header** from
   `InterpretationEvidenceResolver`, the same deterministic chain the reporting
   evidence uses. It is resolved only for entity rows, and it fails closed: a missing
   or ambiguous header contributes no cue. A naive nearest-label scan is not used,
   because a note (`as per Quotation included Below`) or a distant schedule's header
   would otherwise be mistaken for this block's header.
4. A period header (`Year 7`, `FY 2025-26`) states no unit and contributes no cue.
5. Percent is stated, never implied by a word. Bare `GST` / `interest` inside an
   entity name ("BPL LED … (Including GST)") describe the item, not a percentage; a
   percent rate carries `%` or the word "percent".

**Entity name vs unit token.** A row label may claim a counted unit only through an
explicit marker (`No. of`, `Number of`, `Nos.`, `Qty`, a measured `Area`/`Capacity`).
A bare noun (`Rooms`, `Keys`, `Beds`) names the entity, not the dimension. A column
header may claim the measured noun directly, because the header is what the column
measures.

**Scale follows the same precedence, and a formula states its own.** A constant-only
input such as `8000*300/100000` hardcodes a lakh figure; typing it from labels alone
loses the `/10^5`. Typing reuses the formula-divisor rules already written for the
reporting evidence (`formulaDivisorScale`, ADR 0018 / #117): the divisor scale comes
first, then the row label, then the Candidate-scoped column header. A dimensionless
factor carries no scale, so a percent multiplier never erases the lakh scale of the
money it multiplies. These two together are what let a group of derived money cells
agree on lakh instead of splitting unit/lakh and refusing the whole group.

**Why this is not the abandoned stack.** The ban in ADR 0019 is on optional,
author-specific presentation: bold, borders, blank rows, region geometry, and header
wording as *structure*. This decision reads one header per entity-row input, through
the Candidate scope discovery already produced, with no region inferred and no style
honoured. It is the narrower form of the option the reporting evidence layer opened.

**A formula is never frozen before its operands settle.** Each formula is re-derived
every pass until the fixpoint, rather than being typed once and never revisited.
Inputs (including constant-only formulas such as `250/2`, which hardcode a number)
are immutable once typed. This is what turns `D23` from a frozen `percent` into
`money` when `SALESPROJECTION!E75` finally resolves, and it is a soundness fix inside
ADR 0019's own "refusal is the default".

**A product is never the product of a subset of its factors.** If a factor or divisor
is still unknown the product stays pending; if it can never be typed the product
refuses (`UNTYPABLE`). Multiplying whatever resolved and discarding the rest is how
a money cell became `percent` — `P  L!I48`, a money reference times a percent driver,
typed `percent` from the percent factor alone when the reference would not type. The
group that reads it then refused, and every member of that group lost its binding.
Refusing the partial product keeps the group typable from its settled members and
keeps a wrong kind off the cell.

**Consequences.** The room-tariff block types each cell from its column; the room
revenue `SUM` resolves money/lakh, the P&L revenue projections `D23`, `J23`, `J33`
and `I25` follow at money/lakh, input type coverage rises from 79.9% to 88.0%, and
graph-proven derived bindings rise from 27 to 82 (offline fake-model run) as the
derived money groups stop splitting on scale. A cell whose upstream genuinely mixes
kinds now refuses instead of being frozen to a subset — `P  L!D46` refuses through
`CAPITAL COST!D20`, an item row labelled "Nos." summed with money, rather than being
asserted money off a partial reading. Refusal stays the default wherever the block
cannot be typed.
