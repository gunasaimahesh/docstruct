package com.docstruct.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ColumnType;
import com.docstruct.util.FieldValidator.Outcome;

class FieldValidatorTest {

    @Test
    void acceptsRealIsoDatesAndRejectsImpossibleOnes() {
        assertThat(FieldValidator.validate("2026-03-14", ColumnType.DATE, "Date")).isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("2024-02-29", ColumnType.DATE, "Date")).isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("2026-02-31", ColumnType.DATE, "Date")).isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("2026-13-01", ColumnType.DATE, "Date")).isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("14/03/2026", ColumnType.DATE, "Date")).isEqualTo(Outcome.INVALID);
    }

    @Test
    void validatesEmailAddresses() {
        assertThat(FieldValidator.validate("billing@acme.com", ColumnType.EMAIL, "Email")).isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("billing@acme", ColumnType.EMAIL, "Email")).isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("two words@acme.com", ColumnType.EMAIL, "Email"))
                .isEqualTo(Outcome.INVALID);
    }

    @Test
    void validatesEmailsAndPhonesTypedAsPlainText() {
        assertThat(FieldValidator.validate("not-an-email", ColumnType.TEXT, "Contact Email"))
                .isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("+1 (555) 010-2938", ColumnType.TEXT, "Phone Number"))
                .isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("+91 8919584215", ColumnType.TEXT, "Contact Number"))
                .isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("555", ColumnType.TEXT, "Phone Number"))
                .isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("call the office", ColumnType.TEXT, "Mobile"))
                .isEqualTo(Outcome.INVALID);
    }

    @Test
    void identifiersMustLookLikeIdentifiers() {
        assertThat(FieldValidator.validate("INV-2041", ColumnType.TEXT, "Invoice Number")).isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("ACC 99 12", ColumnType.TEXT, "Account Ref")).isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate(
                "the invoice number is printed in the top right corner of the page",
                ColumnType.TEXT, "Invoice Number")).isEqualTo(Outcome.INVALID);
    }

    @Test
    void numbersAndBooleansMustAlreadyBeCoerced() {
        assertThat(FieldValidator.validate(1234.5, ColumnType.CURRENCY, "Total")).isEqualTo(Outcome.VALID);
        // A leftover String means the mapper could not coerce it to a number
        assertThat(FieldValidator.validate("about a thousand", ColumnType.CURRENCY, "Total"))
                .isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate(Boolean.TRUE, ColumnType.BOOLEAN, "Paid")).isEqualTo(Outcome.VALID);
    }

    @Test
    void freeTextAndAbsentValuesAreNotChecked() {
        assertThat(FieldValidator.validate("anything at all, really", ColumnType.TEXT, "Notes"))
                .isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(FieldValidator.validate(null, ColumnType.DATE, "Date")).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(FieldValidator.validate("anything", ColumnType.ENTITY_ARRAY, "Line Items"))
                .isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void validatesUrls() {
        assertThat(FieldValidator.validate("https://acme.com/invoices", ColumnType.URL, "Link"))
                .isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("www.acme.com", ColumnType.URL, "Link")).isEqualTo(Outcome.VALID);
        // Documents print links without a scheme far more often than with one
        assertThat(FieldValidator.validate("linkedin.com/in/jane-doe-a1850", ColumnType.URL, "Link"))
                .isEqualTo(Outcome.VALID);
        assertThat(FieldValidator.validate("acme", ColumnType.URL, "Link")).isEqualTo(Outcome.INVALID);
        assertThat(FieldValidator.validate("see the website", ColumnType.URL, "Link")).isEqualTo(Outcome.INVALID);
    }
}
