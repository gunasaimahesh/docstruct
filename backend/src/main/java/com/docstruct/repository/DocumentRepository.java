package com.docstruct.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.docstruct.domain.DocumentEntity;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findByCollectionIdOrderByCreatedAtDesc(String collectionId);

    void deleteByCollectionId(String collectionId);
}
