package com.docstruct.dto;

import jakarta.validation.constraints.NotBlank;

public record CellUpdateRequest(
        @NotBlank String column,
        Object value
) {
}
