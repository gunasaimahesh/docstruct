package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestionServiceTest {

    @Test
    void generatesNameFromTypeAndFilename() {
        assertThat(IngestionService.generateCollectionName("acme_march.pdf", "invoice"))
                .isEqualTo("invoice — acme march");
    }

    @Test
    void keepsFilenameWhenItAlreadyContainsType() {
        assertThat(IngestionService.generateCollectionName("Invoice-2026-03.pdf", "invoice"))
                .isEqualTo("Invoice 2026 03");
    }

    @Test
    void replacesUnderscoresInDocumentType() {
        assertThat(IngestionService.generateCollectionName("statement.csv", "bank_statement"))
                .isEqualTo("bank statement — statement");
    }
}
