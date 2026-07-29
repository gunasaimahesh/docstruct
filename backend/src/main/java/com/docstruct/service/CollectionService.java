package com.docstruct.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.dto.CollectionDetailResponse;
import com.docstruct.dto.CollectionDto;
import com.docstruct.dto.DocumentDto;
import com.docstruct.exception.CollectionNotFoundException;
import com.docstruct.repository.CollectionRepository;
import com.docstruct.repository.DocumentRepository;
import com.docstruct.repository.DynamicTableRepository;

@Service
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final DynamicTableRepository dynamicTableRepository;

    public CollectionService(CollectionRepository collectionRepository,
                             DocumentRepository documentRepository,
                             DynamicTableRepository dynamicTableRepository) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.dynamicTableRepository = dynamicTableRepository;
    }

    public CollectionEntity getOrThrow(String id) {
        return collectionRepository.findById(id)
                .orElseThrow(() -> new CollectionNotFoundException(id));
    }

    public List<CollectionDto> list() {
        return collectionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(CollectionDto::from)
                .toList();
    }

    public CollectionDetailResponse getDetail(String id, String tableName, int page, int limit) {
        CollectionEntity collection = getOrThrow(id);

        List<DocumentDto> documents = documentRepository
                .findByCollectionIdOrderByCreatedAtDesc(id).stream()
                .map(DocumentDto::from)
                .toList();

        int offset = (page - 1) * limit;
        DynamicTableRepository.DataPage dataPage = dynamicTableRepository.getRows(id, tableName, limit, offset);

        return new CollectionDetailResponse(
                true,
                CollectionDto.from(collection),
                documents,
                dataPage.rows(),
                dataPage.total(),
                page,
                limit,
                (dataPage.total() + limit - 1) / limit);
    }

    /** Deletes a collection, its documents, and its dynamic data tables. Returns the name. */
    @Transactional
    public String delete(String id) {
        CollectionEntity collection = getOrThrow(id);
        dynamicTableRepository.dropTables(id, collection.getSchema().columns());
        documentRepository.deleteByCollectionId(id);
        collectionRepository.delete(collection);
        return collection.getName();
    }
}
