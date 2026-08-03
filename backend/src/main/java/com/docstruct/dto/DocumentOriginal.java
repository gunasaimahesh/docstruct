package com.docstruct.dto;

/** Bytes + metadata for streaming an uploaded original file. */
public record DocumentOriginal(
        String filename,
        String contentType,
        byte[] bytes
) {
}
