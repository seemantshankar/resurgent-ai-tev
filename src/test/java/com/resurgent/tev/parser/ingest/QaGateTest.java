package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Sprint 1 QA gate (ticket 11): 100% cell reconciliation
 * and 100% external-reference reconciliation must hold before a parse run
 * may report status 'success'.
 */
class QaGateTest {

    @Test
    void fullReconciliation_passesAsSuccess() {
        QaGateResult result = QaGate.evaluate(9, 9, 2, 1, 1);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.passed()).isTrue();
        assertThat(result.cellsRejected()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void cellCountMismatch_flipsToPartialWithReason() {
        QaGateResult result = QaGate.evaluate(9, 7, 0, 0, 0);

        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.passed()).isFalse();
        assertThat(result.cellsRejected()).isEqualTo(2);
        assertThat(result.reasons()).anyMatch(r -> r.contains("cell_reconciliation_mismatch"));
    }

    @Test
    void noCellsWrittenWhenCellsWereExpected_flipsToFailed() {
        QaGateResult result = QaGate.evaluate(5, 0, 0, 0, 0);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.cellsRejected()).isEqualTo(5);
    }

    @Test
    void unaccountedExternalRef_flipsToPartialWithReason() {
        QaGateResult result = QaGate.evaluate(3, 3, 2, 1, 0);

        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.reasons()).anyMatch(r -> r.contains("external_ref_reconciliation_mismatch"));
    }

    @Test
    void zeroOccupiedCellsAndZeroWritten_isSuccess() {
        QaGateResult result = QaGate.evaluate(0, 0, 0, 0, 0);

        assertThat(result.status()).isEqualTo("success");
    }
}
