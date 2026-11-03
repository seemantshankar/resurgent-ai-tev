package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.ConflictRepository.ConflictFactRow;
import org.junit.jupiter.api.Test;

/** {@link ConflictScan#compareBoundPath} grouping for doc-doc conflicts (#205). */
class ConflictScanCompareBoundPathTest {

    @Test
    void boundFactsOnSamePathShareIdentityEvenWhenFieldKeysDiffer() {
        ConflictFactRow left = row("schema_a", "commercial > Offtake > Tariff", "bound", null);
        ConflictFactRow right = row("schema_b", "commercial > Offtake > Tariff", "bound", null);

        assertThat(ConflictScan.compareBoundPath(left))
                .isEqualTo(ConflictScan.compareBoundPath(right));
    }

    @Test
    void sharedSeedFieldKeyMapsToPackPathForDocumentLevelFacts() {
        ConflictFactRow reportTariff = row("tariff", "commercial > Indicative Tariff", "bound", null);
        ConflictFactRow agreementTariff = row("tariff", "commercial > Offtake > Tariff", "bound", null);

        assertThat(ConflictScan.compareBoundPath(reportTariff))
                .isEqualTo("path:commercial > Offtake > Tariff");
        assertThat(ConflictScan.compareBoundPath(agreementTariff))
                .isEqualTo("path:commercial > Offtake > Tariff");
    }

    @Test
    void rowFactsKeepFieldKeyWhenLineKeyPresent() {
        ConflictFactRow line = row("qty", "technical > Plant and machinery > Item", "bound", "Module");

        assertThat(ConflictScan.compareBoundPath(line)).isEqualTo("path:technical > Plant and machinery > Item");
    }

    private static ConflictFactRow row(String fieldKey, String path, String status, String lineKey) {
        return new ConflictFactRow(
                1L, 1L, 1L, "Doc", "doc.pdf", "report.dpr", "2024-01-01",
                fieldKey, path, status, null, lineKey,
                "rate", "1.0", null, null, null, null,
                null, null, null, null, null, 1, "quote",
                null, null);
    }
}
