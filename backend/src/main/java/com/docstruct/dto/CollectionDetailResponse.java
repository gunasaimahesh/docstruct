package com.docstruct.dto;

import java.util.List;
import java.util.Map;

public record CollectionDetailResponse(
        boolean success,
        CollectionDto collection,
        List<DocumentDto> documents,
        List<Map<String, Object>> data,
        long totalRows,
        int page,
        int limit,
        long totalPages
) {
}
