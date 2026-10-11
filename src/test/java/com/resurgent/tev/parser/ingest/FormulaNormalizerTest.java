package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
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

    @Test
    void uppercasingIsStableUnderTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(FormulaNormalizer.normalize("if(a1>0, \"i\", b1)"))
                    .isEqualTo("IF(A1>0, \"i\", B1)");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void stripsExcelFunctionPrefix_xlfn() {
        // Newer Excel functions use _xlfn. prefix (e.g., MINIFS, MAXIFS, IFS)
        assertThat(FormulaNormalizer.normalize("_xlfn.MINIFS(a1:a10, b1:b10, \">5\")"))
                .isEqualTo("MINIFS(A1:A10, B1:B10, \">5\")");
        assertThat(FormulaNormalizer.normalize("_xlfn.IFS(a1>0, \"yes\", true, \"no\")"))
                .isEqualTo("IFS(A1>0, \"yes\", TRUE, \"no\")");
        assertThat(FormulaNormalizer.normalize("=_xlfn.MAXIFS(a:a, b:b, 10)"))
                .isEqualTo("MAXIFS(A:A, B:B, 10)");
        // Multiple function prefixes in one formula
        assertThat(FormulaNormalizer.normalize("_xlfn.IFS(_xlfn.MINIFS(a1:a5)>0, \"high\", true, \"low\")"))
                .isEqualTo("IFS(MINIFS(A1:A5)>0, \"high\", TRUE, \"low\")");
    }
}
