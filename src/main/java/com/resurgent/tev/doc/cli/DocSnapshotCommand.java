package com.resurgent.tev.doc.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.FactQueryRunner;
import com.resurgent.tev.doc.db.FactSnapshotRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactLinkWrite;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactSnapshot;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc snapshot}: captures an immutable snapshot of facts consumed by an output
 * (TEV sections, CMA tables, discrepancy matrices) to enable stale tracking upon fact supersession.
 */
@Command(name = "snapshot",
        description = "Capture an output fact snapshot for stale tracking")
public final class DocSnapshotCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--type", required = true, description = "Output type (e.g. tev_summary, capex_schedule)")
    String outputType;

    @Option(names = "--key", required = true, description = "Output key (e.g. section_1, main)")
    String outputKey;

    @Option(names = "--facts", split = ",", description = "Comma-separated list of fact IDs to link (default: all current facts)")
    List<Long> factIds;

    @Option(names = "--json", description = "Machine-readable JSON export")
    boolean json;

    @Spec
    CommandSpec spec;

    @Override
    /**
     * Captures an immutable snapshot of facts consumed by an output.
     */
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            List<FactRow> allRows = FactQueryRunner.query(db, FactQuery.forMandate(mandateId).includingHistory());
            Map<Long, FactRow> rowById = allRows.stream().collect(Collectors.toMap(FactRow::factId, r -> r, (a, b) -> a));

            List<FactLinkWrite> links = new ArrayList<>();
            if (factIds != null && !factIds.isEmpty()) {
                Set<Long> seenIds = new LinkedHashSet<>();
                for (Long id : factIds) {
                    if (id == null) {
                        continue;
                    }
                    if (!seenIds.add(id)) {
                        continue;
                    }
                    FactRow row = rowById.get(id);
                    if (row == null) {
                        throw new IllegalArgumentException("fact ID not found in mandate " + mandateId + ": " + id);
                    }
                    String val = row.valueText() != null ? row.valueText()
                            : row.valueNumber() != null ? String.valueOf(row.valueNumber())
                            : row.valueDate() != null ? row.valueDate()
                            : "";
                    links.add(new FactLinkWrite(row.factId(), val));
                }
            } else {
                for (FactRow row : allRows) {
                    if (row.isCurrent()) {
                        String val = row.valueText() != null ? row.valueText()
                                : row.valueNumber() != null ? String.valueOf(row.valueNumber())
                                : row.valueDate() != null ? row.valueDate()
                                : "";
                        links.add(new FactLinkWrite(row.factId(), val));
                    }
                }
            }

            FactSnapshot snapshot;
            try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
                Connection conn = workspace.connection();
                FactSnapshotRepository repo = new FactSnapshotRepository(conn);
                snapshot = repo.saveSnapshot(mandateId, outputType, outputKey, links);
            }

            if (json) {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode node = mapper.createObjectNode();
                node.put("snapshot_id", snapshot.snapshotId());
                node.put("mandate_id", snapshot.mandateId());
                node.put("output_type", snapshot.outputType());
                node.put("output_key", snapshot.outputKey());
                node.put("facts_count", links.size());
                node.put("is_stale", snapshot.isStale());
                node.put("created_at", snapshot.createdAt());
                out.println(node.toString());
            } else {
                out.printf("Captured snapshot #%d for %s/%s with %d facts%n",
                        snapshot.snapshotId(), snapshot.outputType(), snapshot.outputKey(), links.size());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("snapshot rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("snapshot failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
