package com.docstruct.dto;

import java.util.List;

public record CollectionListResponse(boolean success, List<CollectionDto> collections) {
}
