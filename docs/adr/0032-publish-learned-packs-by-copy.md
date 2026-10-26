# Publish learned packs by copy, and let each mandate stand alone

A later mandate should start from packs and checklists already learned, without putting every mandate's facts in one database. Architecture D3 already chose one workspace per mandate plus an optional registry. The open choice was how learning actually travels: a shared database, a worker, or a published copy.

**Status.** Accepted.

**Decision.**

The platform registry records mandates and published packs and checklists. A mandate row is its id, workspace path, industry, and status. A published row is the code, version, and file hash, with the industry or the country. The registry holds no client names, facts, soft leaves, aliases, or open clause types. Those stay in that mandate's workspace.

A command uses the registry only when it is pointed at one. It does not search parent folders, and it does not create a registry on its own. A single mandate still runs from its own files.

A successful induction publishes one version, including the first mandate of a new industry. Several mandates are judged once, by the existing quality gates, on the combined evidence. A pass copies the new files into each named workspace and, when a registry is named, onto that registry. A failure publishes nothing, and each mandate keeps the version it has. There is one published line per industry and document type, and one per country and industry for a checklist. The country spine ships with the product.

The mandate uses the highest version it can see, local or published, including a seed. A newer local file is never replaced by an older published one, and the same version is never rewritten. The first use copies the file into the mandate folder. That folder can be reopened without the registry.

A published version is never deleted. Marking it withdrawn stops later copies. A mandate that already has the file keeps it, and a new mandate receives the next-highest version that is still published.

There is no multi-mandate worker. The operator runs the commands.

**Considered options.**

- One database for every mandate. Rejected: a mandate could not be backed up or handed over on its own, and one writer's lock would stall the others. This is the rejection already in D3.
- A job queue and a worker serving many mandates. Rejected: nothing in this milestone waits on a long-running process. The operator already runs one command across the workspaces they name.
- Treating two workspaces as shared because their files sit in the same directory. Rejected: that coupling is an accident of layout, and a later mandate would not see a pack that lives beside a different parent.

**Consequences.**

- The mandate folder stays the unit of backup and handover. The registry is an index of what may be copied, not a runtime dependency of a mandate that already has the files.
- Two mandates can hold different versions until the next publish. That divergence is deliberate.
- Withdrawing a version does not repair a mandate that already copied it. A new industry needs no code change and no new seed file.
