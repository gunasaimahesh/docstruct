package com.docstruct.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "must be a non-empty string") String query
) {
}
