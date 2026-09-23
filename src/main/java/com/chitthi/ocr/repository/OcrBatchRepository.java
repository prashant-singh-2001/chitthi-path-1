package com.chitthi.ocr.repository;

import com.chitthi.ocr.model.OcrBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OcrBatchRepository extends JpaRepository<OcrBatch, UUID> {

    List<OcrBatch> findByDocumentIdOrderByPageRange(UUID documentId);
}
