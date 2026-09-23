package com.chitthi.document.repository;

import com.chitthi.document.model.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {

    List<Page> findByDocumentIdOrderByPageNo(UUID documentId);

    List<Page> findByDocumentIdAndPageNoBetweenOrderByPageNo(UUID documentId, int firstPage, int lastPage);
}
