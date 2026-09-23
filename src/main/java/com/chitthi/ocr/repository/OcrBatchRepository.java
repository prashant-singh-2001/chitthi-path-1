package com.chitthi.ocr.repository;

import com.chitthi.ocr.model.OcrBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OcrBatchRepository extends JpaRepository<OcrBatch, UUID> {

    List<OcrBatch> findByDocumentIdOrderByPageRange(UUID documentId);

    /**
     * The actual compare-and-set: only a row still PENDING with attempts left
     * is claimed, in one atomic UPDATE. Two concurrent callers claiming the
     * same batch (e.g. a redelivered message racing the poller's redispatch
     * sweep) can only have one of them affect a row - the other's WHERE
     * clause fails to match once the first has committed, and Postgres'
     * row-level locking makes them serialize on that row rather than both
     * reading a stale PENDING and both believing they won. A read-then-write
     * in Java, even inside a transaction, would not give that guarantee.
     */
    @Modifying
    @Query("UPDATE OcrBatch b SET b.status = com.chitthi.ocr.model.OcrBatchStatus.RUNNING, "
            + "b.attempts = b.attempts + 1 "
            + "WHERE b.id = :id AND b.status = com.chitthi.ocr.model.OcrBatchStatus.PENDING "
            + "AND b.attempts < :maxAttempts")
    int claimForSubmit(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);
}
