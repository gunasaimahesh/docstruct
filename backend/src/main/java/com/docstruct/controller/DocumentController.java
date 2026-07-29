package com.docstruct.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.docstruct.dto.UploadResponse;
import com.docstruct.service.IngestionService;

@RestController
@RequestMapping("/api/collections/{collectionId}/documents")
public class DocumentController {

    private final IngestionService ingestionService;

    public DocumentController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Adds a document to an existing collection, extracting against its schema. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(
            @PathVariable String collectionId,
            @RequestPart("file") MultipartFile file) {
        return ingestionService.ingestIntoCollection(collectionId, file);
    }
}
