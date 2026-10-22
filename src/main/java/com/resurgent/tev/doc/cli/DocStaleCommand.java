package com.resurgent.tev.doc.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.FactSnapshotRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactSnapshot;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc stale}: lists all stale fact snapshots in the mandate with their invalidation causes.
 */
@Command(name = "stale",
        description = "List stale fact snapshots in the mandate")
public final class DocStaleCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--json", description = "Machine-readable JSON export")
    boolean json;

    @Spec
    CommandSpec spec;

    @Override
    /**
     * Queries and reports stale fact snapshots for the mandate.
     */
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            List<FactSnapshot> staleSnapshots;
            try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
                Connection conn = workspace.connection();
                FactSnapshotRepository repo = new FactSnapshotRepository(conn);
                staleSnapshots = repo.findStaleSnapshots(mandateId);
            }

            if (json) {
                ObjectMapper mapper = new ObjectMapper();
                ArrayNode array = mapper.createArrayNode();
                for (FactSnapshot s : staleSnapshots) {
                    ObjectNode node = array.addObject();
                    node.put("snapshot_id", s.snapshotId());
                    node.put("mandate_id", s.mandateId());
                    node.put("output_type", s.outputType());
                    node.put("output_key", s.outputKey());
                    node.put("is_stale", s.isStale());
                    node.put("stale_at", s.staleAt());
                    node.put("stale_reason", s.staleReason());
                    node.put("created_at", s.createdAt());
                }
                out.println(array.toString());
            } else {
                if (staleSnapshots.isEmpty()) {
                    out.println("No stale snapshots found.");
                } else {
                    for (FactSnapshot s : staleSnapshots) {
                        out.printf("Snapshot #%d [%s / %s] stale since %s: %s%n",
                                s.snapshotId(), s.outputType(), s.outputKey(), s.staleAt(), s.staleReason());
                    }
                    out.printf("(%d stale snapshots)%n", staleSnapshots.size());
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("stale rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("stale failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
