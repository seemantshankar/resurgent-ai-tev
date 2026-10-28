package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Schema-path detection is structural: indexed and snake_case dotted paths
 * are citations to drop, while page figures and section numbers stay quotes.
 */
class SchemaPathQuoteTest {

    @Test
    void indexedAndSnakeCasePathsAreSchemaPaths() {
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("assets.0")).isTrue();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("assets.0.items.12")).isTrue();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath(
                "profit_and_loss_account.0.sections.2.items.12.label")).isTrue();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("vendor_name.legal")).isTrue();
    }

    @Test
    void numericPageFiguresAreNotSchemaPaths() {
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("Rs.4.50")).isFalse();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("Sec.3.2")).isFalse();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("v1.2.3")).isFalse();
        assertThat(SchemaPathQuote.looksLikeJsonSchemaPath("Art.12")).isFalse();
    }

    @Test
    void quoteFallbackKeepsPageFiguresAndDropsIndexedPaths() {
        assertThat(SchemaPathQuote.quoteOrFallback("Rs.4.50", "4.50")).isEqualTo("Rs.4.50");
        assertThat(SchemaPathQuote.quoteOrFallback("v1.2.3", "1.2.3")).isEqualTo("v1.2.3");
        assertThat(SchemaPathQuote.quoteOrFallback("assets.0", "Plant A")).isEqualTo("Plant A");
    }
}
