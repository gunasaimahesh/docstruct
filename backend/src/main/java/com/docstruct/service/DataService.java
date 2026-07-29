package com.docstruct.service;

import org.springframework.stereotype.Service;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ValidationException;
import com.docstruct.repository.DynamicTableRepository;
import com.docstruct.util.SqlNameSanitizer;

/** Handles inline edits to extracted data cells. */
@Service
public class DataService {

    private final CollectionService collectionService;
    private final DynamicTableRepository dynamicTableRepository;

    public DataService(CollectionService collectionService, DynamicTableRepository dynamicTableRepository) {
        this.collectionService = collectionService;
        this.dynamicTableRepository = dynamicTableRepository;
    }

    public void updateCell(String collectionId, long rowId, String column, Object value) {
        CollectionEntity collection = collectionService.getOrThrow(collectionId);

        SchemaColumn schemaColumn = collection.getSchema().columns().stream()
                .filter(c -> c.name().equals(column)
                        || SqlNameSanitizer.sanitize(c.name()).equals(column.toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Column \"%s\" not found in schema".formatted(column)));

        dynamicTableRepository.updateCell(collectionId, rowId, schemaColumn.name(), coerce(value, schemaColumn));
    }

    /** Coerces the incoming JSON value to the SQL column's type. */
    private Object coerce(Object value, SchemaColumn column) {
        if (value == null) {
            return null;
        }
        return switch (column.type()) {
            case NUMBER, CURRENCY -> {
                if (value instanceof Number n) {
                    yield n.doubleValue();
                }
                try {
                    yield Double.parseDouble(value.toString().replaceAll("[,$€£¥₹\\s]", ""));
                } catch (NumberFormatException e) {
                    throw new ValidationException("Value for \"%s\" must be numeric".formatted(column.name()));
                }
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                yield switch (value.toString().toLowerCase()) {
                    case "true", "yes", "1", "y" -> Boolean.TRUE;
                    case "false", "no", "0", "n" -> Boolean.FALSE;
                    default -> throw new ValidationException(
                            "Value for \"%s\" must be a boolean".formatted(column.name()));
                };
            }
            default -> value.toString();
        };
    }
}
