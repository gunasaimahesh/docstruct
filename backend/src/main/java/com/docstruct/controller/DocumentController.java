package com.docstruct.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.docstruct.dto.DocumentOriginal;
import com.docstruct.dto.UploadResponse;
import com.docstruct.service.DocumentService;
import com.docstruct.service.IngestionService;

@RestController
@RequestMapping("/api/collections/{collectionId}/documents")
public class DocumentController {

    private final IngestionService ingestionService;
    private final DocumentService documentService;

    public DocumentController(IngestionService ingestionService, DocumentService documentService) {
        this.ingestionService = ingestionService;
        this.documentService = documentService;
    }

    /** Adds a document to an existing collection, extracting against its schema. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(
            @PathVariable String collectionId,
            @RequestPart("file") MultipartFile file) {
        return ingestionService.ingestIntoCollection(collectionId, file);
    }

    /**
     * Streams the original uploaded file for the Compare view.
     * Never embedded in collection JSON — fetch only when the UI needs it.
     */
    @GetMapping("/{documentId}/original")
    public ResponseEntity<byte[]> original(
            @PathVariable String collectionId,
            @PathVariable String documentId) {
        DocumentOriginal original = documentService.getOriginal(collectionId, documentId);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(original.contentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        ContentDisposition disposition = ContentDisposition.inline()
                .filename(original.filename())
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(original.bytes());
    }
}
