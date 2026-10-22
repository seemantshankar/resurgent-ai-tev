package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactLinkWrite;
import com.resurgent.tev.doc.db.FactSnapshotRepository.FactSnapshot;
import com.resurgent.tev.doc.db.FactSnapshotRepository.SnapshotFactLink;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactSnapshotPersistenceTest {

    private Path tempDir;
    private WorkspaceDatabase db;
    private FactSnapshotRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fact-snapshot-test");
        Path dbPath = tempDir.resolve("workspace.db");
        db = WorkspaceDatabase.open(dbPath);
        repository = new FactSnapshotRepository(db.connection());
        seedPrerequisites(db.connection());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private void seedPrerequisites(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO source_file (source_file_id, mandate_id, file_name, file_hash, file_type, ingested_at, parser_version)"
                    + " VALUES (1, 10, 'quote.pdf', 'hash1', 'pdf', '2026-10-20T10:00:00Z', 'v1')");
            s.execute("INSERT INTO parse_run (parse_run_id, source_file_id, mandate_id, parser_version, config_hash, started_at, status)"
                    + " VALUES (1, 1, 10, 'v1', 'cfg', '2026-10-20T10:00:00Z', 'success')");
            s.execute("INSERT INTO document (document_id, mandate_id, logical_name, doc_family, created_at, updated_at)"
                    + " VALUES (1, 10, 'Vendor Quote', 'quotation', '2026-10-20T10:00:00Z', '2026-10-20T10:00:00Z')");
            s.execute("INSERT INTO document_version (document_version_id, document_id, version_label, file_name, file_hash, file_ext, file_role, ingested_at)"
                    + " VALUES (1, 1, 'v1', 'quote.pdf', 'hash1', 'pdf', 'quotation', '2026-10-20T10:00:00Z')");

            // Seed two extracted facts
            s.execute("INSERT INTO fact (fact_id, parse_run_id, document_version_id, field_key, value_kind, value_text, value_number, extraction_method, created_at)"
                    + " VALUES (101, 1, 1, 'solar_capacity', 'quantity', '1200 kWp', 1200.0, 'deterministic', '2026-10-20T10:00:00Z')");
            s.execute("INSERT INTO fact (fact_id, parse_run_id, document_version_id, field_key, value_kind, value_text, value_number, extraction_method, created_at)"
                    + " VALUES (102, 1, 1, 'total_price', 'money', '5000000', 5000000.0, 'deterministic', '2026-10-20T10:00:00Z')");
        }
    }

    @Test
    void saveSnapshotAndRetrieveByKey() throws Exception {
        List<FactLinkWrite> links = List.of(
                new FactLinkWrite(101L, "1200 kWp"),
                new FactLinkWrite(102L, "5000000 INR"));

        FactSnapshot created = repository.saveSnapshot(10L, "tev_report_sec3", "capex_summary", links);
        assertThat(created.snapshotId()).isPositive();
        assertThat(created.mandateId()).isEqualTo(10L);
        assertThat(created.outputType()).isEqualTo("tev_report_sec3");
        assertThat(created.outputKey()).isEqualTo("capex_summary");
        assertThat(created.isStale()).isFalse();
        assertThat(created.staleAt()).isNull();
        assertThat(created.staleReason()).isNull();

        Optional<FactSnapshot> fetched = repository.findSnapshot(10L, "tev_report_sec3", "capex_summary");
        assertThat(fetched).isPresent();
        assertThat(fetched.get().snapshotId()).isEqualTo(created.snapshotId());

        List<SnapshotFactLink> factLinks = repository.findLinkedFacts(created.snapshotId());
        assertThat(factLinks).hasSize(2);
        assertThat(factLinks).extracting(SnapshotFactLink::factId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(factLinks).extracting(SnapshotFactLink::recordedValue).containsExactlyInAnyOrder("1200 kWp", "5000000 INR");
    }

    @Test
    void markStaleAndQueryStaleSnapshots() throws Exception {
        FactSnapshot snap1 = repository.saveSnapshot(10L, "cma_extract", "table_1",
                List.of(new FactLinkWrite(101L, "1200 kWp")));
        FactSnapshot snap2 = repository.saveSnapshot(10L, "fm_comparison", "table_2",
                List.of(new FactLinkWrite(102L, "5000000")));

        assertThat(repository.findStaleSnapshots(10L)).isEmpty();

        repository.markStale(snap1.snapshotId(), "fact 101 superseded by fact 105");

        List<FactSnapshot> stale = repository.findStaleSnapshots(10L);
        assertThat(stale).hasSize(1);
        FactSnapshot staleSnap = stale.get(0);
        assertThat(staleSnap.snapshotId()).isEqualTo(snap1.snapshotId());
        assertThat(staleSnap.isStale()).isTrue();
        assertThat(staleSnap.staleReason()).isEqualTo("fact 101 superseded by fact 105");
        assertThat(staleSnap.staleAt()).isNotBlank();
    }

    @Test
    void reSavingSnapshotReplacesLinksCleanly() throws Exception {
        repository.saveSnapshot(10L, "cma_extract", "key_1",
                List.of(new FactLinkWrite(101L, "val1"), new FactLinkWrite(102L, "val2")));

        FactSnapshot updated = repository.saveSnapshot(10L, "cma_extract", "key_1",
                List.of(new FactLinkWrite(101L, "new_val1")));

        List<SnapshotFactLink> links = repository.findLinkedFacts(updated.snapshotId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).factId()).isEqualTo(101L);
        assertThat(links.get(0).recordedValue()).isEqualTo("new_val1");
    }

    @Test
    void foreignKeyCascadeOnSnapshotDeletion() throws Exception {
        FactSnapshot snap = repository.saveSnapshot(10L, "cma_extract", "key_del",
                List.of(new FactLinkWrite(101L, "val1")));

        try (Statement s = db.connection().createStatement()) {
            s.execute("DELETE FROM fact_snapshot WHERE snapshot_id = " + snap.snapshotId());
        }

        List<SnapshotFactLink> links = repository.findLinkedFacts(snap.snapshotId());
        assertThat(links).isEmpty();
    }

    @Test
    void findSnapshotByIdAndReferencingFacts() throws Exception {
        FactSnapshot snap1 = repository.saveSnapshot(10L, "cma_extract", "key_a",
                List.of(new FactLinkWrite(101L, "v1")));
        FactSnapshot snap2 = repository.saveSnapshot(10L, "cma_extract", "key_b",
                List.of(new FactLinkWrite(101L, "v2")));

        assertThat(repository.findSnapshotById(snap1.snapshotId()))
                .isPresent()
                .get()
                .extracting(FactSnapshot::outputKey)
                .isEqualTo("key_a");

        List<Long> referencing = repository.findSnapshotIdsReferencingFact(101L);
        assertThat(referencing).containsExactlyInAnyOrder(snap1.snapshotId(), snap2.snapshotId());
    }

    @Test
    void evaluateFreshnessMarksSnapshotsWithSupersededFactsAsStale() throws Exception {
        FactSnapshot snap1 = repository.saveSnapshot(10L, "tev_summary", "key1",
                List.of(new FactLinkWrite(101L, "1200 kWp"), new FactLinkWrite(102L, "5000000")));
        FactSnapshot snap2 = repository.saveSnapshot(10L, "tev_summary", "key2",
                List.of(new FactLinkWrite(102L, "5000000")));

        assertThat(repository.countSnapshots(10L)).isEqualTo(2);
        assertThat(repository.countStaleSnapshots(10L)).isEqualTo(0);

        // Simulate fact 101 being superseded by fact 103 (doc 2)
        try (Statement s = db.connection().createStatement()) {
            s.execute("INSERT INTO document (document_id, mandate_id, logical_name, doc_family, created_at, updated_at)"
                    + " VALUES (2, 10, 'Revised Quote', 'quotation', '2026-10-21T10:00:00Z', '2026-10-21T10:00:00Z')");
            s.execute("INSERT INTO document_version (document_version_id, document_id, version_label, file_name, file_hash, file_ext, file_role, ingested_at)"
                    + " VALUES (2, 2, 'v2', 'quote_v2.pdf', 'hash2', 'pdf', 'quotation', '2026-10-21T10:00:00Z')");
            s.execute("INSERT INTO fact (fact_id, parse_run_id, document_version_id, field_key, value_kind, value_text, value_number, extraction_method, created_at, is_current)"
                    + " VALUES (103, 1, 2, 'solar_capacity', 'quantity', '1400 kWp', 1400.0, 'deterministic', '2026-10-21T10:00:00Z', 1)");
            s.execute("UPDATE fact SET is_current = 0, superseded_by_fact_id = 103 WHERE fact_id = 101");
        }

        int invalidated = repository.evaluateFreshness(10L);
        assertThat(invalidated).isEqualTo(1);
        assertThat(repository.countStaleSnapshots(10L)).isEqualTo(1);

        Optional<FactSnapshot> staleSnap1 = repository.findSnapshot(10L, "tev_summary", "key1");
        assertThat(staleSnap1).isPresent();
        assertThat(staleSnap1.get().isStale()).isTrue();
        assertThat(staleSnap1.get().staleReason()).contains("fact #101").contains("superseded by fact #103").contains("doc #2");
        assertThat(staleSnap1.get().staleAt()).isNotBlank();

        Optional<FactSnapshot> freshSnap2 = repository.findSnapshot(10L, "tev_summary", "key2");
        assertThat(freshSnap2).isPresent();
        assertThat(freshSnap2.get().isStale()).isFalse();
    }
}
