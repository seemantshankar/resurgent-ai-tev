# Formula graph is ingest structure, not sheet meaning

The co-pilot needs two facts a reference edge does not answer: what a formula is calculating from, and which formulas a cell reaches. Ingest materializes that graph. Each formula links to the persisted cells its references read, ranges included, and the transitive closure is stored so a later reader does not walk the workbook. Dependents are that closure read in reverse. Reference edges stay the unexpanded tokens they already are.

This is not one-hop SUM membership, and it does not assign amount roles or nomenclature. A blank coordinate still creates no cell. A reference that leaves the workbook, or a defined name that is not a plain cell or range, is a gap on the formula. A cycle is recorded and the walk stops. Sheet meaning stays with classify (ADR 0009).

Building the closure only when something asks was the alternative. Ingest is where every edge already exists, and the closure is the fact a later reader will ask for every time.
