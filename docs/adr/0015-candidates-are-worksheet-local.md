# Candidates are worksheet-local; other sheets appear only as Packet context

A Candidate’s members are always cells on one worksheet. Coverage is per worksheet. Packets may append cells from another worksheet as context cells when a persisted reference edge supports the link; those cells stay members of their own sheet’s Candidates. Cross-sheet similarity relationships are deferred. Cross-sheet relationships may still be recorded when supported by formula-reference edges (related Candidates and/or Packet context).
