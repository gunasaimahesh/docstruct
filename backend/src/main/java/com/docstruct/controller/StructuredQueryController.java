package com.docstruct.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.docstruct.dto.ColumnValuesResponse;
import com.docstruct.dto.FilterRequest;
import com.docstruct.dto.QueryResponse;
import com.docstruct.service.StructuredQueryService;

import jakarta.validation.Valid;

/**
 * Deterministic, no-LLM filter/sort and column-value listing over a collection's
 * extracted data. Kept separate from {@link QueryController} so the rate limiter
 * on the natural-language path does not throttle free, local queries.
 */
@RestController
@RequestMapping("/api/collections/{collectionId}")
public class StructuredQueryController {

    private final StructuredQueryService structuredQueryService;

    public StructuredQueryController(StructuredQueryService structuredQueryService) {
        this.structuredQueryService = structuredQueryService;
    }

    @PostMapping("/filter")
    public QueryResponse filter(
            @PathVariable String collectionId,
            @Valid @RequestBody FilterRequest request) {
        return new QueryResponse(true, structuredQueryService.filter(collectionId, request));
    }

    /**
     * Distinct values currently stored for a schema column (main table, or a nested
     * entity_array when {@code entity} is set). Source of truth is the data table.
     */
    @GetMapping("/columns/{column}/values")
    public ColumnValuesResponse columnValues(
            @PathVariable String collectionId,
            @PathVariable String column,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) Integer limit) {
        return ColumnValuesResponse.of(
                structuredQueryService.distinctValues(collectionId, column, entity, limit));
    }
}
