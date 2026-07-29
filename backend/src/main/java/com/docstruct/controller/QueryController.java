package com.docstruct.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docstruct.dto.QueryRequest;
import com.docstruct.dto.QueryResponse;
import com.docstruct.service.QueryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/collections/{collectionId}/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** Runs a natural-language query against the collection's extracted data. */
    @PostMapping
    public QueryResponse query(
            @PathVariable String collectionId,
            @Valid @RequestBody QueryRequest request) {
        return new QueryResponse(true, queryService.query(collectionId, request.query()));
    }
}
