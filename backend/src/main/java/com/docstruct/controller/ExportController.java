package com.docstruct.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.docstruct.service.ExportService;

@RestController
@RequestMapping("/api/collections/{collectionId}/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** Downloads the collection's data as CSV (default) or JSON. */
    @GetMapping
    public ResponseEntity<String> export(
            @PathVariable String collectionId,
            @RequestParam(value = "format", defaultValue = "csv") String format) {
        ExportService.Export export = exportService.export(collectionId, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s\"".formatted(export.filename()))
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.content());
    }
}
