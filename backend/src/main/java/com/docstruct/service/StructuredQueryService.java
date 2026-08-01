package com.docstruct.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.FilterOperator;
import com.docstruct.domain.QueryRole;
import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.FilterRequest;
import com.docstruct.dto.FilterRequest.FilterCondition;
import com.docstruct.dto.FilterRequest.SortSpec;
import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.docstruct.exception.ValidationException;
import com.docstruct.repository.DynamicTableRepository;
import com.docstruct.repository.DynamicTableRepository.ChildFilterQuery;
import com.docstruct.repository.DynamicTableRepository.FilterClause;
import com.docstruct.repository.DynamicTableRepository.FilterPage;
import com.docstruct.repository.DynamicTableRepository.FilterQuery;
import com.docstruct.service.AnswerComposer.ComposeOptions;
import com.docstruct.util.SqlNameSanitizer;
import com.docstruct.util.ValueParser;

/**
 * Deterministic filter/sort over a collection's data table — no LLM involved.
 *
 * <p>Safety comes from construction, not validation after the fact: every
 * column must already be in the collection schema (sanitized), every operator
 * is an enum, and every value is a JDBC bind parameter. There is no path from
 * user input into the SQL string itself.
 *
 * <p>Unit of retrieval follows the filter level: when conditions target a single
 * nested entity, matching <em>child entries</em> are returned by default (with a
 * parent locator). Pass {@code resultUnit=documents} to keep the older
 * document-centric {@code EXISTS} path. When {@code match=all}, multiple
 * conditions on the same entity must hold on the same child row.
 */
@Service
public class StructuredQueryService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_DISTINCT_LIMIT = 200;
    private static final int MAX_DISTINCT_LIMIT = 500;
    private static final String DEFAULT_SORT = "_row_id";

    private final CollectionService collectionService;
    private final DynamicTableRepository dynamicTableRepository;
    private final AnswerComposer answerComposer;

    public StructuredQueryService(CollectionService collectionService,
                                  DynamicTableRepository dynamicTableRepository,
                                  AnswerComposer answerComposer) {
        this.collectionService = collectionService;
        this.dynamicTableRepository = dynamicTableRepository;
        this.answerComposer = answerComposer;
    }

    /**
     * Distinct non-empty values for a schema column (main table or nested entity).
     * Source of truth is the data table — never schema metadata or the LLM.
     *
     * @param entity optional top-level entity_array column / entitySchema name
     */
    public List<String> distinctValues(String collectionId, String column, String entity, Integer limit) {
        CollectionEntity collection = collectionService.getOrThrow(collectionId);
        ResolvedColumn resolved = resolveTarget(column, entity, collection.getSchema().columns());
        int capped = limit == null
                ? DEFAULT_DISTINCT_LIMIT
                : Math.clamp(limit, 1, MAX_DISTINCT_LIMIT);
        return dynamicTableRepository.distinctValues(
                collectionId,
                resolved.childEntityName(),
                SqlNameSanitizer.sanitize(resolved.column().name()),
                capped);
    }

    public QueryResultDto filter(String collectionId, FilterRequest request) {
        CollectionEntity collection = collectionService.getOrThrow(collectionId);
        List<SchemaColumn> schemaColumns = collection.getSchema().columns();
        Map<String, SchemaColumn> mainColumns = valueColumns(schemaColumns);

        boolean matchAny = "any".equalsIgnoreCase(request.match() == null ? "all" : request.match());
        if (request.match() != null
                && !"all".equalsIgnoreCase(request.match())
                && !"any".equalsIgnoreCase(request.match())) {
            throw new ValidationException("match must be \"all\" or \"any\"", "Got: " + request.match());
        }
        if (request.resultUnit() != null && !request.resultUnit().isBlank()
                && request.resultUnitOrNull() == null) {
            throw new ValidationException("resultUnit must be \"entries\" or \"documents\"",
                    "Got: " + request.resultUnit());
        }
        if (request.sort() != null
                && request.sort().direction() != null
                && !"asc".equalsIgnoreCase(request.sort().direction())
                && !"desc".equalsIgnoreCase(request.sort().direction())) {
            throw new ValidationException("sort direction must be \"asc\" or \"desc\"",
                    "Got: " + request.sort().direction());
        }

        int page = request.page() == null ? 1 : Math.max(request.page(), 1);
        int limit = request.limit() == null
                ? DEFAULT_LIMIT
                : Math.clamp(request.limit(), 1, MAX_LIMIT);
        int offset = (page - 1) * limit;

        String nestedEntity = singleNestedEntity(request.filtersOrEmpty(), schemaColumns);
        boolean useEntries = resolveUseEntries(request, nestedEntity);
        if (useEntries) {
            return filterEntries(collectionId, request, schemaColumns, mainColumns,
                    nestedEntity, matchAny, limit, offset);
        }

        List<FilterClause> clauses = buildDocumentClauses(collectionId, request.filtersOrEmpty(),
                schemaColumns, matchAny);

        String sortColumn = resolveSortColumn(request.sort(), mainColumns);
        boolean sortDescending = request.sort() != null
                && "desc".equalsIgnoreCase(request.sort().direction());

        FilterPage result = dynamicTableRepository.filterRows(collectionId, new FilterQuery(
                clauses, matchAny, sortColumn, sortDescending, limit, offset));

        int total = result.total() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) result.total();
        return answerComposer.compose(
                result.rows(),
                result.sql(),
                "Structured filter over this collection's data table.",
                ComposeOptions.of(total, request.excludeLowConfidenceOrFalse()));
    }

    /**
     * Entity-centric path: return matching child rows for one nested entity,
     * with an optional parent locator column.
     */
    private QueryResultDto filterEntries(String collectionId, FilterRequest request,
                                         List<SchemaColumn> schemaColumns,
                                         Map<String, SchemaColumn> mainColumns,
                                         String nestedEntity, boolean matchAny,
                                         int limit, int offset) {
        List<FilterClause> childClauses = new ArrayList<>();
        List<FilterClause> parentClauses = new ArrayList<>();
        for (FilterCondition condition : request.filtersOrEmpty()) {
            PreparedPredicate prepared = preparePredicate(condition, schemaColumns);
            if (prepared.childEntityName() == null) {
                // Re-qualify as main.* — preparePredicate already used main. for these.
                parentClauses.add(prepared.predicate());
            } else if (nestedEntity.equals(prepared.childEntityName())) {
                childClauses.add(prepared.predicate());
            } else {
                throw new ValidationException(
                        "Cannot return entries for multiple nested entities at once",
                        "Got conditions on " + nestedEntity + " and " + prepared.childEntityName());
            }
        }

        SchemaColumn entityCol = findEntitySchema(schemaColumns, nestedEntity);
        String entityLabel = entityCol != null && entityCol.entitySchema() != null
                ? entityCol.entitySchema().name()
                : nestedEntity;
        String parentLabel = pickParentLabelColumn(mainColumns);

        FilterPage result = dynamicTableRepository.filterChildRows(collectionId, new ChildFilterQuery(
                nestedEntity, childClauses, parentClauses, matchAny, parentLabel, limit, offset));

        int total = result.total() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) result.total();
        return answerComposer.compose(
                result.rows(),
                result.sql(),
                "Structured filter over matching " + entityLabel + " entries.",
                ComposeOptions.entries(total, request.excludeLowConfidenceOrFalse(), entityLabel));
    }

    /**
     * Default to entries when the filter targets a single nested entity;
     * {@code resultUnit} overrides. Forcing entries without a nested target is rejected.
     */
    private static boolean resolveUseEntries(FilterRequest request, String nestedEntity) {
        String unit = request.resultUnitOrNull();
        if ("documents".equals(unit)) {
            return false;
        }
        if ("entries".equals(unit)) {
            if (nestedEntity == null) {
                throw new ValidationException(
                        "resultUnit=entries requires a filter on a nested entity",
                        "Add an entity-scoped condition, or use resultUnit=documents");
            }
            return true;
        }
        return nestedEntity != null;
    }

    /**
     * The single nested entity targeted by the filters, or null when there is none
     * or more than one (multi-entity filters stay document-centric).
     */
    private String singleNestedEntity(List<FilterCondition> conditions, List<SchemaColumn> schemaColumns) {
        String found = null;
        for (FilterCondition condition : conditions) {
            if (condition.entity() == null || condition.entity().isBlank()) {
                continue;
            }
            ResolvedColumn resolved = resolveTarget(condition.column(), condition.entity(), schemaColumns);
            if (resolved.childEntityName() == null) {
                continue;
            }
            if (found == null) {
                found = resolved.childEntityName();
            } else if (!found.equals(resolved.childEntityName())) {
                return null;
            }
        }
        return found;
    }

    private static SchemaColumn findEntitySchema(List<SchemaColumn> schemaColumns, String entityName) {
        for (SchemaColumn col : schemaColumns) {
            if (col.isEntityArray() && col.entitySchema() != null
                    && col.entitySchema().name().equals(entityName)) {
                return col;
            }
        }
        return findEntityColumn(schemaColumns, entityName);
    }

    /**
     * Prefer person/company-like roles for the parent locator; fall back to the
     * first non-description text column.
     */
    private static String pickParentLabelColumn(Map<String, SchemaColumn> mainColumns) {
        SchemaColumn best = null;
        int bestRank = Integer.MAX_VALUE;
        for (SchemaColumn col : mainColumns.values()) {
            int rank = parentLabelRank(col);
            if (rank < bestRank) {
                bestRank = rank;
                best = col;
            }
        }
        return best == null ? null : SqlNameSanitizer.sanitize(best.name());
    }

    private static int parentLabelRank(SchemaColumn col) {
        QueryRole role = col.queryHint() == null ? null : col.queryHint().role();
        if (role == QueryRole.PERSON_NAME) {
            return 0;
        }
        if (role == QueryRole.COMPANY || role == QueryRole.ORGANIZATION) {
            return 1;
        }
        if (role == QueryRole.IDENTIFIER || role == QueryRole.EMAIL) {
            return 3;
        }
        if (role == QueryRole.DESCRIPTION) {
            return 100;
        }
        if (col.type() == ColumnType.TEXT || col.type() == ColumnType.EMAIL) {
            return 2;
        }
        return 50;
    }

    /**
     * Document-centric path: main-table conditions stay one clause each. Nested
     * conditions on the same entity are folded into a single EXISTS when
     * {@code matchAny} is false so they must hold on the same child row.
     */
    private List<FilterClause> buildDocumentClauses(String collectionId, List<FilterCondition> conditions,
                                                    List<SchemaColumn> schemaColumns, boolean matchAny) {
        List<FilterClause> clauses = new ArrayList<>();
        // Preserve request order: main clauses and nested groups interleaved by first appearance.
        Map<String, List<PreparedPredicate>> nestedByEntity = new LinkedHashMap<>();
        List<Object> ordered = new ArrayList<>(); // FilterClause | String (entity key)

        for (FilterCondition condition : conditions) {
            PreparedPredicate prepared = preparePredicate(condition, schemaColumns);
            if (prepared.childEntityName() == null) {
                ordered.add(prepared.predicate());
                continue;
            }
            boolean first = !nestedByEntity.containsKey(prepared.childEntityName());
            nestedByEntity.computeIfAbsent(prepared.childEntityName(), k -> new ArrayList<>()).add(prepared);
            if (first) {
                ordered.add(prepared.childEntityName());
            }
        }

        for (Object item : ordered) {
            if (item instanceof FilterClause mainClause) {
                clauses.add(mainClause);
                continue;
            }
            String entityName = (String) item;
            List<PreparedPredicate> group = nestedByEntity.get(entityName);
            if (matchAny) {
                // OR across conditions: each gets its own EXISTS (any child row may match).
                for (PreparedPredicate prepared : group) {
                    clauses.add(wrapExists(collectionId, entityName, List.of(prepared.predicate()), true));
                }
            } else {
                // AND across conditions: one EXISTS, same child row must satisfy all.
                List<FilterClause> predicates = group.stream().map(PreparedPredicate::predicate).toList();
                clauses.add(wrapExists(collectionId, entityName, predicates, true));
            }
        }
        return clauses;
    }

    private PreparedPredicate preparePredicate(FilterCondition condition, List<SchemaColumn> schemaColumns) {
        ResolvedColumn resolved = resolveTarget(condition.column(), condition.entity(), schemaColumns);

        FilterOperator operator;
        try {
            operator = FilterOperator.fromJson(condition.operator());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Unknown operator: " + condition.operator(),
                    "Supported: eq, neq, contains, starts_with, ends_with, gt, gte, lt, lte, is_empty, is_not_empty");
        }

        String qualifier = resolved.childEntityName() == null ? "main." : "c.";
        String quoted = qualifier + quote(SqlNameSanitizer.sanitize(resolved.column().name()));

        FilterClause predicate;
        if (!operator.requiresValue()) {
            predicate = emptyClause(quoted, resolved.column().type(), operator == FilterOperator.IS_EMPTY);
        } else {
            if (condition.value() == null || (condition.value() instanceof String s && s.isBlank())) {
                throw new ValidationException(
                        "Operator " + operator.toJson() + " requires a value",
                        "Column: " + condition.column());
            }
            Object bound = coerce(condition.value(), resolved.column().type(), operator);
            predicate = valueClause(quoted, operator, bound);
        }
        return new PreparedPredicate(resolved.childEntityName(), predicate);
    }

    private static FilterClause wrapExists(String collectionId, String childEntityName,
                                           List<FilterClause> predicates, boolean andJoin) {
        String childTable = DynamicTableRepository.dataTableName(collectionId, childEntityName);
        String joiner = andJoin ? " AND " : " OR ";
        StringBuilder body = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                body.append(joiner);
            }
            FilterClause predicate = predicates.get(i);
            body.append('(').append(predicate.sqlFragment()).append(')');
            params.addAll(predicate.params());
        }
        String exists = "EXISTS (SELECT 1 FROM " + quote(childTable)
                + " AS c WHERE c._parent_row_id = main._row_id AND "
                + body + ")";
        return new FilterClause(exists, params);
    }

    private record PreparedPredicate(String childEntityName, FilterClause predicate) {
    }

    private static FilterClause emptyClause(String quoted, ColumnType type, boolean empty) {
        boolean textish = type != ColumnType.NUMBER && type != ColumnType.CURRENCY && type != ColumnType.BOOLEAN;
        if (empty) {
            String sql = textish
                    ? quoted + " IS NULL OR TRIM(CAST(" + quoted + " AS TEXT)) = ''"
                    : quoted + " IS NULL";
            return new FilterClause(sql, List.of());
        }
        String sql = textish
                ? quoted + " IS NOT NULL AND TRIM(CAST(" + quoted + " AS TEXT)) <> ''"
                : quoted + " IS NOT NULL";
        return new FilterClause(sql, List.of());
    }

    private static FilterClause valueClause(String quoted, FilterOperator operator, Object bound) {
        return switch (operator) {
            case EQ -> new FilterClause(quoted + " = ?", List.of(bound));
            case NEQ -> new FilterClause(quoted + " IS DISTINCT FROM ?", List.of(bound));
            case CONTAINS -> new FilterClause(
                    "CAST(" + quoted + " AS TEXT) ILIKE ? ESCAPE '\\'",
                    List.of("%" + escapeLike(String.valueOf(bound)) + "%"));
            case STARTS_WITH -> new FilterClause(
                    "CAST(" + quoted + " AS TEXT) ILIKE ? ESCAPE '\\'",
                    List.of(escapeLike(String.valueOf(bound)) + "%"));
            case ENDS_WITH -> new FilterClause(
                    "CAST(" + quoted + " AS TEXT) ILIKE ? ESCAPE '\\'",
                    List.of("%" + escapeLike(String.valueOf(bound))));
            case GT -> new FilterClause(quoted + " > ?", List.of(bound));
            case GTE -> new FilterClause(quoted + " >= ?", List.of(bound));
            case LT -> new FilterClause(quoted + " < ?", List.of(bound));
            case LTE -> new FilterClause(quoted + " <= ?", List.of(bound));
            case IS_EMPTY, IS_NOT_EMPTY -> throw new IllegalStateException("unreachable");
        };
    }

    private Object coerce(Object value, ColumnType type, FilterOperator operator) {
        if (operator == FilterOperator.CONTAINS
                || operator == FilterOperator.STARTS_WITH
                || operator == FilterOperator.ENDS_WITH) {
            return String.valueOf(value);
        }

        return switch (type) {
            case NUMBER, CURRENCY -> {
                if (value instanceof Number n) {
                    yield n.doubleValue();
                }
                Double parsed = ValueParser.parseNumber(String.valueOf(value));
                if (parsed == null) {
                    throw new ValidationException(
                            "Expected a number for column of type " + type.toJson(),
                            "Got: " + value);
                }
                yield parsed;
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                Boolean parsed = ValueParser.parseBoolean(String.valueOf(value));
                if (parsed == null) {
                    throw new ValidationException(
                            "Expected a boolean (true/false, yes/no)",
                            "Got: " + value);
                }
                yield parsed;
            }
            default -> String.valueOf(value);
        };
    }

    private String resolveSortColumn(SortSpec sort, Map<String, SchemaColumn> columns) {
        if (sort == null || sort.column() == null || sort.column().isBlank()) {
            return DEFAULT_SORT;
        }
        String sanitized = SqlNameSanitizer.sanitize(sort.column());
        if (DEFAULT_SORT.equals(sanitized)) {
            return DEFAULT_SORT;
        }
        if (!columns.containsKey(sanitized) || sanitized.startsWith("_")) {
            throw new ValidationException(
                    "Unknown or internal sort column: " + sort.column(),
                    "Only this collection's main-table schema columns can be sorted");
        }
        return sanitized;
    }

    private ResolvedColumn resolveTarget(String column, String entity, List<SchemaColumn> schemaColumns) {
        if (entity == null || entity.isBlank()) {
            SchemaColumn resolved = resolveColumn(column, valueColumns(schemaColumns));
            if (resolved == null) {
                throw new ValidationException(
                        "Unknown or internal column: " + column,
                        "Only this collection's schema columns can be filtered");
            }
            return new ResolvedColumn(resolved, null);
        }

        SchemaColumn entityCol = findEntityColumn(schemaColumns, entity);
        if (entityCol == null || entityCol.entitySchema() == null) {
            throw new ValidationException(
                    "Unknown entity: " + entity,
                    "entity must name a top-level entity_array column on this collection");
        }
        EntitySchema nested = entityCol.entitySchema();
        SchemaColumn nestedCol = resolveColumn(column, valueColumns(nested.columns()));
        if (nestedCol == null) {
            throw new ValidationException(
                    "Unknown column \"" + column + "\" on entity \"" + entity + "\"",
                    "Only nested columns of that entity_array can be filtered");
        }
        return new ResolvedColumn(nestedCol, nested.name());
    }

    private static SchemaColumn findEntityColumn(List<SchemaColumn> schemaColumns, String entity) {
        String sanitized = SqlNameSanitizer.sanitize(entity);
        for (SchemaColumn col : schemaColumns) {
            if (!col.isEntityArray() || col.entitySchema() == null) {
                continue;
            }
            if (col.name().equalsIgnoreCase(entity)
                    || SqlNameSanitizer.sanitize(col.name()).equals(sanitized)
                    || col.entitySchema().name().equalsIgnoreCase(entity)
                    || SqlNameSanitizer.sanitize(col.entitySchema().name()).equals(sanitized)) {
                return col;
            }
        }
        return null;
    }

    /** Flat value columns only — skips entity_array parents (their nested cols are resolved separately). */
    private static Map<String, SchemaColumn> valueColumns(List<SchemaColumn> columns) {
        Map<String, SchemaColumn> map = new LinkedHashMap<>();
        for (SchemaColumn col : columns) {
            if (!col.isEntityArray()) {
                map.put(SqlNameSanitizer.sanitize(col.name()), col);
            }
        }
        return map;
    }

    private static SchemaColumn resolveColumn(String requested, Map<String, SchemaColumn> columns) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String sanitized = SqlNameSanitizer.sanitize(requested);
        if (sanitized.startsWith("_")) {
            return null;
        }
        SchemaColumn column = columns.get(sanitized);
        if (column != null) {
            return column;
        }
        return columns.values().stream()
                .filter(c -> SqlNameSanitizer.sanitize(c.name()).equals(sanitized)
                        || c.name().equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** A resolved filter target: main-table column, or nested column + child entitySchema name. */
    private record ResolvedColumn(SchemaColumn column, String childEntityName) {
    }
}
