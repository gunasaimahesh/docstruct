package com.docstruct.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.docstruct.domain.DocumentEntity;
import com.docstruct.dto.DocumentOriginal;
import com.docstruct.exception.DocumentNotFoundException;
import com.docstruct.repository.DocumentRepository;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Loads the stored original file for inline viewing. 404 when the document is
     * missing, belongs to another collection, or was ingested before originals were kept.
     */
    @Transactional(readOnly = true)
    public DocumentOriginal getOriginal(String collectionId, String documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!collectionId.equals(document.getCollectionId())
                || !document.getHasOriginal()
                || document.getOriginalBytes() == null
                || document.getOriginalBytes().length == 0) {
            throw new DocumentNotFoundException(documentId);
        }

        String contentType = document.getContentType() != null
                ? document.getContentType()
                : "application/octet-stream";

        return new DocumentOriginal(document.getFilename(), contentType, document.getOriginalBytes());
    }
}
