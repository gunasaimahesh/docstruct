package com.docstruct.domain.extraction;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One semantic group of fields, named the way the document itself names it
 * ("Taxpayer Information", "Payment Summary", "Lab Results").
 *
 * Section *titles* may come from the extraction (how the document names its parts).
 * Section *coverage* is enforced against the schema: every top-level column appears
 * in exactly one section, so a forgetful model cannot hide extracted data from Knowledge.
 *
 * @param fields schema column names this section covers, in display order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSection(
        String title,
        String description,
        List<String> fields
) {
}
