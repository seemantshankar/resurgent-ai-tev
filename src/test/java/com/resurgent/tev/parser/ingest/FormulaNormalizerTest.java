package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct seam for formula normalisation rules that adapters feed into
 * {@code formula_normalized} (whitespace + case outside quotes).
 */
class FormulaNormalizerTest {

    @Test
    void collapsesWhitespaceOutsideQuotesAndUppercases() {
        assertThat(FormulaNormalizer.normalize("sum(  a1,  b1 )")).isEqualTo("SUM( A1, B1 )");
        assertThat(FormulaNormalizer.normalize("\"A  B\"  &  c1")).isEqualTo("\"A  B\" & C1");
        assertThat(FormulaNormalizer.normalize("'My  Sheet'!a1 + b1"))
                .isEqualTo("'My  Sheet'!A1 + B1");
    }

    @Test
    void leavesQuotedLiteralsAndEscapesUntouched() {
        assertThat(FormulaNormalizer.normalize("\"He said \"\"Hi\"\"\"")).isEqualTo("\"He said \"\"Hi\"\"\"");
        assertThat(FormulaNormalizer.normalize("'O''Brien'!A1")).isEqualTo("'O''Brien'!A1");
    }

    @Test
    void stripsLegacyEqualsPlusPrefix() {
        assertThat(FormulaNormalizer.normalize("=+A1+B1")).isEqualTo("A1+B1");
        assertThat(FormulaNormalizer.normalize("=A1+B1")).isEqualTo("A1+B1");
    }
}
