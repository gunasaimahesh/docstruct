package com.docstruct.dto;

import java.util.List;

/** Distinct values for one schema column, loaded from the collection's data table. */
public record ColumnValuesResponse(boolean success, List<String> values) {

    public static ColumnValuesResponse of(List<String> values) {
        return new ColumnValuesResponse(true, List.copyOf(values));
    }
}
