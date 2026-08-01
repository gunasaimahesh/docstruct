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
        EntitySchema entitySchema,
        QueryHint queryHint
) {
    public SchemaColumn(String name, ColumnType type, String description, boolean required) {
        this(name, type, description, required, null, null);
    }

    public SchemaColumn(String name, ColumnType type, String description, boolean required,
                        EntitySchema entitySchema) {
        this(name, type, description, required, entitySchema, null);
    }

    @JsonIgnore
    public boolean isEntityArray() {
        return type == ColumnType.ENTITY_ARRAY;
    }

    /**
     * Whether this column is a filter/search target. Entity arrays are tables (not
     * filter columns); their nested scalars are resolved separately. Prose columns
     * with {@code filterable:false} remain searchable in the UI as contains-only.
     */
    @JsonIgnore
    public boolean isFilterable() {
        return !isEntityArray();
    }

    /** Whether the structured filter UI should offer this column for sorting. */
    @JsonIgnore
    public boolean isSortable() {
        if (isEntityArray()) {
            return false;
        }
        return queryHint == null || queryHint.isSortable();
    }
}
