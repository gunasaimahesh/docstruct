package com.docstruct.domain.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where an extracted value came from and how well it held up to verification.
 *
 * {@code page} and {@code chunk} are the citation, resolved against the actual
 * {@link DocumentChunk} list rather than trusted as reported by the LLM.
 * {@code score} is the deterministic 0–1 confidence score that produced the
 * cell's confidence level, and {@code note} explains any deduction in plain
 * English so a reviewer can see *why* a field is uncertain.
 *
 * All fields are nullable: a field that is legitimately absent from the document
 * has no citation and no score, only a note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CellEvidence(
        Integer page,
        Integer chunk,
        Double score,
        String note
) {
    public static CellEvidence note(String note) {
        return new CellEvidence(null, null, null, note);
    }
}
