package com.docstruct.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error body shared by every failed API response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String error,
        String code,
        String details
) {
    public static ErrorResponse of(String error, String code, String details) {
        return new ErrorResponse(false, error, code, details);
    }
}
