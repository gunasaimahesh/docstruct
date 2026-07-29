package com.docstruct.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlNameSanitizerTest {

    @Test
    void convertsToSnakeCase() {
        assertThat(SqlNameSanitizer.sanitize("Invoice Number")).isEqualTo("invoice_number");
        assertThat(SqlNameSanitizer.sanitize("Total (USD)")).isEqualTo("total_usd");
        assertThat(SqlNameSanitizer.sanitize("  Weird -- Name!! ")).isEqualTo("weird_name");
    }

    @Test
    void handlesDegenerateInput() {
        assertThat(SqlNameSanitizer.sanitize("")).isEqualTo("unnamed_column");
        assertThat(SqlNameSanitizer.sanitize("!!!")).isEqualTo("unnamed_column");
        assertThat(SqlNameSanitizer.sanitize(null)).isEqualTo("unnamed_column");
    }

    @Test
    void capsLengthAtPostgresLimit() {
        String longName = "a".repeat(100);
        assertThat(SqlNameSanitizer.sanitize(longName)).hasSize(63);
    }
}
