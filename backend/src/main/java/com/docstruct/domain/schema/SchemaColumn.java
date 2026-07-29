package com.docstruct.domain.schema;

import com.docstruct.domain.ColumnType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single column in an inferred document schema.
 * If {@code type == ENTITY_ARRAY}, {@code entitySchema} describes the nested child table.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaColumn(
        String name,
        ColumnType type,
        String description,
        boolean required,
        EntitySchema entitySchema
) {
    public SchemaColumn(String name, ColumnType type, String description, boolean required) {
        this(name, type, description, required, null);
    }

    @JsonIgnore
    public boolean isEntityArray() {
        return type == ColumnType.ENTITY_ARRAY;
    }
}
