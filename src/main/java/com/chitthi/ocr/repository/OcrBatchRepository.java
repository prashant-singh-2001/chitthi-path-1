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

    /**
     * Undoes {@link #claimForSubmit}'s claim when the submit never actually
     * happened - the rate limiter or circuit breaker turned it away before
     * the HTTP call. Decrementing {@code attempts} back out means the
     * attempt budget only ever counts submits that were actually tried.
     */
    @Modifying
    @Query("UPDATE OcrBatch b SET b.status = com.chitthi.ocr.model.OcrBatchStatus.PENDING, "
            + "b.attempts = b.attempts - 1 "
            + "WHERE b.id = :id AND b.status = com.chitthi.ocr.model.OcrBatchStatus.RUNNING")
    int releaseClaim(@Param("id") UUID id);

    /**
     * Locks due-for-polling batches without ever blocking on one another:
     * {@code SKIP LOCKED} means a batch another instance (or another sweep,
     * under overlap) is already holding is simply left for next time rather
     * than queuing this transaction behind it. The row lock never crosses
     * into an HTTP call - the caller extends {@code next_poll_at} as a lease
     * inside this same transaction, then releases the lock immediately by
     * committing, before polling Sarvam.
     */
    @Query(value = """
            SELECT * FROM ocr_batch
             WHERE status = 'RUNNING' AND sarvam_job_id IS NOT NULL AND next_poll_at <= now()
             ORDER BY next_poll_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OcrBatch> lockDueForPoll(@Param("limit") int limit);

    /**
     * The redispatch sweep's selection: a batch with no Sarvam job at all
     * past its dispatch lease - either its after-commit publish never
     * arrived, or a worker claimed it and then failed to submit. Same
     * SKIP LOCKED reasoning as {@link #lockDueForPoll}.
     */
    @Query(value = """
            SELECT * FROM ocr_batch
             WHERE status IN ('PENDING', 'RUNNING') AND sarvam_job_id IS NULL AND next_poll_at <= now()
               AND attempts < :maxSubmitAttempts
             ORDER BY next_poll_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OcrBatch> lockStalledForRedispatch(@Param("maxSubmitAttempts") int maxSubmitAttempts, @Param("limit") int limit);
}
