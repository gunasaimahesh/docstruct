package com.docstruct.controller;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docstruct.dto.CellUpdateRequest;
import com.docstruct.dto.MessageResponse;
import com.docstruct.service.DataService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/collections/{collectionId}/rows")
public class DataController {

    private final DataService dataService;

    public DataController(DataService dataService) {
        this.dataService = dataService;
    }

    /** Edits one cell of an extracted data row. */
    @PatchMapping("/{rowId}")
    public MessageResponse updateCell(
            @PathVariable String collectionId,
            @PathVariable long rowId,
            @Valid @RequestBody CellUpdateRequest request) {
        dataService.updateCell(collectionId, rowId, request.column(), request.value());
        return MessageResponse.of("Cell updated successfully");
    }
}
