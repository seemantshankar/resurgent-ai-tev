# Close schedule family and refuse transcribed soft leaves

A Mercury/Gemini A/B on the same 220 Om Arham candidates showed two defects that
a model swap will not fix. Both models minted the same supplier lines, tax rates,
and quantities as soft leaves, and Gemini invented `depreciation` /
`depreciation_schedule` as schedule families because Layer A allowed any
snake_case name. The validator stayed GREEN because it only checked grounding,
not whether a leaf was a category.

**Decision.**

1. **`schedule_family` is a closed set of seven values**: `capex_detail`,
   `means_of_finance`, `profit_and_loss`, `balance_sheet`, `cash_flow`,
   `assumptions`, `project_summary`. The Layer A JSON schema enumerates them;
   the parser returns null for any other name and the existing retry asks again.
   Depreciation is not an eighth family. The depreciation expense is a line on
   the profit and loss; the sheet that calculates it asset by asset is a working
   paper. The pipeline does not rename `depreciation_schedule` onto one of the
   seven — the model must choose.

2. **A soft leaf is a category, never the row.** When materialising a Layer B
   binding, the proposed leaf segment is resolved against the ontology aliases
   alongside the row label. A unique hard catalogue hit replaces the path (so
   `Elevator (Supplier - Kone…)` under Civil Works binds to Plant & Machinery >
   Elevator / Lift). Otherwise a leaf that matches a transcribed shape —
   formula error, supplier, tax rate, quantity, specification, or Less:/Add:
   qualifier — is refused as `transcribed_label` and is never staged into the
   mandate overlay. Mid-level recovery that used to invent a leaf from the row
   text is removed: the model must name a category via `soft[]`, or the amount
   stays unbound.

3. **Short category names may still mint.** `Closing Stock` under Profit & Loss
   is allowed when it matches no hard leaf and matches no transcribed shape.
   Opening Stock / Closing Stock / Dividend(Drawings) are not added to the
   frozen spine by this decision.

4. **The harness treats transcribed soft leaves as RED.**
   `scripts/validate-classify-run.py` fails the run when any soft leaf looks like
   a raw line item. Outside-catalogue soft categories that are real categories
   remain INFO.

**Consequences.** Coverage of bound cells will drop for rows whose only name was
a transcribed leaf; those cells carry `transcribed_label` until a real category
is known. Schedule-family agreement across models becomes measurable because
both are forced onto the same seven names. A second A/B after this change is the
right basis for choosing a model, because what remains is spine targeting and
relevance.
