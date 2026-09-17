package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persists the cell graph's evidence — the aggregations and the resolved type of
 * each numeric cell — inside the caller's classify transaction. Does not open or
 * commit its own transaction.
 *
 * <p>The graph is persisted rather than cached because that is what makes a run
 * reproducible and makes "why is this cell bound" answerable afterwards.
 */
final class CellGraphWriter {

    /** Replace the run's graph evidence. Returns each head cell's aggregation id. */
    Map<Long, Long> write(WorkspaceRepository repo, CellGraph graph, CellTypes types)
            throws SQLException {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(types, "types");
        repo.deleteAggregationsForParseRun(graph.parseRunId());
        repo.deleteCellTypesForParseRun(graph.parseRunId());

        Map<Long, Long> aggregationIds = new LinkedHashMap<>();
        for (Aggregation aggregation : graph.aggregations()) {
            ResolvedUnit unit = types.unitOf(aggregation);
            long aggregationId = repo.insertAggregation(new AggregationRow(
                    null,
                    graph.parseRunId(),
                    aggregation.headCellId(),
                    aggregation.worksheetId(),
                    aggregation.relativeSignature(),
                    aggregation.headLabel(),
                    unit.kind(),
                    unit.scale()));
            aggregationIds.put(aggregation.headCellId(), aggregationId);
            List<Aggregation.Member> members = aggregation.members();
            for (int i = 0; i < members.size(); i++) {
                Aggregation.Member member = members.get(i);
                repo.insertAggregationMember(aggregationId, new AggregationMemberRow(
                        i,
                        member.cellId(),
                        member.sign(),
                        member.amountRole(),
                        member.label()));
            }
        }

        for (CellType type : types.rows(graph.parseRunId())) {
            repo.insertCellType(type);
        }
        return Collections.unmodifiableMap(aggregationIds);
    }
}
