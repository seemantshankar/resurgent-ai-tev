package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicKindTokensTest {

    @TempDir
    Path dir;

    private static boolean observe(DynamicKindTokens d, String label, String kind, String wb, String sheet, int row) {
        return d.observe("expenses", label, kind, "", wb, sheet, row);
    }

    /** Three rows on two sheets of one workbook: enough to promote. */
    private static void teach(DynamicKindTokens d, String label, String kind) {
        observe(d, label, kind, "wb1", "ASSETS", 24);
        observe(d, label, kind, "wb1", "ASSETS", 41);
        observe(d, label, kind, "wb1", "Power", 17);
        d.persist();
    }

    @Test
    void promotedTermIsFoundAcrossSpellingVariantsWithinItsFamily() {
        var d = new DynamicKindTokens(dir);
        teach(d, "Fire Fighting Work", "money");

        var reloaded = new DynamicKindTokens(dir);
        assertThat(reloaded.lookup("expenses", "FIRE-FIGHTING WORKS (2.5% of civil cost)"))
                .hasValueSatisfying(t -> assertThat(t.kind()).isEqualTo("money"));
        assertThat(reloaded.lookup("Expenses ", "fire fighting work")).isPresent();
    }

    @Test
    void sameLabelInAnotherFamilyIsNotAHit() {
        var d = new DynamicKindTokens(dir);
        teach(d, "Chairs", "money");

        var reloaded = new DynamicKindTokens(dir);
        assertThat(reloaded.lookup("capacity", "Chairs")).isEmpty();
        assertThat(reloaded.lookup("", "Chairs")).isEmpty();
        assertThat(reloaded.lookup(null, "Chairs")).isEmpty();
    }

    @Test
    void oneRowAcrossManyYearColumnsIsOneObservation() {
        var d = new DynamicKindTokens(dir);
        for (int i = 0; i < 10; i++) {
            observe(d, "Plumbing Works", "money", "wb1", "ASSETS", 24);
        }
        d.persist();

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Plumbing Works")).isEmpty();
    }

    @Test
    void twoWorkbooksPromoteEvenWithOneRowEach() {
        var d = new DynamicKindTokens(dir);
        observe(d, "Plumbing Works", "money", "wb1", "ASSETS", 24);
        observe(d, "Plumbing Works", "money", "wb2", "Cost", 9);
        d.persist();

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Plumbing Works")).isPresent();
    }

    @Test
    void threeRowsOnOneSheetAreNotEnough() {
        var d = new DynamicKindTokens(dir);
        for (int row : new int[] {1, 2, 3}) {
            observe(d, "Plumbing Works", "money", "wb1", "ASSETS", row);
        }
        d.persist();

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Plumbing Works")).isEmpty();
    }

    @Test
    void sameWorkbookRerunIsIdempotent() {
        var d = new DynamicKindTokens(dir);
        observe(d, "Plumbing Works", "money", "wb1", "ASSETS", 24);
        d.persist();
        var again = new DynamicKindTokens(dir);
        observe(again, "Plumbing Works", "money", "wb1", "ASSETS", 24);
        again.persist();

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Plumbing Works")).isEmpty();
    }

    @Test
    void conflictingKindsAreQuarantinedNotPromoted() throws Exception {
        var d = new DynamicKindTokens(dir);
        teach(d, "Furniture and Accessories", "money");
        var second = new DynamicKindTokens(dir);
        observe(second, "Furniture and Accessories", "count", "wb2", "Stock", 3);
        observe(second, "Furniture and Accessories", "count", "wb3", "Stock", 4);
        second.persist();

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Furniture and Accessories")).isEmpty();
        assertThat(Files.readString(dir.resolve("quarantine.json"))).contains("furniture and accessory");
    }

    @Test
    void inconsistentRowIsIgnored() {
        var d = new DynamicKindTokens(dir);
        for (int i = 0; i < 3; i++) {
            d.observe("expenses", "Plumbing Works", i == 0 ? "money" : "percent", "", "wb1", "A", 5);
        }
        d.observe("expenses", "Plumbing Works", "money", "", "wb2", "A", 5);
        d.persist();

        // wb1 row is 1 money : 2 percent (purity .67 < .80) and is dropped; wb2 alone is not enough.
        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Plumbing Works")).isEmpty();
    }

    @Test
    void unlearnableInputsAreRejected() {
        var d = new DynamicKindTokens(dir);
        assertThat(observe(d, "Total", "money", "wb1", "S", 1)).isFalse();
        assertThat(observe(d, "28.0", "money", "wb1", "S", 1)).isFalse();
        assertThat(observe(d, "Plumbing Works", "rate", "wb1", "S", 1)).isFalse();
        assertThat(observe(d, "Plumbing Works", "money", "", "S", 1)).isFalse();
        assertThat(d.observe("", "Plumbing Works", "money", "", "wb1", "S", 1)).isFalse();
        assertThat(d.observe(null, "Plumbing Works", "money", "", "wb1", "S", 1)).isFalse();
        assertThat(observe(d, null, "money", "wb1", "S", 1)).isFalse();
    }

    @Test
    void quantityUnitIsCarriedOnPromotion() {
        var d = new DynamicKindTokens(dir);
        d.observe("capacity", "Banquet Chairs", "count", "nos", "wb1", "A", 1);
        d.observe("capacity", "Banquet Chairs", "count", "nos", "wb2", "A", 1);
        d.persist();

        assertThat(new DynamicKindTokens(dir).lookup("capacity", "Banquet Chairs"))
                .hasValueSatisfying(t -> assertThat(t.unit()).isEqualTo("nos"));
    }

    @Test
    void v1WordListsAreRetiredAndNeverRead() throws Exception {
        Files.writeString(dir.resolve("money_terms.txt"), "# junk\nPlumbing\n28.0\n");
        var d = new DynamicKindTokens(dir);

        assertThat(dir.resolve("money_terms.txt")).doesNotExist();
        assertThat(dir.resolve("money_terms.txt.v1.bak")).exists();
        assertThat(d.termCount()).isZero();
    }

    @Test
    void corruptFilesAreSetAsideAndDoNotBreakPersist() throws Exception {
        Files.writeString(dir.resolve("learned_candidates.json"), "{not json");
        Files.writeString(dir.resolve("terms.json"), "garbage");
        var d = new DynamicKindTokens(dir);
        observe(d, "Plumbing Works", "money", "wb1", "ASSETS", 1);
        d.persist();

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.startsWith("learned_candidates.json.corrupt-"))
                    .contains("learned_candidates.json");
        }
    }

    @Test
    void concurrentPersistsKeepEveryObservation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> jobs = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            jobs.add(pool.submit(() -> {
                var d = new DynamicKindTokens(dir);
                for (int i = 0; i < 5; i++) {
                    d.observe("expenses", "Shared Phrase", "money", "", "wb" + id, "S", i);
                    d.observe("expenses", "Own Phrase Number" + "x".repeat(id + 1), "money", "", "wb" + id, "S", i);
                }
                d.persist();
            }));
        }
        for (Future<?> f : jobs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        var reloaded = new DynamicKindTokens(dir);
        assertThat(reloaded.lookup("expenses", "Shared Phrase")).isPresent(); // 4 workbooks unioned
        String candidates = Files.readString(dir.resolve("learned_candidates.json"));
        for (int t = 0; t < 4; t++) {
            assertThat(candidates).contains("own phrase number" + "x".repeat(t + 1));
        }
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }
}
