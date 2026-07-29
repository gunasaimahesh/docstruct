package com.docstruct.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.docstruct.dto.CollectionDetailResponse;
import com.docstruct.dto.CollectionListResponse;
import com.docstruct.dto.MessageResponse;
import com.docstruct.dto.UploadResponse;
import com.docstruct.service.CollectionService;
import com.docstruct.service.IngestionService;

@RestController
@RequestMapping("/api/collections")
public class CollectionController {

    private final CollectionService collectionService;
    private final IngestionService ingestionService;

    public CollectionController(CollectionService collectionService, IngestionService ingestionService) {
        this.collectionService = collectionService;
        this.ingestionService = ingestionService;
    }

    /** Creates a new collection by uploading its first document. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "collectionName", required = false) String collectionName) {
        return ingestionService.ingestNewCollection(file, collectionName);
    }

    @GetMapping
    public CollectionListResponse list() {
        return new CollectionListResponse(true, collectionService.list());
    }

    @GetMapping("/{id}")
    public CollectionDetailResponse get(
            @PathVariable String id,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return collectionService.getDetail(id, tableName, Math.max(page, 1), Math.clamp(limit, 1, 1000));
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable String id) {
        String name = collectionService.delete(id);
        return MessageResponse.of("Collection \"%s\" deleted successfully".formatted(name));
    }
}
