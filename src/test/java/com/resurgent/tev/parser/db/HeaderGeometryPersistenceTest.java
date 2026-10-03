package com.resurgent.tev.parser.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.classify.HeaderGeometry;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeaderGeometryPersistenceTest {

    @TempDir
    Path dir;

    @Test
    void geometryRoundTripsAndIsReplacedWithTheRunsDispositions() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dir.resolve("w.db"))) {
            try (Statement s = db.connection().createStatement()) {
                s.execute("PRAGMA foreign_keys = OFF"); // parse_run / candidate rows are not under test
            }
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            HeaderGeometry geometry = new HeaderGeometry(
                    42L, List.of(new HeaderGeometry.Band(4, 7, 2, 7)), List.of(2));

            repo.insertHeaderGeometry(7L, geometry);
            repo.insertHeaderGeometry(7L, geometry); // re-asking one region replaces, not duplicates

            assertThat(repo.selectHeaderGeometry(7L)).containsOnlyKeys(42L).containsValue(geometry);
            assertThat(repo.selectHeaderGeometry(8L)).isEmpty();

            repo.deletePacketDispositionsForParseRun(7L);

            assertThat(repo.selectHeaderGeometry(7L)).isEmpty();
        }
    }
}
