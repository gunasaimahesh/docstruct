package com.docstruct.domain.extraction;

/**
 * One addressable slice of a parsed document.
 *
 * Chunks are what make extraction auditable: the LLM sees the document as a
 * numbered list of chunks and must cite the chunk (and therefore the page) that
 * every extracted value came from. {@code index} is 1-based and stable for a
 * given parse, so a citation of "chunk 17" always resolves back to this text.
 */
public record DocumentChunk(int index, int page, String text) {
}
