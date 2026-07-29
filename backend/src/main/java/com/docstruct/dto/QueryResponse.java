package com.docstruct.dto;

import java.util.List;
import java.util.Map;

public record QueryResponse(boolean success, QueryResultDto result) {

    public record QueryResultDto(
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            String generatedSql,
            String explanation,
            String summary
    ) {
    }
}
