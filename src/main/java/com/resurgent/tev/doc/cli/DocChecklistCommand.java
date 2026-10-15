package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import com.resurgent.tev.doc.checklist.ChecklistLoader;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.db.ChecklistRepository;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc checklist}: the analyst's optional adjustment of a mandate's
 * checklist (issue #143). An add or remove requires a recorded reason and never
 * blocks anything — the override is read at assessment time, so it takes effect on
 * the next {@code tev-doc assess}. Without an action the command lists the
 * mandate's recorded overrides and the effective items it was last assessed against.
 */
@Command(name = "checklist",
        description = "Add or remove a mandate checklist item (a reason is required),"
                + " or list the effective checklist")
public final class DocChecklistCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--add", description = "Item key to add to this mandate's checklist")
    String add;

    @Option(names = "--remove", description = "Item key to remove from this mandate's checklist")
    String remove;

    @Option(names = "--reason", description = "Why the item is added or removed"
            + " (required with --add / --remove)")
    String reason;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (add != null && remove != null) {
                err.println("checklist rejected: give either --add or --remove, not both");
                return 3;
            }
            String action = add != null ? "add" : remove != null ? "remove" : null;
            if (action != null) {
                String key = "add".equals(action) ? add : remove;
                if (reason == null || reason.isBlank()) {
                    err.println("checklist rejected: --" + action
                            + " requires a --reason naming why the item is adjusted");
                    return 3;
                }
                ChecklistItemDef item = findItem(
                        ChecklistStore.loadCatalog(db).values(), key);
                if (item == null) {
                    err.println("checklist rejected: unknown item key '" + key + "'");
                    return 3;
                }
                try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
                    Connection connection = workspace.connection();
                    new ChecklistRepository(connection)
                            .insertOverride(mandateId, key, action, reason.strip());
                }
                out.printf("checklist %s recorded: %s (%s) — applies to the next assessment%n",
                        action, key, item.label());
                return 0;
            }
            list(out);
            return 0;
        } catch (ChecklistLoader.ChecklistValidationException e) {
            err.println("checklist rejected: " + e.getMessage());
            return 3;
        } catch (IllegalArgumentException e) {
            err.println("checklist rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("checklist failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    /** Lists the mandate's overrides and the items it was last assessed against. */
    private void list(java.io.PrintWriter out) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                Connection connection = workspace.connection()) {
            ChecklistRepository checklist = new ChecklistRepository(connection);
            for (MandateQa.ChecklistOverrideRow override : checklist.overrides(mandateId)) {
                out.printf("override %s %s: %s%n",
                        override.action(), override.itemKey(), override.reason());
            }
            out.printf("overrides: %d%n", checklist.overrides(mandateId).size());
            var items = checklist.items(mandateId);
            for (ChecklistRepository.ItemRow item : items) {
                out.printf("item %s [%s, %s/%s] %s | %s: %s%n",
                        item.itemKey(),
                        item.mandatory() ? "mandatory" : "optional",
                        item.level(),
                        item.granularity(),
                        item.label(),
                        item.basisKind(),
                        item.basisText());
            }
            out.printf("checklist items: %d%s%n", items.size(), items.isEmpty()
                    ? " (not assessed yet — run tev-doc assess)"
                    : "");
        }
    }

    private static ChecklistItemDef findItem(Collection<ChecklistDefinition> catalog, String key) {
        for (ChecklistDefinition definition : catalog) {
            for (ChecklistItemDef item : definition.items()) {
                if (item.key().equals(key)) {
                    return item;
                }
            }
        }
        return null;
    }
}
