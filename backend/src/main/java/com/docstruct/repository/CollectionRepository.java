package com.docstruct.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.docstruct.domain.CollectionEntity;

public interface CollectionRepository extends JpaRepository<CollectionEntity, String> {

    List<CollectionEntity> findAllByOrderByUpdatedAtDesc();
}
